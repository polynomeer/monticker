package com.monticker.quant

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(
    scanBasePackages = [
        "com.monticker.api.quant",
        "com.monticker.api.analytics",
        "com.monticker.api.backtest",
        "com.monticker.api.common",
    ]
)
class QuantEngineApplication

fun main(args: Array<String>) {
    runApplication<QuantEngineApplication>(*args)
}
