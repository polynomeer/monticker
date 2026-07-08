package com.monticker.worker.health

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Kafka 브로커 연결 상태를 `/actuator/health` 에 노출한다.
 *
 * ingestion.source=kafka 일 때만 활성화 (내부 Mock 경로에서는 Kafka 불필요).
 * AdminClient로 클러스터 노드 목록을 조회해 응답 시간과 노드 수를 기록한다.
 * 3초 이내에 응답하지 않으면 DOWN으로 간주한다.
 */
@Component("kafka")
@ConditionalOnProperty(name = ["ingestion.source"], havingValue = "kafka")
class KafkaHealthIndicator(
    @Value("\${spring.kafka.bootstrap-servers:localhost:9092}") private val bootstrapServers: String,
) : HealthIndicator {

    override fun health(): Health {
        return try {
            val config = mapOf(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG        to bootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG       to "3000",
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG   to "3000",
            )
            val start = System.currentTimeMillis()
            AdminClient.create(config).use { admin ->
                val nodes = admin.describeCluster()
                    .nodes()
                    .get(3, TimeUnit.SECONDS)
                val latencyMs = System.currentTimeMillis() - start

                Health.up()
                    .withDetail("brokers", nodes.map { "${it.host()}:${it.port()}" })
                    .withDetail("nodeCount", nodes.size)
                    .withDetail("latencyMs", latencyMs)
                    .build()
            }
        } catch (e: Exception) {
            Health.down()
                .withDetail("bootstrapServers", bootstrapServers)
                .withDetail("error", e.message)
                .build()
        }
    }
}
