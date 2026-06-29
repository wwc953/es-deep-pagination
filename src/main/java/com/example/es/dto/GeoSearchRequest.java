package com.example.es.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 地理距离搜索请求
 * 根据传入的经纬度坐标，查询指定半径范围内的数据
 */
@Data
public class GeoSearchRequest {

    /**
     * 索引名称
     */
    @NotBlank(message = "索引名称不能为空")
    private String index;

    /**
     * 中心点纬度（-90 ~ 90）
     */
    @NotNull(message = "纬度不能为空")
    @Min(value = -90, message = "纬度不能小于-90")
    @Max(value = 90, message = "纬度不能大于90")
    private Double lat;

    /**
     * 中心点经度（-180 ~ 180）
     */
    @NotNull(message = "经度不能为空")
    @Min(value = -180, message = "经度不能小于-180")
    @Max(value = 180, message = "经度不能大于180")
    private Double lon;

    /**
     * 搜索半径（米），默认500米
     */
    @Min(value = 1, message = "搜索半径必须大于0")
    private Double distance = 500.0;

    /**
     * 关键词过滤（可选）
     */
    private String keyword;

    /**
     * 分类过滤（可选）
     */
    private String category;

    /**
     * 状态过滤（可选）
     */
    private Integer status;

    /**
     * 每页大小，默认20
     */
    @Min(value = 1, message = "每页大小必须大于0")
    private Integer pageSize = 20;

    /**
     * 页码，默认1
     */
    @Min(value = 1, message = "页码必须大于0")
    private Integer pageNum = 1;

    /**
     * 排序方式：DISTANCE_ASC（默认，按距离升序）/ CREATE_TIME_DESC（按创建时间降序）
     */
    private SortOrder sortOrder = SortOrder.DISTANCE_ASC;

    public enum SortOrder {
        /**
         * 按距离升序（最近的排在前面）
         */
        DISTANCE_ASC,
        /**
         * 按创建时间降序（最新的排在前面）
         */
        CREATE_TIME_DESC
    }
}
