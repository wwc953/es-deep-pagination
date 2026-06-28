package com.example.es.controller;

import com.example.es.dto.PageRequest;
import com.example.es.dto.PageResult;
import com.example.es.entity.Document;
import com.example.es.service.DeepPaginationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
     * 批量清理Scroll上下文
     */
    @DeleteMapping("/scroll")
    public ResponseEntity<Void> clearAllScroll() {
        // 注意：ES 9.x 中没有 clearScroll all API，需要逐个清理
        log.warn("请通过具体scrollId清理，ES暂不支持批量清理所有scroll");
        return ResponseEntity.ok().build();
    }
}
