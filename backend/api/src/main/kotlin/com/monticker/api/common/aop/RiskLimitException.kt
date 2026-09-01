package com.monticker.api.common.aop

class RiskLimitException(rule: String) : RuntimeException("리스크 한도 초과: $rule")
