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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 混合搜索服务
 * 结合 BM25 关键词搜索 + KNN 向量搜索，使用 RRF（Reciprocal Rank Fusion）融合排序
 *
 * 特性：
 * - 支持语义搜索（KNN）与关键词搜索（BM25）的混合
 * - 使用 RRF 算法融合两种搜索结果的排名
 * - 支持 Search After + PIT 深度分页
 * - 支持分类、状态等过滤条件
 * - 可选服务端 EmbeddingService 自动计算查询向量
 */
@Slf4j
@Service
public class HybridSearchService {

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
     * 首次混合搜索
     */
    private PageResult<Document> startHybridSearch(HybridSearchRequest request, float[] queryVector) throws IOException {
        long startTime = System.currentTimeMillis();

        // 创建 PIT（Point in Time）
        String pitId = createPit(request.getIndex());

        try {
            // 构建搜索请求
            SearchRequest searchRequest = buildHybridSearchRequest(request, queryVector, pitId, null);

            log.debug("首次混合搜索: index={}, keyword={}, pitId={}", request.getIndex(), request.getKeyword(), pitId);
            SearchResponse<Document> response = elasticsearchClient.search(searchRequest, Document.class);

            PageResult<Document> result = buildHybridPageResult(response, request);
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

        // 构建搜索请求
        SearchRequest searchRequest = buildHybridSearchRequest(request, queryVector, request.getPitId(), fieldValues);

        log.debug("继续混合搜索: index={}, keyword={}, pitId={}", request.getIndex(), request.getKeyword(), request.getPitId());
        SearchResponse<Document> response = elasticsearchClient.search(searchRequest, Document.class);

        PageResult<Document> result = buildHybridPageResult(response, request);
        result.setPitId(request.getPitId());
        result.setCostTime(System.currentTimeMillis() - startTime);
        return result;
    }

    /**
     * 构建混合搜索请求（BM25 + KNN + RRF）
     */
    private SearchRequest buildHybridSearchRequest(HybridSearchRequest request, float[] queryVector,
                                                    String pitId,
                                                    List<co.elastic.clients.elasticsearch._types.FieldValue> searchAfter) {

        // 转换 float[] → List<Float>（ES Java Client 需要的类型）
        List<Float> vectorList = new ArrayList<>();
        for (float f : queryVector) {
            vectorList.add(f);
        }

        // 构建 Bool 查询
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

        // 构建排序
        List<co.elastic.clients.elasticsearch._types.SortOptions> sorts = new ArrayList<>();
        SortOrder order = request.getSortOrder() == HybridSearchRequest.SortOrder.ASC
                ? SortOrder.Asc : SortOrder.Desc;
        sorts.add(co.elastic.clients.elasticsearch._types.SortOptions.of(s -> s
                .field(f -> f
                        .field(request.getSortField())
                        .order(order)
                )
        ));
        // 添加 _doc 作为 tiebreaker
        sorts.add(co.elastic.clients.elasticsearch._types.SortOptions.of(s -> s
                .field(f -> f
                        .field("_doc")
                        .order(SortOrder.Asc)
                )
        ));

        // 构建 SearchRequest
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .size(request.getPageSize())
                // KNN 向量搜索
                .knn(k -> k
                        .field("titleVector")
                        .queryVector(vectorList)
                        .k(paginationProperties.getKnnK())
                        .numCandidates(paginationProperties.getKnnNumCandidates())
                )
                // BM25 关键词搜索
                .query(Query.of(q -> q.bool(boolQuery.build())))
                // RRF 融合排序
                .rank(r -> r
                        .rrf(rrf -> rrf
                                .rankWindowSize((long) paginationProperties.getRrfWindowSize())
                                .rankConstant((long) paginationProperties.getRrfRankConstant())
                        )
                )
                // PIT
                .pit(p -> p
                        .id(pitId)
                        .keepAlive(t -> t.time(paginationProperties.getPitKeepAliveMinutes() + "m"))
                )
                // 排序
                .sort(sorts)
                // 排除 titleVector 字段（减少数据传输）
                .source(src -> src
                        .filter(f -> f.excludes("titleVector"))
                );

        // 翻页参数
        if (searchAfter != null && !searchAfter.isEmpty()) {
            builder.searchAfter(searchAfter);
        }

        return builder.build();
    }

    /**
     * 构建分页结果
     */
    private PageResult<Document> buildHybridPageResult(SearchResponse<Document> response, HybridSearchRequest request) {
        List<Document> documents = response.hits().hits().stream()
                .map(hit -> {
                    Document doc = hit.source();
                    doc.setId(hit.id());
                    return doc;
                })
                .collect(Collectors.toList());

        long total = response.hits().total() != null ? response.hits().total().value() : 0;

        // 判断是否有下一页
        boolean hasNext = documents.size() >= request.getPageSize();

        // 获取最后一个文档的 sort 值，用于 search_after
        Object[] searchAfter = null;
        if (hasNext && !response.hits().hits().isEmpty()) {
            Hit<Document> lastHit = response.hits().hits().get(response.hits().hits().size() - 1);
            searchAfter = lastHit.sort().stream()
                    .map(fv -> {
                        if (fv.isString()) {
                            return fv.stringValue();
                        } else if (fv.isLong()) {
                            return fv.longValue();
                        } else if (fv.isDouble()) {
                            return fv.doubleValue();
                        } else if (fv.isBoolean()) {
                            return fv.booleanValue();
                        } else {
                            return fv.toString();
                        }
                    })
                    .toArray();
        }

        return PageResult.<Document>builder()
                .data(documents)
                .total(total)
                .pageSize(request.getPageSize())
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
