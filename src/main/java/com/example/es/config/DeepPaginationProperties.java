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
}
