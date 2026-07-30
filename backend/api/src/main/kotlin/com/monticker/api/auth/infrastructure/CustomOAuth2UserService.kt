package com.monticker.api.auth.infrastructure

import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Service

/**
 * 카카오·네이버의 비표준 속성 구조를 Google과 동일한 형태(email, name)로 정규화.
 *
 * 카카오 응답: { id, kakao_account: { email, profile: { nickname } } }
 * 네이버 응답: { resultcode, message, response: { id, email, name } }
 */
@Service
class CustomOAuth2UserService : OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private val delegate = DefaultOAuth2UserService()

    override fun loadUser(userRequest: OAuth2UserRequest): OAuth2User {
        val oAuth2User = delegate.loadUser(userRequest)
        return when (userRequest.clientRegistration.registrationId) {
            "kakao"  -> normalizeKakao(oAuth2User)
            "naver"  -> normalizeNaver(oAuth2User)
            "google" -> normalizeGoogle(oAuth2User)
            else     -> oAuth2User
        }
    }

    private fun normalizeKakao(user: OAuth2User): OAuth2User {
        @Suppress("UNCHECKED_CAST")
        val account = user.getAttribute<Map<String, Any>>("kakao_account") ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val profile = account["profile"] as? Map<String, Any> ?: emptyMap()

        val email    = account["email"] as? String ?: ""
        val nickname = profile["nickname"] as? String ?: email.substringBefore("@")

        val attrs = mapOf("email" to email, "name" to nickname, "provider" to "KAKAO")
        return DefaultOAuth2User(listOf(SimpleGrantedAuthority("ROLE_USER")), attrs, "email")
    }

    private fun normalizeGoogle(user: OAuth2User): OAuth2User {
        val email    = user.getAttribute<String>("email") ?: ""
        val name     = user.getAttribute<String>("name")  ?: email.substringBefore("@")
        val sub      = user.getAttribute<String>("sub")   ?: ""
        val attrs = mapOf("email" to email, "name" to name, "provider" to "GOOGLE", "sub" to sub)
        return DefaultOAuth2User(listOf(SimpleGrantedAuthority("ROLE_USER")), attrs, "email")
    }

    private fun normalizeNaver(user: OAuth2User): OAuth2User {
        @Suppress("UNCHECKED_CAST")
        val response = user.getAttribute<Map<String, Any>>("response") ?: emptyMap()

        val email = response["email"] as? String ?: ""
        val name  = response["name"]  as? String ?: email.substringBefore("@")

        val attrs = mapOf("email" to email, "name" to name, "provider" to "NAVER")
        return DefaultOAuth2User(listOf(SimpleGrantedAuthority("ROLE_USER")), attrs, "email")
    }
}
