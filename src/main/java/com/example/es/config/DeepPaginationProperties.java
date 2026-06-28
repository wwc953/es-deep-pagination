package com.example.es.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "deep-pagination")
public class DeepPaginationProperties {

    /**
     * 默认最大分页大小
     */
    private int maxPageSize = 10000;

    /**
     * Scroll上下文保持时间（分钟）*/
    private int scrollKeepAliveMinutes = 5;

    /**
     * Search After PIT保持时间（分钟）*/
    private int pitKeepAliveMinutes = 2;

    /**
     * 是否启用Search After方式（推荐）*/
    private boolean searchAfterEnabled = true;

    /**
     * 默认每页大小
     */
    private int defaultPageSize = 20;

    /**
     * 向量维度（默认768，适配BERT-base等模型）
     */
    private int vectorDimensions = 768;

    /**
     * KNN搜索的k值（返回top-k个最近邻）
     */
    private int knnK = 10;

    /**
     * KNN候选数量（越大越精确，性能越低）
     */
    private int knnNumCandidates = 100;

    /**
     * RRF窗口大小
     */
    private int rrfWindowSize = 50;

    /**
     * RRF排名常数
     */
    private int rrfRankConstant = 60;
}
