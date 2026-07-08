package com.monticker.api.batch

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableBatchProcessing
@EnableScheduling
class BatchConfig
