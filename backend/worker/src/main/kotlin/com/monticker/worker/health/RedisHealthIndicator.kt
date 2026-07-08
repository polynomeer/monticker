package com.monticker.worker.health

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.stereotype.Component

/**
 * Worker Redis 연결 상태와 latency를 `/actuator/health` 에 노출한다.
 * Worker는 시세 틱을 Redis에 직접 쓰므로 Redis 상태가 파이프라인 가용성과 직결된다.
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
            conn.close()

            if (pong == "PONG") {
                Health.up().withDetail("latencyMs", latencyMs).build()
            } else {
                Health.down().withDetail("ping", pong).build()
            }
        } catch (e: Exception) {
            Health.down(e).build()
        }
    }
}
