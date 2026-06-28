package com.example.es.service;

import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.*;
import co.elastic.clients.elasticsearch._types.FieldValue;
import com.example.es.config.DeepPaginationProperties;
import com.example.es.dto.PageRequest;
import com.example.es.dto.PageResult;
import com.example.es.entity.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 深度分页查询服务
 *
 * 支持三种分页模式：
 * 1. FROM模式：传统from+size分页，适合前几页查询（性能随from增大而下降）
 * 2. SCROLL模式：适合全量数据导出，快照式查询
 * 3. SEARCH_AFTER + PIT模式：推荐用于深度分页，实时查询
 */
@Slf4j
@Service
public class DeepPaginationService {

    @Autowired
    private co.elastic.clients.elasticsearch.ElasticsearchClient elasticsearchClient;

    @Autowired
    private DeepPaginationProperties paginationProperties;

    /**
     * 执行深度分页查询
     */
    public PageResult<Document> search(PageRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            // 根据分页模式执行不同的查询策略
            PageResult<Document> result;
            switch (request.getMode()) {
                case FROM:
                    result = searchFrom(request);
                    break;
                case SCROLL:
                    result = searchScroll(request);
                    break;
                case SEARCH_AFTER:
                default:
                    result = searchAfterWithPit(request);
                    break;
            }

            result.setCostTime(System.currentTimeMillis() - startTime);
            return result;
        } catch (Exception e) {
            log.error("深度分页查询失败, mode={}, index={}, keyword={}",
                    request.getMode(), request.getIndex(), request.getKeyword(), e);
            // 提取 ES 详细错误信息
            String errorMsg = "查询失败";
            if (e instanceof co.elastic.clients.elasticsearch._types.ElasticsearchException) {
                var esEx = (co.elastic.clients.elasticsearch._types.ElasticsearchException) e;
                errorMsg = String.format("查询失败: status=%d, error=%s",
                        esEx.status(), esEx.error());
            } else {
                errorMsg = "查询失败: " + e.getMessage();
            }
            return PageResult.<Document>builder()
                    .errorMsg(errorMsg)
                    .costTime(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 传统FROM分页（适合浅分页，from < 10000）
     * 性能随from增大而下降，因为ES需要跳过from个文档
     */
    private PageResult<Document> searchFrom(PageRequest request) throws IOException {
        int from = (request.getPageNum() - 1) * request.getPageSize();

        // 检查from+size是否超过限制
        if (from + request.getPageSize() > paginationProperties.getMaxPageSize()) {
            throw new IllegalArgumentException(
                    String.format("from+size超过最大限制%d，请使用Search After模式", paginationProperties.getMaxPageSize()));
        }

        SearchResponse<Document> response = elasticsearchClient.search(s -> s
                .index(request.getIndex())
                .from(from)
                .size(request.getPageSize())
                .query(buildQuery(request))
                .sort(buildSort(request))
                .trackTotalHits(t -> t.enabled(true)),
                Document.class
        );

        return buildPageResult(response, request);
    }

    /**
     * SCROLL模式（适合全量导出）
     * 创建数据快照，遍历完成后需要清理scroll上下文
     */
    private PageResult<Document> searchScroll(PageRequest request) throws IOException {
        // 如果有scrollId，继续滚动
        if (request.getScrollId() != null && !request.getScrollId().isEmpty()) {
            return continueScroll(request);
        }

        // 初始化scroll查询
        SearchResponse<Document> response = elasticsearchClient.search(s -> s
                .index(request.getIndex())
                .size(request.getPageSize())
                .query(buildQuery(request))
                .sort(buildSort(request))
                .scroll(t -> t.time(paginationProperties.getScrollKeepAliveMinutes() + "m"))
                .trackTotalHits(t -> t.enabled(true)),
                Document.class
        );

        PageResult<Document> result = buildPageResult(response, request);
        result.setScrollId(response.scrollId());
        return result;
    }

    /**
     * 继续Scroll滚动
     */
    private PageResult<Document> continueScroll(PageRequest request) throws IOException {
        ScrollResponse<Document> response = elasticsearchClient.scroll(s -> s
                .scrollId(request.getScrollId())
                .scroll(t -> t.time(paginationProperties.getScrollKeepAliveMinutes() + "m")),
                Document.class
        );

        PageResult<Document> result = buildPageResult(response, request);
        result.setScrollId(response.scrollId());

        // 如果没有更多数据，清理scroll上下文
        if (result.getData() == null || result.getData().isEmpty() || !result.getHasNext()) {
            clearScroll(response.scrollId());
            result.setScrollId(null);
        }

        return result;
    }

    /**
     * 清理Scroll上下文
     */
    public void clearScroll(String scrollId) {
        if (scrollId != null && !scrollId.isEmpty()) {
            try {
                elasticsearchClient.clearScroll(c -> c.scrollId(scrollId));
                log.debug("清理scroll上下文成功: {}", scrollId);
            } catch (Exception e) {
                log.warn("清理scroll上下文失败: {}", scrollId, e);
            }
        }
    }

    /**
     * SEARCH_AFTER + PIT模式（推荐用于深度分页）
     * Point in Time保持数据快照，Search After基于排序值翻页
     */
    private PageResult<Document> searchAfterWithPit(PageRequest request) throws IOException {
        // 如果有searchAfter，继续查询下一页
        if (request.getSearchAfter() != null && request.getSearchAfter().length > 0) {
            return continueSearchAfter(request);
        }

        // 创建PIT（Point in Time）
        String pitId = createPit(request.getIndex());

        try {
            // 首次查询
            // 注意：使用PIT时不能指定index，PIT创建时已绑定索引
            SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                    .size(request.getPageSize())
                    .query(buildQuery(request))
                    .pit(p -> p
                            .id(pitId)
                            .keepAlive(t -> t.time(paginationProperties.getPitKeepAliveMinutes() + "m"))
                    )
                    .sort(buildSortWithId(request));

            SearchResponse<Document> response = elasticsearchClient.search(searchBuilder.build(), Document.class);

            PageResult<Document> result = buildPageResult(response, request);
            result.setPitId(pitId);
            return result;
        } catch (Exception e) {
            // 查询失败时清理PIT
            clearPit(pitId);
            throw e;
        }
    }

    /**
     * 继续Search After查询
     */
    private PageResult<Document> continueSearchAfter(PageRequest request) throws IOException {
        try {
            // 将Object[]转换为FieldValue[]（保留原始类型，避免toString导致类型失真）
            List<FieldValue> fieldValues = Arrays.stream(request.getSearchAfter())
                    .filter(Objects::nonNull)
                    .map(FieldValue::of)
                    .collect(Collectors.toList());

            // 注意：使用PIT时不能指定index，PIT创建时已绑定索引
            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .size(request.getPageSize())
                    .query(buildQuery(request))
                    .pit(p -> p
                            .id(request.getPitId())
                            .keepAlive(t -> t.time(paginationProperties.getPitKeepAliveMinutes() + "m"))
                    )
                    .sort(buildSortWithId(request))
                    .searchAfter(fieldValues)
            );

            SearchResponse<Document> response = elasticsearchClient.search(searchRequest, Document.class);

            PageResult<Document> result = buildPageResult(response, request);
            result.setPitId(request.getPitId());
            return result;
        } catch (Exception e) {
            throw new RuntimeException("继续Search After查询失败", e);
        }
    }

    /**
     * 创建PIT（Point in Time）
     */
    private String createPit(String index) throws IOException {
        OpenPointInTimeResponse pitResponse = elasticsearchClient.openPointInTime(p -> p
                .index(index)
                .keepAlive(t -> t.time(paginationProperties.getPitKeepAliveMinutes() + "m"))
        );
        log.debug("创建PIT成功: {}", pitResponse.id());
        return pitResponse.id();
    }

    /**
     * 清理PIT
     */
    public void clearPit(String pitId) {
        if (pitId != null && !pitId.isEmpty()) {
            try {
                elasticsearchClient.closePointInTime(c -> c.id(pitId));
                log.debug("清理PIT成功: {}", pitId);
            } catch (Exception e) {
                log.warn("清理PIT失败: {}", pitId, e);
            }
        }
    }

    /**
     * 构建查询条件
     */
    private Query buildQuery(PageRequest request) {
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        // 关键词搜索
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

        return Query.of(q -> q.bool(boolQuery.build()));
    }

    /**
     * 构建排序（不含_id，用于FROM和SCROLL模式）
     */
    private List<SortOptions> buildSort(PageRequest request) {
        String sortField = mapSortField(request.getSortField());
        SortOrder order = request.getSortOrder() == PageRequest.SortOrder.ASC
                ? SortOrder.Asc : SortOrder.Desc;

        List<SortOptions> sorts = new ArrayList<>();
        sorts.add(SortOptions.of(s -> s
                .field(f -> f
                        .field(sortField)
                        .order(order)
                )
        ));

        return sorts;
    }

    /**
     * 构建排序（含_id，用于Search After模式）
     * 需要添加_id作为 tiebreaker 确保排序唯一性
     */
    private List<SortOptions> buildSortWithId(PageRequest request) {
        String sortField = mapSortField(request.getSortField());
        SortOrder order = request.getSortOrder() == PageRequest.SortOrder.ASC
                ? SortOrder.Asc : SortOrder.Desc;

        List<SortOptions> sorts = new ArrayList<>();
        sorts.add(SortOptions.of(s -> s
                .field(f -> f
                        .field(sortField)
                        .order(order)
                )
        ));

        // 添加_doc作为 tiebreaker，确保排序唯一性
        // 注意：ES默认禁用_id字段的fielddata排序，使用_doc替代
        sorts.add(SortOptions.of(s -> s
                .field(f -> f
                        .field("_doc")
                        .order(SortOrder.Asc)
                )
        ));

        return sorts;
    }

    /**
     * 映射排序字段
     */
    private String mapSortField(String field) {
        if (field == null || field.isEmpty()) {
            return "createTime";
        }
        // ES索引mapping中的字段名为驼峰命名(createTime/updateTime)，直接透传即可
        switch (field) {
            case "createTime":
                return "createTime";
            case "updateTime":
                return "updateTime";
            default:
                return field;
        }
    }

    /**
     * 构建分页结果
     */
    private PageResult<Document> buildPageResult(SearchResponse<Document> response, PageRequest request) {
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

        // 获取最后一个文档的sort值，用于search_after
        // 注意：FieldValue无法被JSON序列化，需要转为原始类型
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
                .pageNum(request.getPageNum())
                .pageSize(request.getPageSize())
                .hasNext(hasNext)
                .searchAfter(searchAfter)
                .build();
    }

    /**
     * 构建分页结果（Scroll响应）
     */
    private PageResult<Document> buildPageResult(ScrollResponse<Document> response, PageRequest request) {
        List<Document> documents = response.hits().hits().stream()
                .map(hit -> {
                    Document doc = hit.source();
                    doc.setId(hit.id());
                    return doc;
                })
                .collect(Collectors.toList());

        long total = response.hits().total() != null ? response.hits().total().value() : 0;

        boolean hasNext = documents.size() >= request.getPageSize();

        return PageResult.<Document>builder()
                .data(documents)
                .total(total)
                .pageNum(request.getPageNum())
                .pageSize(request.getPageSize())
                .hasNext(hasNext)
                .build();
    }
}
