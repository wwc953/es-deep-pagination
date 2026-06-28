package com.example.es.service;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.es.config.DeepPaginationProperties;
import com.example.es.dto.HybridSearchRequest;
import com.example.es.dto.PageResult;
import com.example.es.entity.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合搜索服务
 * 结合 BM25 关键词搜索 + KNN 向量搜索，在应用层使用 RRF（Reciprocal Rank Fusion）融合排序
 *
 * 与 ES 内置的 rank.rrf 不同，本实现将 RRF 逻辑放在应用层：
 * - 分别执行 BM25 关键词搜索和 KNN 向量搜索
 * - 在 Java 代码中按 RRF 公式融合排名
 * - 无需 ES Platinum/Enterprise 许可证，Basic 版即可使用
 *
 * RRF 公式：score(doc) = Σ 1 / (rank_i + k)
 *   - rank_i 是文档在第 i 次搜索中的排名（从 0 开始）
 *   - k 是排名常数（默认 60），防止排名第一的文档权重过大
 */
@Slf4j
@Service
public class HybridSearchServiceSelf {

    @Autowired
    private co.elastic.clients.elasticsearch.ElasticsearchClient elasticsearchClient;

    @Autowired
    private DeepPaginationProperties paginationProperties;

    @Autowired(required = false)
    private EmbeddingService embeddingService;

    /**
     * 执行混合搜索
     */
    public PageResult<Document> hybridSearch(HybridSearchRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. 解析查询向量
            float[] queryVector = resolveQueryVector(request);

            // 2. 判断是首次查询还是翻页查询
            if (request.getSearchAfter() != null && request.getSearchAfter().length > 0) {
                return continueHybridSearch(request, queryVector);
            } else {
                return startHybridSearch(request, queryVector);
            }
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            log.error("混合搜索失败, index={}, keyword={}, status={}, error={}",
                    request.getIndex(), request.getKeyword(), e.status(), e.error(), e);
            String errorMsg = String.format("混合搜索失败: status=%d, error=%s", e.status(), e.error());
            return PageResult.<Document>builder()
                    .errorMsg(errorMsg)
                    .costTime(System.currentTimeMillis() - startTime)
                    .build();
        } catch (Exception e) {
            log.error("混合搜索失败, index={}, keyword={}", request.getIndex(), request.getKeyword(), e);
            String errorMsg = "混合搜索失败: " + e.getMessage();
            return PageResult.<Document>builder()
                    .errorMsg(errorMsg)
                    .costTime(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 首次混合搜索：分别执行 BM25 和 KNN，然后 RRF 融合
     */
    private PageResult<Document> startHybridSearch(HybridSearchRequest request, float[] queryVector) throws IOException {
        long startTime = System.currentTimeMillis();

        // 创建 PIT（Point in Time）
        String pitId = createPit(request.getIndex());

        try {
            // 计算融合需要的召回数量（从每路搜索中取更多结果，融合后再截取 pageSize）
            int recallSize = request.getPageSize() * 3;

            // 1. BM25 关键词搜索
            SearchRequest bm25Request = buildBm25SearchRequest(request, pitId, recallSize);
            log.debug("BM25 搜索: index={}, keyword={}, pitId={}", request.getIndex(), request.getKeyword(), pitId);
            SearchResponse<Document> bm25Response = elasticsearchClient.search(bm25Request, Document.class);

            // 2. KNN 向量搜索
            SearchRequest knnRequest = buildKnnSearchRequest(request, queryVector, pitId, recallSize);
            log.debug("KNN 搜索: index={}, pitId={}", request.getIndex(), pitId);
            SearchResponse<Document> knnResponse = elasticsearchClient.search(knnRequest, Document.class);

            // 3. RRF 融合 + 分页
            PageResult<Document> result = mergeAndPaginate(bm25Response, knnResponse, request);
            result.setPitId(pitId);
            result.setCostTime(System.currentTimeMillis() - startTime);
            return result;
        } catch (Exception e) {
            // 查询失败时清理 PIT
            clearPit(pitId);
            throw e;
        }
    }

    /**
     * 继续混合搜索（翻页）
     */
    private PageResult<Document> continueHybridSearch(HybridSearchRequest request, float[] queryVector) throws IOException {
        long startTime = System.currentTimeMillis();

        // 将 Object[] 转换为 FieldValue[]
        List<co.elastic.clients.elasticsearch._types.FieldValue> fieldValues = Arrays.stream(request.getSearchAfter())
                .filter(Objects::nonNull)
                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                .collect(Collectors.toList());

        int recallSize = request.getPageSize() * 3;

        // 1. BM25 关键词搜索（带 searchAfter 翻页）
        SearchRequest bm25Request = buildBm25SearchRequest(request, request.getPitId(), recallSize, fieldValues);

        SearchResponse<Document> bm25Response = elasticsearchClient.search(bm25Request, Document.class);

        // 2. KNN 向量搜索（带 searchAfter 翻页）
        SearchRequest knnRequest = buildKnnSearchRequest(request, queryVector, request.getPitId(), recallSize, fieldValues);

        SearchResponse<Document> knnResponse = elasticsearchClient.search(knnRequest, Document.class);

        // 3. RRF 融合 + 分页
        PageResult<Document> result = mergeAndPaginate(bm25Response, knnResponse, request);
        result.setPitId(request.getPitId());
        result.setCostTime(System.currentTimeMillis() - startTime);
        return result;
    }

    /**
     * 构建 BM25 关键词搜索请求（首次查询）
     */
    private SearchRequest buildBm25SearchRequest(HybridSearchRequest request, String pitId, int size) {
        return buildBm25SearchRequest(request, pitId, size, null);
    }

    /**
     * 构建 BM25 关键词搜索请求（支持 searchAfter 翻页）
     */
    private SearchRequest buildBm25SearchRequest(HybridSearchRequest request, String pitId, int size,
                                                    List<co.elastic.clients.elasticsearch._types.FieldValue> searchAfter) {
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        // 关键词搜索（BM25）
        if (request.getKeyword() != null && !request.getKeyword().isEmpty()) {
            boolQuery.must(m -> m
                    .multiMatch(mm -> mm
                            .query(request.getKeyword())
                            .fields("title^2", "content")
                            .type(TextQueryType.BestFields)
                    )
            );
        }

        // 分类过滤
        if (request.getCategory() != null && !request.getCategory().isEmpty()) {
            boolQuery.filter(f -> f
                    .term(t -> t
                            .field("category")
                            .value(request.getCategory())
                    )
            );
        }

        // 状态过滤
        if (request.getStatus() != null) {
            boolQuery.filter(f -> f
                    .term(t -> t
                            .field("status")
                            .value(String.valueOf(request.getStatus()))
                    )
            );
        }

        // 排序：按 _score DESC + _doc ASC
        List<co.elastic.clients.elasticsearch._types.SortOptions> sorts = new ArrayList<>();
        sorts.add(co.elastic.clients.elasticsearch._types.SortOptions.of(s -> s
                .field(f -> f.field("_score").order(SortOrder.Desc))
        ));
        sorts.add(co.elastic.clients.elasticsearch._types.SortOptions.of(s -> s
                .field(f -> f.field("_doc").order(SortOrder.Asc))
        ));

        // 注意：使用 PIT 时不能指定 index，PIT 创建时已绑定索引
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .size(size)
                .query(Query.of(q -> q.bool(boolQuery.build())))
                .pit(p -> p
                        .id(pitId)
                        .keepAlive(t -> t.time(paginationProperties.getPitKeepAliveMinutes() + "m"))
                )
                .sort(sorts)
                .source(src -> src.filter(f -> f.excludes("titleVector")));

        if (searchAfter != null && !searchAfter.isEmpty()) {
            builder.searchAfter(searchAfter);
        }

        return builder.build();
    }

    /**
     * 构建 KNN 向量搜索请求（首次查询）
     */
    private SearchRequest buildKnnSearchRequest(HybridSearchRequest request, float[] queryVector,
                                                String pitId, int size) {
        return buildKnnSearchRequest(request, queryVector, pitId, size, null);
    }

    /**
     * 构建 KNN 向量搜索请求（支持 searchAfter 翻页）
     */
    private SearchRequest buildKnnSearchRequest(HybridSearchRequest request, float[] queryVector,
                                                String pitId, int size,
                                                List<co.elastic.clients.elasticsearch._types.FieldValue> searchAfter) {
        // 转换 float[] → List<Float>
        List<Float> vectorList = new ArrayList<>();
        for (float f : queryVector) {
            vectorList.add(f);
        }

        // 构建 KNN 过滤条件（ES 8.12+ 支持 knn.filter）
        // 注意：使用 PIT 时 KNN 搜索不能带顶层 query，过滤条件放在 knn.filter 中
        Query knnnFilter = buildKnnFilter(request);

        // 排序：按 _score DESC（向量相似度）+ _doc ASC
        List<co.elastic.clients.elasticsearch._types.SortOptions> sorts = new ArrayList<>();
        sorts.add(co.elastic.clients.elasticsearch._types.SortOptions.of(s -> s
                .field(f -> f.field("_score").order(SortOrder.Desc))
        ));
        sorts.add(co.elastic.clients.elasticsearch._types.SortOptions.of(s -> s
                .field(f -> f.field("_doc").order(SortOrder.Asc))
        ));

        // 注意：使用 PIT 时不能指定 index，PIT 创建时已绑定索引
        // 注意：使用 PIT 时 KNN 搜索不能带顶层 query，否则会 all shards failed
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .size(size)
                .knn(k -> {
                    k.field("titleVector")
                     .queryVector(vectorList)
                     .k(paginationProperties.getKnnK())
                     .numCandidates(paginationProperties.getKnnNumCandidates());
                    // 有过滤条件时设置 knn.filter
                    if (knnnFilter != null) {
                        k.filter(knnnFilter);
                    }
                    return k;
                })
                .pit(p -> p
                        .id(pitId)
                        .keepAlive(t -> t.time(paginationProperties.getPitKeepAliveMinutes() + "m"))
                )
                .sort(sorts)
                .source(src -> src.filter(f -> f.excludes("titleVector")));

        if (searchAfter != null && !searchAfter.isEmpty()) {
            builder.searchAfter(searchAfter);
        }

        return builder.build();
    }

    /**
     * 构建 KNN 过滤条件（category / status）
     * 当有过滤条件时返回 BoolQuery，无过滤时返回 null
     */
    private Query buildKnnFilter(HybridSearchRequest request) {
        boolean hasCategory = request.getCategory() != null && !request.getCategory().isEmpty();
        boolean hasStatus = request.getStatus() != null;

        if (!hasCategory && !hasStatus) {
            return null;
        }

        BoolQuery.Builder boolQuery = new BoolQuery.Builder();
        if (hasCategory) {
            boolQuery.filter(f -> f
                    .term(t -> t.field("category").value(request.getCategory()))
            );
        }
        if (hasStatus) {
            boolQuery.filter(f -> f
                    .term(t -> t.field("status").value(String.valueOf(request.getStatus())))
            );
        }
        return Query.of(q -> q.bool(boolQuery.build()));
    }

    /**
     * RRF 融合 + 分页
     *
     * 算法步骤：
     * 1. 从 BM25 结果中提取文档排名（按 _score 降序）
     * 2. 从 KNN 结果中提取文档排名（按 _score 降序）
     * 3. 对每个文档计算 RRF 分数：score = Σ 1 / (rank + k)
     * 4. 按 RRF 分数降序排序
     * 5. 截取 pageSize 大小作为当前页
     *
     * @param bm25Response BM25 搜索结果
     * @param knnResponse  KNN 向量搜索结果
     * @param request      搜索请求
     */
    private PageResult<Document> mergeAndPaginate(SearchResponse<Document> bm25Response,
                                                    SearchResponse<Document> knnResponse,
                                                    HybridSearchRequest request) {
        int rankConstant = paginationProperties.getRrfRankConstant();

        // 1. 收集所有文档，用 LinkedHashMap 保持插入顺序
        Map<String, Document> docMap = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        // 2. 处理 BM25 结果，计算 RRF 分数
        List<Hit<Document>> bm25Hits = bm25Response.hits().hits();
        for (int i = 0; i < bm25Hits.size(); i++) {
            Hit<Document> hit = bm25Hits.get(i);
            String docId = hit.id();
            Document doc = hit.source();
            doc.setId(docId);
            docMap.put(docId, doc);
            // RRF 公式：1 / (rank + k)，rank 从 0 开始
            double rrfScore = 1.0 / (i + rankConstant);
            rrfScores.merge(docId, rrfScore, Double::sum);
        }

        // 3. 处理 KNN 结果，累加 RRF 分数
        List<Hit<Document>> knnHits = knnResponse.hits().hits();
        for (int i = 0; i < knnHits.size(); i++) {
            Hit<Document> hit = knnHits.get(i);
            String docId = hit.id();
            Document doc = hit.source();
            doc.setId(docId);
            docMap.put(docId, doc);
            double rrfScore = 1.0 / (i + rankConstant);
            rrfScores.merge(docId, rrfScore, Double::sum);
        }

        // 4. 按 RRF 分数降序排序
        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(rrfScores.entrySet());
        sortedEntries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // 5. 提取排序后的文档列表
        List<Document> allDocs = sortedEntries.stream()
                .map(entry -> docMap.get(entry.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 6. 计算总数（取两路搜索的较大值作为近似总数）
        long bm25Total = bm25Response.hits().total() != null ? bm25Response.hits().total().value() : 0;
        long knnTotal = knnResponse.hits().total() != null ? knnResponse.hits().total().value() : 0;
        long total = Math.max(bm25Total, knnTotal);

        // 7. 截取当前页
        int pageSize = request.getPageSize();
        int fromIndex = 0;
        int toIndex = Math.min(pageSize, allDocs.size());
        List<Document> pageData = allDocs.subList(fromIndex, toIndex);

        // 8. 判断是否有下一页
        boolean hasNext = allDocs.size() > pageSize;

        // 9. 构建 searchAfter（用最后一个文档的排序值）
        Object[] searchAfter = null;
        if (hasNext && !pageData.isEmpty()) {
            // 使用 RRF 分数作为 searchAfter
            String lastDocId = pageData.get(pageData.size() - 1).getId();
            Double lastScore = rrfScores.get(lastDocId);
            searchAfter = new Object[]{lastScore != null ? lastScore : 0.0};
        }

        return PageResult.<Document>builder()
                .data(pageData)
                .total(total)
                .pageSize(pageSize)
                .hasNext(hasNext)
                .searchAfter(searchAfter)
                .build();
    }

    /**
     * 解析查询向量
     * 优先使用预计算的 queryVector，否则调用 EmbeddingService 计算
     */
    private float[] resolveQueryVector(HybridSearchRequest request) {
        if (request.getQueryVector() != null && !request.getQueryVector().isEmpty()) {
            // 使用预计算的向量
            float[] vector = new float[request.getQueryVector().size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = request.getQueryVector().get(i);
            }
            return vector;
        }

        // 服务端计算向量
        if (request.getKeyword() != null && !request.getKeyword().isEmpty()) {
            if (embeddingService == null) {
                throw new IllegalArgumentException(
                        "未提供 queryVector 且未配置 EmbeddingService，无法生成查询向量");
            }
            return embeddingService.embed(request.getKeyword());
        }

        throw new IllegalArgumentException("必须提供 keyword 或 queryVector");
    }

    /**
     * 创建 PIT（Point in Time）
     */
    private String createPit(String index) throws IOException {
        var pitResponse = elasticsearchClient.openPointInTime(p -> p
                .index(index)
                .keepAlive(t -> t.time(paginationProperties.getPitKeepAliveMinutes() + "m"))
        );
        log.debug("创建 PIT 成功: {}", pitResponse.id());
        return pitResponse.id();
    }

    /**
     * 清理 PIT
     */
    private void clearPit(String pitId) {
        if (pitId != null && !pitId.isEmpty()) {
            try {
                elasticsearchClient.closePointInTime(c -> c.id(pitId));
                log.debug("清理 PIT 成功: {}", pitId);
            } catch (Exception e) {
                log.warn("清理 PIT 失败: {}", pitId, e);
            }
        }
    }
}
