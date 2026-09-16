package com.monticker.worker.experiment

import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 실험 M-002 관측값 — `experiment` 프로파일에서만 노출된다. bench/experiments/m2-*.sh 가 읽는다. */
@RestController
@Profile("experiment")
@RequestMapping("/experiment/tick-order")
class TickOrderController(private val monitor: TickOrderMonitor) {
    @GetMapping fun snapshot(): Map<String, Any?> = monitor.snapshot()
    @PostMapping("/reset") fun reset(): Map<String, Any> { monitor.reset(); return mapOf("reset" to true) }
}
