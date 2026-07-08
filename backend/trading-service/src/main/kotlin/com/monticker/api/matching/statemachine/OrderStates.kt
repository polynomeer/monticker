package com.monticker.api.matching.statemachine

enum class OrderStates {
    PENDING,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
}

enum class OrderEvents {
    SUBMIT,          // PENDING 진입 트리거
    PARTIAL_FILL,    // 일부 체결
    COMPLETE_FILL,   // 전량 체결
    CANCEL,          // 취소
    REJECT,          // 리스크/잔고 부족으로 거부
}
