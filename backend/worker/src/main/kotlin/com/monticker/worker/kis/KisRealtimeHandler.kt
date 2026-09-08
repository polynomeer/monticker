package com.monticker.worker.kis

/**
 * KIS WebSocket이 수신한 pipe-delimited 프레임을 TR 타입별로 처리하는 핸들러.
 *
 * KisWebSocketClient는 커넥션 하나를 여러 TR 타입(H0STASP0 호가, H0STCNT0 체결가 등)이
 * 공유하도록 trId로 디스패치한다 — 새 실시간 데이터 종류를 추가할 때 이 인터페이스만
 * 구현하면 KisWebSocketClient 쪽 변경 없이 Spring이 자동으로 수집한다.
 */
interface KisRealtimeHandler {
    val trId: String

    /** parts는 raw.split("|") 전체 — [encType, trId, dataCount, ...fields] */
    fun handle(parts: List<String>)
}
