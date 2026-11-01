package com.monticker.api.auth.api

import com.monticker.api.auth.application.UserProfile
import com.monticker.api.auth.application.UserProfileService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 내 계정 프로필(닉네임·이메일·가입 경로). 대상은 인증 토큰으로만 정한다 — 웹 계정 메뉴·모바일 설정 프로필 카드가 쓴다. */
@RestController
@RequestMapping("/api/users/me")
class UserProfileController(private val profiles: UserProfileService) {

    @GetMapping
    fun get(@AuthenticationPrincipal userId: Long): ResponseEntity<UserProfile> =
        ResponseEntity.ok(profiles.get(userId))
}
