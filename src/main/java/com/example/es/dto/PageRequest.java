package com.example.es.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 深度分页查询请求
 * 支持两种模式：
 * 1. Search After模式：使用pitId和searchAfter进行翻页
 * 2. Scroll模式：使用scrollId进行翻页
 */
@Data
public class PageRequest {

    /**
     * 索引名称
     */
    @NotBlank(message = "索引名称不能为空")
    private String index;

    /**
     * 查询关键词
     */
    private String keyword;

    /**
     * 分类过滤
     */
    private String category;

    /**
     * 状态过滤
     */
    private Integer status;

    /**
     * 每页大小
     */
    @Min(value = 1, message = "每页大小必须大于0")
    private Integer pageSize = 20;

    /**
     * 当前页码（仅用于传统from分页）
     */
    private Integer pageNum = 1;

    /**
     * Scroll ID（Scroll模式）
     */
    private String scrollId;

    /**
     * PIT ID（Search After模式）
     */
    private String pitId;

    /**
     * Search After值（Search After模式）
     */
    private Object[] searchAfter;

    /**
     * 排序字段
     */
    private String sortField = "createTime";

    /**
     * 排序方向
     */
    private SortOrder sortOrder = SortOrder.DESC;

    /**
     * 查询模式
     */
    private PaginationMode mode = PaginationMode.SEARCH_AFTER;

    public enum SortOrder {
        ASC, DESC
    }

    public enum PaginationMode {
        /**
         * 传统from分页（性能差，适合浅分页）
         */
        FROM,
        /**
         * Scroll模式（适合全量导出）
         */
        SCROLL,
        /**
         * Search After模式（推荐，适合深度分页）
         */
        SEARCH_AFTER
    }
}
