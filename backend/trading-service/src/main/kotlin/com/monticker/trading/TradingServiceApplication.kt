package com.monticker.trading

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

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
class TradingServiceApplication

fun main(args: Array<String>) {
    runApplication<TradingServiceApplication>(*args)
}
