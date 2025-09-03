package com.polynomeer.app.batch.collector.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {
    @Bean
    public NewTopic quoteRequest() {
        return TopicBuilder.name("quote.request").partitions(12).replicas(1).build();
    }

    @Bean
    public NewTopic quoteNormalized() {
        return TopicBuilder.name("quote.normalized").partitions(12).replicas(1).build();
    }
}
