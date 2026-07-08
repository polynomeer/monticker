package com.monticker.api.common.health

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.stereotype.Component

/**
 * Redis 연결 상태와 응답 시간을 `/actuator/health` 에 노출한다.
 * Spring Boot 기본 RedisHealthIndicator는 단순 PING만 체크하므로,
 * 여기서는 latency(ms)와 used_memory도 함께 기록해 Grafana에서 트렌드를 볼 수 있게 한다.
 */
@Component("redis")
class RedisHealthIndicator(
    private val connectionFactory: RedisConnectionFactory,
) : HealthIndicator {

    override fun health(): Health {
        return try {
            val start = System.currentTimeMillis()
            val conn  = connectionFactory.connection
            val pong  = conn.ping()
            val latencyMs = System.currentTimeMillis() - start
            val info = conn.serverCommands().info("memory")
            conn.close()

            val usedMemory = info?.getProperty("used_memory_human")

            if (pong == "PONG") {
                Health.up()
                    .withDetail("latencyMs", latencyMs)
                    .withDetail("usedMemory", usedMemory ?: "unknown")
                    .build()
            } else {
                Health.down().withDetail("ping", pong).build()
            }
        } catch (e: Exception) {
            Health.down(e).build()
        }
    }
}
