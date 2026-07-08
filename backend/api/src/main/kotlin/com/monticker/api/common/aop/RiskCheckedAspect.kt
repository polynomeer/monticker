package com.monticker.api.common.aop

import com.monticker.api.matching.application.RiskCheckerService
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * @RiskChecked 메서드 진입 전 RiskCheckerService.check()를 자동 수행한다.
 *
 * 파라미터 추출 규칙:
 *  - userId:        첫 번째 Long 파라미터
 *  - stockId:       파라미터명 "stockId" 또는 @RiskParam + Long
 *  - side:          파라미터명 "side" (String)
 *  - quantity:      파라미터명 "quantity" (Int)
 *  - estimatedPrice: 파라미터명 "estimatedPrice" 또는 "limitPrice" (BigDecimal)
 *
 * 추출할 수 없는 파라미터는 기본값으로 대체한다.
 */
@Aspect
@Component
class RiskCheckedAspect(private val riskChecker: RiskCheckerService) {

    @Around("@annotation(com.monticker.api.common.aop.RiskChecked)")
    fun checkRisk(pjp: ProceedingJoinPoint): Any? {
        val sig    = pjp.signature as MethodSignature
        val params = sig.parameterNames
        val args   = pjp.args

        val userId        = extractLong(params, args, "userId")        ?: return pjp.proceed()
        val stockId       = extractLong(params, args, "stockId")       ?: return pjp.proceed()
        val side          = extractString(params, args, "side")        ?: "BUY"
        val quantity      = extractInt(params, args, "quantity")       ?: 1
        val estimatedPrice = extractDecimal(params, args, "estimatedPrice", "limitPrice", "price")
            ?: BigDecimal.ZERO

        val result = riskChecker.check(userId, stockId, side, quantity, estimatedPrice)
        if (!result.approved) {
            throw RiskLimitException(result.blockedBy ?: "Unknown risk rule")
        }

        return pjp.proceed()
    }

    private fun extractLong(names: Array<String>, args: Array<Any?>, vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key ->
            names.indexOf(key).takeIf { it >= 0 }?.let { args[it] as? Long }
        }

    private fun extractString(names: Array<String>, args: Array<Any?>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            names.indexOf(key).takeIf { it >= 0 }?.let { args[it] as? String }
        }

    private fun extractInt(names: Array<String>, args: Array<Any?>, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            names.indexOf(key).takeIf { it >= 0 }?.let { args[it] as? Int }
        }

    private fun extractDecimal(names: Array<String>, args: Array<Any?>, vararg keys: String): BigDecimal? =
        keys.firstNotNullOfOrNull { key ->
            names.indexOf(key).takeIf { it >= 0 }?.let { args[it] as? BigDecimal }
        }
}

class RiskLimitException(rule: String) : RuntimeException("리스크 한도 초과: $rule")
