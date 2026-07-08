package com.monticker.api.common.aop

/**
 * 메서드 실행 전 RiskCheckerService.check()를 자동으로 수행한다.
 *
 * 적용 조건:
 *  - 메서드 첫 번째 파라미터: userId: Long
 *  - 메서드에 @RiskParam 어노테이션이 붙은 파라미터가 있어야 함
 *    (stockId, side, quantity, estimatedPrice 추출용)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RiskChecked

/**
 * @RiskChecked 메서드에서 리스크 체크에 사용할 파라미터를 표시한다.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class RiskParam
