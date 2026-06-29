package com.example.es.service;

import co.elastic.clients.elasticsearch._types.GeoLocation;
import co.elastic.clients.elasticsearch._types.LatLonGeoLocation;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.es.dto.GeoSearchRequest;
import com.example.es.dto.PageResult;
import com.example.es.entity.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 地理距离搜索服务
 *
 * 使用 Elasticsearch 原生 geo_distance 查询，
 * 根据传入的经纬度坐标查询指定半径范围内的数据。
 */
@Slf4j
@Service
public class GeoSearchService {

    @Autowired
    private co.elastic.clients.elasticsearch.ElasticsearchClient elasticsearchClient;

    /**
     * 根据经纬度查询附近数据
     *
     * @param request 搜索请求（包含坐标、半径、过滤条件等）
     * @return 分页结果，每条数据包含 distance 字段（单位：米）
     */
    public PageResult<Document> searchNearby(GeoSearchRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            int from = (request.getPageNum() - 1) * request.getPageSize();

            // 构建搜索请求
            SearchResponse<Document> response = elasticsearchClient.search(s -> s
                    .index(request.getIndex())
                    .from(from)
                    .size(request.getPageSize())
                    .query(buildQuery(request))
                    .sort(buildSort(request))
                    .trackTotalHits(t -> t.enabled(true)),
                    Document.class
            );

            // 构建返回结果
            PageResult<Document> result = buildPageResult(response, request);
            result.setCostTime(System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            log.error("地理距离搜索失败, index={}, lat={}, lon={}, distance={}",
                    request.getIndex(), request.getLat(), request.getLon(), request.getDistance(), e);
            String errorMsg = "地理距离搜索失败: " + e.getMessage();
            if (e instanceof co.elastic.clients.elasticsearch._types.ElasticsearchException) {
                var esEx = (co.elastic.clients.elasticsearch._types.ElasticsearchException) e;
                errorMsg = String.format("地理距离搜索失败: status=%d, error=%s",
                        esEx.status(), esEx.error());
            }
            return PageResult.<Document>builder()
                    .errorMsg(errorMsg)
                    .costTime(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 构建查询条件
     * 核心：使用 geo_distance 过滤 + 可选的关键词/分类/状态过滤
     */
    private Query buildQuery(GeoSearchRequest request) {
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        // 核心：geo_distance 地理距离过滤
        boolQuery.filter(f -> f
                .geoDistance(g -> g
                        .field("location")
                        .location(GeoLocation.of(gl -> gl
                                .latlon(LatLonGeoLocation.of(ll -> ll
                                        .lat(request.getLat())
                                        .lon(request.getLon())
                                ))
                        ))
                        .distance(request.getDistance() + "m")
                )
        );

        // 关键词搜索（可选）
        if (request.getKeyword() != null && !request.getKeyword().isEmpty()) {
            boolQuery.must(m -> m
                    .multiMatch(mm -> mm
                            .query(request.getKeyword())
                            .fields("title^2", "content")
                            .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                    )
            );
        }

        // 分类过滤（可选）
        if (request.getCategory() != null && !request.getCategory().isEmpty()) {
            boolQuery.filter(f -> f
                    .term(t -> t
                            .field("category")
                            .value(request.getCategory())
                    )
            );
        }

        // 状态过滤（可选）
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
     * 构建排序
     * 默认按距离升序，可选按创建时间降序
     */
    private List<SortOptions> buildSort(GeoSearchRequest request) {
        List<SortOptions> sorts = new ArrayList<>();

        if (request.getSortOrder() == GeoSearchRequest.SortOrder.CREATE_TIME_DESC) {
            // 按创建时间降序
            sorts.add(SortOptions.of(s -> s
                    .field(f -> f
                            .field("createTime")
                            .order(SortOrder.Desc)
                    )
            ));
        } else {
            // 默认：按距离升序
            sorts.add(SortOptions.of(s -> s
                    .geoDistance(g -> g
                            .field("location")
                            .location(GeoLocation.of(gl -> gl
                                    .latlon(LatLonGeoLocation.of(ll -> ll
                                            .lat(request.getLat())
                                            .lon(request.getLon())
                                    ))
                            ))
                            .order(SortOrder.Asc)
                            .unit(co.elastic.clients.elasticsearch._types.DistanceUnit.Meters)
                    )
            ));
        }

        return sorts;
    }

    /**
     * 构建分页结果
     * 从 _geo_distance 排序的 sort 值中提取距离信息
     */
    private PageResult<Document> buildPageResult(SearchResponse<Document> response, GeoSearchRequest request) {
        List<Document> documents = response.hits().hits().stream()
                .map(hit -> {
                    Document doc = hit.source();
                    doc.setId(hit.id());

                    // 从排序值中提取距离（当使用 _geo_distance 排序时，sort值为距离，单位：米）
                    if (request.getSortOrder() == GeoSearchRequest.SortOrder.DISTANCE_ASC
                            && hit.sort() != null && !hit.sort().isEmpty()) {
                        Double distance = hit.sort().get(0).doubleValue();
                        doc.setDistance(distance);
                    }

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
