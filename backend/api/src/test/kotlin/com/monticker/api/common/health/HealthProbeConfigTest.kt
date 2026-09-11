package com.monticker.api.common.health

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * resilience-plan §A3 / P0-6.
 * Spring 컨텍스트를 띄우지 않고 application.yml을 직접 읽는다 — 이 저장소의 통합 테스트는
 * 의도적으로 컨텍스트를 안 띄우므로(PostgresIntegrationTest 주석), 설정 회귀는 여기서 잡는다.
 */
class HealthProbeConfigTest {

    @Suppress("UNCHECKED_CAST")
    private fun healthConfig(): Map<String, Any> {
        val yaml = Yaml().load<Map<String, Any>>(javaClass.getResourceAsStream("/application.yml"))
        val management = yaml["management"] as Map<String, Any>
        val endpoint = management["endpoint"] as Map<String, Any>
        return endpoint["health"] as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun group(name: String): List<String> {
        val groups = healthConfig()["group"] as Map<String, Any>
        val g = groups[name] as Map<String, Any>
        return (g["include"] as String).split(",").map { it.trim() }
    }

    @Test
    fun `readiness는 DB 상태를 포함한다 — 죽은 pod에 트래픽이 가지 않도록`() {
        assertThat(group("readiness")).contains("readinessState", "db")
    }

    @Test
    fun `liveness는 DB를 포함하지 않는다 — DB 장애가 재시작 루프가 되면 안 된다`() {
        assertThat(group("liveness")).containsExactly("livenessState")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `probe 엔드포인트가 K8s 밖에서도 노출된다`() {
        val probes = healthConfig()["probes"] as Map<String, Any>
        assertThat(probes["enabled"]).isEqualTo(true)
    }
}
