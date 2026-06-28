package com.example.es.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * 分页查询结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /**
     * 数据列表
     */
    private List<T> data;

    /**
     * 总数
     */
    private Long total;

    /**
     * 当前页码
     */
    private Integer pageNum;

    /**
     * 每页大小
     */
    private Integer pageSize;

    /**
     * 是否有下一页
     */
    private Boolean hasNext;

    /**
     * Scroll ID（Scroll模式）
     */
    private String scrollId;

    /**
     * PIT ID（Search After模式）
     */
    private String pitId;

    /**
     * Search After值（用于下一页查询）
     */
    private Object[] searchAfter;

    /**
     * 查询耗时（毫秒）
     */
    private Long costTime;

    /**
     * 错误信息
     */
    private String errorMsg;
}
