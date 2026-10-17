package com.monticker.worker.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * B4-6 — worker 코드는 Jackson 3(`tools.jackson`)만 쓴다. Jackson 2 databind는 다른 라이브러리(anthropic-java 등)를 따라
 * 클래스패스에 남아 있어 IDE 자동 import로 `com.fasterxml.jackson.databind.ObjectMapper`가 다시 들어오기 쉽다 — 그러면
 * 트리 접근 의미(asText·asLong·map)와 기본 형식이 조용히 Jackson 2로 돌아간다. 어노테이션(`com.fasterxml.jackson.annotation`)은
 * Jackson 3도 같은 패키지를 쓰므로 허용한다.
 */
class NoJackson2InMainTest {
    @Test
    fun `main sources do not import jackson 2 databind or modules`() {
        val offenders = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> f.readLines().filter { it.startsWith("import com.fasterxml.jackson.") && !it.startsWith("import com.fasterxml.jackson.annotation.") }.map { "${f.name}: $it" } }
            .toList()
        assertThat(offenders).isEmpty()
    }
}
