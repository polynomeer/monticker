package com.monticker.worker.toss

import com.fasterxml.jackson.databind.JsonNode

/**
 * Toss WebSocket 푸시 메시지를 채널별로 처리하는 핸들러.
 *
 * KIS의 KisRealtimeHandler와 같은 역할이지만, Toss는 pipe-delimited가 아니라 JSON이고
 * 종목코드가 payload(data)가 아니라 topic 문자열("trade:kr:005930")에 있어 시그니처가 다르다.
 */
interface TossRealtimeHandler {
    /** topic의 첫 세그먼트 — 예: "trade" (topic 예시: "trade:kr:005930") */
    val channelPrefix: String

    fun handle(topic: String, data: JsonNode)
}
