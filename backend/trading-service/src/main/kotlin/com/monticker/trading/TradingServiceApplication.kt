package com.monticker.trading

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(
    scanBasePackages = [
        "com.monticker.api.paper",
        "com.monticker.api.matching",
        "com.monticker.api.wallet",
        "com.monticker.api.batch.score",
        "com.monticker.api.common",
        "com.monticker.trading",
    ]
)
@EntityScan(
    basePackages = [
        "com.monticker.api.paper.domain",
        "com.monticker.api.matching.domain",
        "com.monticker.api.wallet.domain",
    ]
)
@EnableJpaRepositories(
    basePackages = [
        "com.monticker.api.paper.infrastructure",
        "com.monticker.api.matching.infrastructure",
        "com.monticker.api.wallet.infrastructure",
        "com.monticker.api.batch.score",
        "com.monticker.trading",
    ]
)
class TradingServiceApplication

fun main(args: Array<String>) {
    runApplication<TradingServiceApplication>(*args)
}
