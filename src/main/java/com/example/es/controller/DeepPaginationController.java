package com.example.es.controller;

import com.example.es.dto.HybridSearchRequest;
import com.example.es.dto.PageRequest;
import com.example.es.dto.PageResult;
import com.example.es.entity.Document;
import com.example.es.service.DeepPaginationService;
import com.example.es.service.EmbeddingService;
import com.example.es.service.HybridSearchService;
import com.example.es.service.HybridSearchServiceSelf;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 深度分页查询接口
 *
 * 支持三种分页模式：
 * 1. FROM模式：传统from+size分页，适合浅分页（from < 10000）
 * 2. SCROLL模式：适合全量数据导出
 * 3. SEARCH_AFTER模式：推荐用于深度分页，实时查询
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pagination")
public class DeepPaginationController {

    @Autowired
    private DeepPaginationService deepPaginationService;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private HybridSearchServiceSelf hybridSearchServiceSelf;

    @Autowired(required = false)
    private EmbeddingService embeddingService;

    /**
     * 深度分页查询
     *
     * 示例请求：
     * POST /api/v1/pagination/search
     * {
     *   "index": "documents",
     *   "keyword": "测试",
     *   "category": "tech",
     *   "status": 1,
     *   "pageSize": 20,
     *   "sortField": "createTime",
     *   "sortOrder": "DESC",
     *   "mode": "SEARCH_AFTER"
     * }
     */
    @PostMapping("/search")
    public ResponseEntity<PageResult<Document>> search(@Valid @RequestBody PageRequest request) {
        log.info("深度分页查询请求: {}", request);
        PageResult<Document> result = deepPaginationService.search(request);
        return ResponseEntity.ok(result);
    }

    /**
     * 第一页查询（简化版）
     *
     * GET /api/v1/pagination/search-first?index=documents&keyword=测试&pageSize=20
     */
    @GetMapping("/search-first")
    public ResponseEntity<PageResult<Document>> searchFirst(
            @RequestParam String index,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(defaultValue = "createTime") String sortField,
            @RequestParam(defaultValue = "DESC") PageRequest.SortOrder sortOrder,
            @RequestParam(defaultValue = "SEARCH_AFTER") PageRequest.PaginationMode mode) {

        PageRequest request = new PageRequest();
        request.setIndex(index);
        request.setKeyword(keyword);
        request.setCategory(category);
        request.setStatus(status);
        request.setPageSize(pageSize);
        request.setSortField(sortField);
        request.setSortOrder(sortOrder);
        request.setMode(mode);

        return search(request);
    }

    /**
     * 下一页查询（Search After模式）
     *
     * POST /api/v1/pagination/search-next
     * {
     *   "index": "documents",
     *   "pitId": "xxx",
     *   "searchAfter": [...],
     *   "pageSize": 20,
     *   "mode": "SEARCH_AFTER"
     * }
     */
    @PostMapping("/search-next")
    public ResponseEntity<PageResult<Document>> searchNext(@Valid @RequestBody PageRequest request) {
        if (request.getPitId() == null && request.getSearchAfter() == null) {
            return ResponseEntity.badRequest().body(
                    PageResult.<Document>builder()
                            .errorMsg("pitId或searchAfter不能为空")
                            .build()
            );
        }
        PageResult<Document> result = deepPaginationService.search(request);
        return ResponseEntity.ok(result);
    }

    /**
     * 继续Scroll滚动
     *
     * POST /api/v1/pagination/scroll-next
     * {
     *   "scrollId": "xxx",
     *   "index": "documents",
     *   "pageSize": 20,
     *   "mode": "SCROLL"
     * }
     */
    @PostMapping("/scroll-next")
    public ResponseEntity<PageResult<Document>> scrollNext(@Valid @RequestBody PageRequest request) {
        if (request.getScrollId() == null || request.getScrollId().isEmpty()) {
            return ResponseEntity.badRequest().body(
                    PageResult.<Document>builder()
                            .errorMsg("scrollId不能为空")
                            .build()
            );
        }
        PageResult<Document> result = deepPaginationService.search(request);
        return ResponseEntity.ok(result);
    }

    /**
     * 清理Scroll上下文
     *
     * DELETE /api/v1/pagination/scroll/xxx
     */
    @DeleteMapping("/scroll/{scrollId}")
    public ResponseEntity<Void> clearScroll(@PathVariable String scrollId) {
        deepPaginationService.clearScroll(scrollId);
        return ResponseEntity.ok().build();
    }

    /**
     * 清理PIT上下文
     *
     * DELETE /api/v1/pagination/pit/xxx
     */
    @DeleteMapping("/pit/{pitId}")
    public ResponseEntity<Void> clearPit(@PathVariable String pitId) {
        deepPaginationService.clearPit(pitId);
        return ResponseEntity.ok().build();
    }

    /**
     * 混合搜索（BM25 + KNN + RRF）
     *
     * 结合关键词搜索与向量语义搜索，使用 RRF（Reciprocal Rank Fusion）融合排序
     *  ES 8.14 之前，RRF 曾在 Basic 版本中可用（后来被列入付费功能）
     *
     * 示例请求：
     * POST /api/v1/pagination/hybrid-search
     * {
     *   "index": "documents",
     *   "keyword": "Spring Boot",
     *   "queryVector": [0.1, 0.2, ...],  // 可选，不提供则使用 EmbeddingService 计算
     *   "category": "tech",
     *   "status": 1,
     *   "pageSize": 20
     * }
     */
    @PostMapping("/hybrid-search")
    public ResponseEntity<PageResult<Document>> hybridSearch(@Valid @RequestBody HybridSearchRequest request) {
        log.info("混合搜索请求: index={}, keyword={}, hasVector={}",
                request.getIndex(), request.getKeyword(), request.getQueryVector() != null);

        // 若未提供 queryVector，使用 EmbeddingService 服务端计算
        if (request.getQueryVector() == null && request.getKeyword() != null && !request.getKeyword().isEmpty()) {
            if (embeddingService == null) {
                return ResponseEntity.badRequest().body(
                        PageResult.<Document>builder()
                                .errorMsg("未提供queryVector且未配置EmbeddingService，请提供queryVector或配置EmbeddingService实现")
                                .build()
                );
            }
            float[] vector = embeddingService.embed(request.getKeyword());
            List<Float> vectorList = new ArrayList<>();
            for (float f : vector) {
                vectorList.add(f);
            }
            request.setQueryVector(vectorList);
        }

        PageResult<Document> result = hybridSearchService.hybridSearch(request);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/hybrid-search-self")
    public ResponseEntity<PageResult<Document>> hybridSearchSelf(@Valid @RequestBody HybridSearchRequest request) {
        log.info("混合搜索请求: index={}, keyword={}, hasVector={}",
                request.getIndex(), request.getKeyword(), request.getQueryVector() != null);

        // 若未提供 queryVector，使用 EmbeddingService 服务端计算
        if (request.getQueryVector() == null && request.getKeyword() != null && !request.getKeyword().isEmpty()) {
            if (embeddingService == null) {
                return ResponseEntity.badRequest().body(
                        PageResult.<Document>builder()
                                .errorMsg("未提供queryVector且未配置EmbeddingService，请提供queryVector或配置EmbeddingService实现")
                                .build()
                );
            }
            float[] vector = embeddingService.embed(request.getKeyword());
            List<Float> vectorList = new ArrayList<>();
            for (float f : vector) {
                vectorList.add(f);
            }
            request.setQueryVector(vectorList);
        }

        PageResult<Document> result = hybridSearchServiceSelf.hybridSearch(request);
        return ResponseEntity.ok(result);
    }

    /**
     * 批量清理Scroll上下文
     */
    @DeleteMapping("/scroll")
    public ResponseEntity<Void> clearAllScroll() {
        // 注意：ES 9.x 中没有 clearScroll all API，需要逐个清理
        log.warn("请通过具体scrollId清理，ES暂不支持批量清理所有scroll");
        return ResponseEntity.ok().build();
    }
}
