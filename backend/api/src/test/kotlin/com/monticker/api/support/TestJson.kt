package com.monticker.api.support

import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.json.JsonMapper

/**
 * 운영과 같은 Boot 매퍼(Jackson 3, ADR-104). **실제 application.yml**(`spring.jackson.use-jackson2-defaults` 등)로 구성한다.
 * 서비스가 Boot 매퍼를 주입받는다면 단위 테스트도 이걸 넘긴다 — `JsonMapper()`는 Kotlin 모듈도, Jackson 2 기본값도 없다.
 */
object TestJson {
    val boot: JsonMapper by lazy {
        var mapper: JsonMapper? = null
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
            .run { ctx -> mapper = ctx.getBean(JsonMapper::class.java) }
        mapper!!
    }
}
