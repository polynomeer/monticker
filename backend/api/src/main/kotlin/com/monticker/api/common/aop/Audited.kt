package com.monticker.api.common.aop

/**
 * 컨트롤러 메서드 호출을 감사 로그로 기록한다.
 * 요청자 IP, 메서드명, 인자 요약, 응답 상태, 실행 시간을 남긴다.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Audited(
    val action: String = "",   // 빈 문자열이면 메서드명 사용
)
