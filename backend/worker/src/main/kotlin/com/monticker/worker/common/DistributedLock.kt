package com.monticker.worker.common

/**
 * Redis SETNX 기반 분산 락 애노테이션.
 *
 * K8s 레플리카가 여러 개 떠 있을 때 @Scheduled 작업이 중복 실행되는 것을 방지한다.
 * 락 키: distributed-lock:{name}
 * 한 인스턴스만 TTL 내에 실행되며, 작업 완료 후 락을 해제한다.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DistributedLock(
    val name: String,
    val ttlSeconds: Long = 300,
)
