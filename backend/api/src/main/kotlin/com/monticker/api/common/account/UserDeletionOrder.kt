package com.monticker.api.common.account

/**
 * ADR-114 Note — [UserAccountDeletedEvent] 리스너의 실행 순서(`@Order` 값). 작은 값이 먼저 돈다. 값이 없는 리스너는
 * 맨 뒤(LOWEST_PRECEDENCE)에 돈다 — 다른 모듈 테이블과 FK로 엮이지 않은 데이터는 순서를 정하지 않는다.
 *
 * 모의투자 데이터는 세 모듈의 테이블이 FK로 엮여 있다(order_emotion_tags → paper_trades → fills → orders,
 * paper_settlements → paper_trades, paper_conditional_orders → orders, order_sagas → orders). 그래서 먼저 동시에 도는
 * 체결·발동을 막고(FREEZE), 참조하는 쪽부터 지운다(wallet → paper → matching).
 *
 * FREEZE의 락 순서는 체결 경로와 같다: 조건부 주문 행 → 주문 행 → 포지션 행 → 계좌 행
 * (PaperConditionalOrderFirer, LimitOrderFiller, OrderSagaOrchestrator). 순서가 같아야 서로 기다리다 교착하지 않는다.
 */
object UserDeletionOrder {
    /** paper — 아직 발동하지 않은 조건부 주문을 취소하며 행을 잠근다. 발동 중인 것은 끝날 때까지 기다린다. */
    const val PAPER_FREEZE = 100

    /** matching — 미체결 주문·포지션·계좌 행을 잠근다. 체결 중인 주문은 끝날 때까지 기다리고, 이후 스위퍼는 건너뛴다. */
    const val MATCHING_FREEZE = 200

    /** wallet — paper_trades를 참조하는 감정 태그와 원장. */
    const val WALLET = 300

    /** paper — fills·orders를 참조하는 계좌 기록. */
    const val PAPER = 400

    /** matching — 마지막으로 체결·사가·주문. */
    const val MATCHING = 500
}
