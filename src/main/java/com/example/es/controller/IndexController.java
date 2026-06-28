package com.example.es.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 索引管理接口（用于测试）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/index")
public class IndexController {

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    /**
     * 创建测试索引
     */
    @PostMapping("/create/{indexName}")
    public ResponseEntity<Map<String, Object>> createIndex(@PathVariable String indexName) throws IOException {
        Map<String, Object> result = new HashMap<>();

        // 检查索引是否存在
        boolean exists = elasticsearchClient.indices().exists(e -> e.index(indexName)).value();
        if (exists) {
            result.put("message", "索引已存在");
            return ResponseEntity.ok(result);
        }

        // 创建索引
        CreateIndexResponse response = elasticsearchClient.indices().create(c -> c
                        .index(indexName)
                        .mappings(m -> m
//                        .properties("title", p -> p.text(t -> t.analyzer("ik_max_word")))
//                        .properties("content", p -> p.text(t -> t.analyzer("ik_max_word")))
                                        .properties("title", p -> p.text(t -> t))
                                        .properties("content", p -> p.text(t -> t))
                                        .properties("category", p -> p.keyword(k -> k))
                                        .properties("author", p -> p.keyword(k -> k))
                                        .properties("status", p -> p.integer(i -> i))
                                        .properties("createTime", p -> p.date(d -> d.format("strict_date_optional_time||epoch_millis||yyyy-MM-dd HH:mm:ss")))
                                        .properties("updateTime", p -> p.date(d -> d.format("strict_date_optional_time||epoch_millis||yyyy-MM-dd HH:mm:ss")))
                        )
        );

        result.put("acknowledged", response.acknowledged());
        result.put("index", response.index());
        return ResponseEntity.ok(result);
    }

    /**
     * 删除索引
     */
    @DeleteMapping("/{indexName}")
    public ResponseEntity<Map<String, Object>> deleteIndex(@PathVariable String indexName) throws IOException {
        Map<String, Object> result = new HashMap<>();
        elasticsearchClient.indices().delete(d -> d.index(indexName));
        result.put("message", "索引删除成功");
        return ResponseEntity.ok(result);
    }

    /**
     * 检查索引是否存在
     */
    @GetMapping("/exists/{indexName}")
    public ResponseEntity<Map<String, Object>> exists(@PathVariable String indexName) throws IOException {
        Map<String, Object> result = new HashMap<>();
        boolean exists = elasticsearchClient.indices().exists(e -> e.index(indexName)).value();
        result.put("exists", exists);
        return ResponseEntity.ok(result);
    }
}
