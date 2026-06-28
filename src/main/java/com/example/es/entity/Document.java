package com.example.es.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

    private Map<String, Object> metadata;
}
