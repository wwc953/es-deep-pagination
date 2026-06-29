package com.example.es.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ES文档实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    private String id;
    private String title;
    private String content;
    private String category;
    private String author;
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /**
     * 标题向量（用于语义搜索）
     * 不序列化到响应中，避免大量数据传输
     */
    @JsonIgnore
    private List<Float> titleVector;

    /**
     * 地理位置坐标（映射 ES geo_point）
     */
    private GeoPoint location;

    /**
     * 到查询中心点的距离（单位：米）
     * 由查询结果动态填充，不持久化到 ES
     */
    private Double distance;

    private Map<String, Object> metadata;

    /**
     * 地理坐标点
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeoPoint {
        private Double lat;
        private Double lon;
    }
}
