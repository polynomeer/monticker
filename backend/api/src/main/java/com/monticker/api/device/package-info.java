/**
 * 푸시 기기 토큰 모듈. common — 탈퇴 이벤트(UserAccountDeletedEvent)를 받아 토큰을 지운다(ADR-114).
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common"}
)
package com.monticker.api.device;
