package com.monticker.api.common.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter

/**
 * ADR-043 라이브 검증에서 발견: `columnDefinition = "jsonb"`만 있는 String 필드는 Hibernate 6가 varchar로
 * 바인딩해 Postgres가 INSERT를 거부한다("column is of type jsonb but expression is of type character varying"),
 * null이어도. 원장이 그렇게 몇 달을 비어 있었고, 같은 결함이 RebalanceTarget에서 한 번 고쳐진 뒤에도 나머지
 * 엔티티는 점검되지 않았다. 이 테스트는 모듈의 모든 @Entity를 훑어 그런 필드가 다시 생기지 못하게 한다.
 * mock 기반 테스트는 이 결함을 절대 못 본다 — 그래서 매핑 자체를 검사한다.
 */
class JsonbColumnMappingTest {

    @Test
    fun `every jsonb column on a String field declares JdbcTypeCode JSON`() {
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply { addIncludeFilter(AnnotationTypeFilter(Entity::class.java)) }
        val entities = scanner.findCandidateComponents("com.monticker").map { Class.forName(it.beanClassName) }
        assertThat(entities).isNotEmpty

        val offenders = entities.flatMap { cls ->
            cls.declaredFields
                .filter { f -> f.getAnnotation(Column::class.java)?.columnDefinition.equals("jsonb", ignoreCase = true) }
                .filter { f -> f.getAnnotation(JdbcTypeCode::class.java)?.value != SqlTypes.JSON }
                .map { f -> "${cls.simpleName}.${f.name}" }
        }

        assertThat(offenders)
            .withFailMessage("jsonb 컬럼에 @JdbcTypeCode(SqlTypes.JSON)이 없다 — INSERT가 Postgres에서 거부된다: %s", offenders)
            .isEmpty()
    }
}
