package com.monticker.api.common.aop

/**
 * 메서드 레벨 Rate Limiting.
 * 기존 RateLimitFilter(IP 기반)와 달리 userId + 메서드 단위로 제한한다.
 *
 * @param limit    허용 횟수
 * @param windowSec 시간 창 (초)
 * @param keyPrefix Redis 키 접두사. 빈 문자열이면 클래스.메서드명 사용.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimited(
    val limit: Int = 10,
    val windowSec: Long = 60,
    val keyPrefix: String = "",
)
