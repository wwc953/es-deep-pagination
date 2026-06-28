package com.example.es.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * 混合搜索请求
 * 结合 BM25 关键词搜索 + KNN 向量搜索，使用 RRF 融合排序
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HybridSearchRequest {

    /**
     * 索引名称
     */
    @NotBlank(message = "索引名称不能为空")
    private String index;

    /**
     * 查询关键词（BM25）
     */
    private String keyword;

    /**
     * 预计算的查询向量（可选，未提供时使用 EmbeddingService 计算）
     */
    private List<Float> queryVector;

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
     * PIT ID（Search After 模式）
     */
    private String pitId;

    /**
     * Search After 值（翻页用）
     */
    private Object[] searchAfter;

    /**
     * 排序字段（默认按 RRF 分数排序）
     */
    private String sortField = "_score";

    /**
     * 排序方向
     */
    private SortOrder sortOrder = SortOrder.DESC;

    public enum SortOrder {
        ASC, DESC
    }
}
