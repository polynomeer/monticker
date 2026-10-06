package com.monticker.api.auth.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.common.aop.RateLimited
import com.monticker.api.common.consent.ConsentGroup
import com.monticker.api.common.consent.ConsentService
import com.monticker.api.common.consent.ConsentSource
import com.monticker.api.common.consent.ConsentState
import com.monticker.api.common.consent.ConsentType
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

data class ConsentStatusResponse(
    val states: List<ConsentState>,
    /** 지금 다시 받아야 하는 가입 필수 동의 — 비어 있지 않으면 화면이 동의 화면으로 보낸다(소셜 가입·문서 개정). */
    val missingRequired: List<ConsentType>,
)

data class ConsentRequest(@field:Size(max = 10) val consents: List<String> = emptyList())

/**
 * ADR-068 — 내 동의 상태. 이메일 가입은 가입 요청에서 동의를 받지만, 소셜 가입은 그 경로가 없고 약관 개정 뒤에는
 * 기존 회원도 다시 동의해야 한다. 둘 다 이 API로 확인하고 받는다.
 */
@Validated
@RestController
@RequestMapping("/api/users/me/consents")
class ConsentController(
    private val jwtTokenProvider: JwtTokenProvider,
    private val consentService: ConsentService,
) {
    private fun userId(auth: String) = jwtTokenProvider.getUserId(auth.removePrefix("Bearer ").trim())

    @GetMapping
    fun status(@RequestHeader("Authorization") auth: String): ResponseEntity<ConsentStatusResponse> {
        val userId = userId(auth)
        return ResponseEntity.ok(
            ConsentStatusResponse(
                states = consentService.status(userId),
                missingRequired = consentService.missingRequired(userId, ConsentGroup.SIGNUP).sortedBy { it.ordinal },
            ),
        )
    }

    /** 가입 필수 동의를 (다시) 받는다. 필수가 빠지면 400. */
    @PostMapping
    @RateLimited(limit = 20, windowSec = 60, keyPrefix = "consent.write")
    fun agree(@RequestHeader("Authorization") auth: String, @RequestBody body: ConsentRequest): ResponseEntity<ConsentStatusResponse> {
        val userId = userId(auth)
        consentService.requireMissingAndRecord(userId, ConsentGroup.SIGNUP, body.consents, ConsentSource.CONSENT_PROMPT)
        return status(auth)
    }

    /** 선택 동의(마케팅) 하나에 (다시) 동의한다 — 설정 화면. 필수 항목은 400(가입·동의 화면에서 묶음으로 받는다). */
    @PutMapping("/{type}")
    fun agreeOne(@RequestHeader("Authorization") auth: String, @PathVariable type: String): ResponseEntity<ConsentStatusResponse> {
        consentService.agreeOptional(userId(auth), parseType(type), ConsentSource.SETTINGS)
        return status(auth)
    }

    /** 선택 동의(마케팅) 철회. 필수 동의는 철회할 수 없다(400). */
    @DeleteMapping("/{type}")
    @RateLimited(limit = 20, windowSec = 60, keyPrefix = "consent.write")
    fun withdraw(@RequestHeader("Authorization") auth: String, @PathVariable type: String): ResponseEntity<ConsentStatusResponse> {
        consentService.withdraw(userId(auth), parseType(type), ConsentSource.SETTINGS)
        return status(auth)
    }

    private fun parseType(type: String) =
        ConsentType.entries.firstOrNull { it.name == type.uppercase() } ?: throw IllegalArgumentException("알 수 없는 동의 항목: $type")
}
