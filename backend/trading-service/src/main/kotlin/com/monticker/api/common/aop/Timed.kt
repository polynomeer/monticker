package com.monticker.api.common.aop

/**
 * 메서드 실행 시간을 Micrometer Timer로 측정한다.
 *
 * @param value  메트릭 이름 (예: "matching.submit_order"). 생략 시 클래스.메서드명 자동 생성.
 * @param tags   "key=value" 형식의 추가 태그 (예: ["module=matching"])
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Timed(
    val value: String = "",
    val tags: Array<String> = [],
)
