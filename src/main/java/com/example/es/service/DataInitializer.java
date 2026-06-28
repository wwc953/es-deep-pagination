package com.example.es.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import com.example.es.config.DeepPaginationProperties;
import com.example.es.entity.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 测试数据初始化
 * 启动时自动创建索引并插入测试数据
 */
@Slf4j
@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private DeepPaginationProperties paginationProperties;

    private static final String INDEX_NAME = "documents";
    private static final int TOTAL_DOCS = 100000; // 生成100000条测试数据

    @Override
    public void run(String... args) {
        try {
            // 检查索引是否存在
            boolean exists = elasticsearchClient.indices().exists(e -> e.index(INDEX_NAME)).value();
            if (exists) {
                log.info("索引 {} 已存在，跳过初始化", INDEX_NAME);
                return;
            }

            // 创建索引
            log.info("创建索引: {}", INDEX_NAME);
            int vectorDims = paginationProperties.getVectorDimensions();
            elasticsearchClient.indices().create(c -> c
                            .index(INDEX_NAME)
                            .mappings(m -> m
                                            .properties("title", p -> p.text(t -> t))
                                            .properties("content", p -> p.text(t -> t))
                                            .properties("category", p -> p.keyword(k -> k))
                                            .properties("author", p -> p.keyword(k -> k))
                                            .properties("status", p -> p.integer(i -> i))
                                            .properties("createTime", p -> p.date(d -> d.format("strict_date_optional_time||epoch_millis||yyyy-MM-dd HH:mm:ss")))
                                            .properties("updateTime", p -> p.date(d -> d.format("strict_date_optional_time||epoch_millis||yyyy-MM-dd HH:mm:ss")))
                                            // 向量字段（用于混合搜索的 KNN 语义搜索）
                                            .properties("titleVector", p -> p.denseVector(d -> d
                                                    .dims(vectorDims)
                                                    .similarity(co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity.Cosine)
                                                    .index(true)
                                            ))
                            ).settings(IndexSettings.of(v -> v.maxResultWindow(100).numberOfReplicas("0")))
            );

            // 批量插入测试数据
            insertBulkData();

            log.info("测试数据初始化完成，共 {} 条", TOTAL_DOCS);
        } catch (Exception e) {
            log.error("测试数据初始化失败", e);
        }
    }

    private void insertBulkData() throws IOException {
        List<String> categories = List.of("tech", "news", "blog", "tutorial", "review");
        List<String> authors = List.of("张三", "李四", "王五", "赵六", "钱七");

        int batchSize = 500;
        int batches = (TOTAL_DOCS + batchSize - 1) / batchSize;

        for (int batch = 0; batch < batches; batch++) {
            List<BulkOperation> operations = new ArrayList<>();
            int start = batch * batchSize;
            int end = Math.min(start + batchSize, TOTAL_DOCS);

            int vectorDims = paginationProperties.getVectorDimensions();
            for (int i = start; i < end; i++) {
                Document doc = Document.builder()
                        .title("测试文档标题-" + (i + 1))
                        .content("这是第 " + (i + 1) + " 条测试文档的内容，包含关键词：Spring Boot, Elasticsearch, 深度分页, Search After, Scroll API")
                        .category(categories.get(i % categories.size()))
                        .author(authors.get(i % authors.size()))
                        .status(i % 3 == 0 ? 0 : 1)
                        .titleVector(generateRandomVector(vectorDims))
                        .createTime(LocalDateTime.now().minusDays(i))
                        .build();

                operations.add(BulkOperation.of(b -> b.index(idx -> idx.document(doc))));
            }

            BulkResponse response = elasticsearchClient.bulk(b -> b.index(INDEX_NAME).operations(operations));

            if (response.errors()) {
                log.error("批量插入第 {} 批数据出错", batch);
                response.items().stream()
                        .filter(item -> item.error() != null)
                        .forEach(item -> log.error("插入失败: {}", item.error().reason()));
            } else {
                log.info("成功插入第 {} 批数据 ({} - {})", batch + 1, start, end);
            }
        }
    }

    /**
     * 生成随机向量（用于测试数据）
     * 使用固定种子确保每次运行生成相同的向量
     */
    private List<Float> generateRandomVector(int dimensions) {
        Random random = new Random(42);
        return IntStream.range(0, dimensions)
                .mapToObj(i -> random.nextFloat() * 2 - 1) // 范围 [-1, 1]
                .collect(Collectors.toList());
    }
}
