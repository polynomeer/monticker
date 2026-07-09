package com.monticker.quant

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(
    scanBasePackages = [
        "com.monticker.api.quant",
        "com.monticker.api.analytics",
        "com.monticker.api.backtest",
        "com.monticker.api.common",
    ]
)
@EntityScan(
    basePackages = [
        "com.monticker.api.quant.domain",
        "com.monticker.api.analytics.domain",
        "com.monticker.api.backtest.domain",
    ]
)
@EnableJpaRepositories(
    basePackages = [
        "com.monticker.api.quant.infrastructure",
        "com.monticker.api.analytics.infrastructure",
        "com.monticker.api.backtest.infrastructure",
    ]
)
class QuantEngineApplication

fun main(args: Array<String>) {
    runApplication<QuantEngineApplication>(*args)
}
