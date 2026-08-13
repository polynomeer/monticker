package com.monticker.api.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * PasswordEncoder를 SecurityConfig에서 분리한 이유: SecurityConfig는 OAuth2SuccessHandler를
 * 생성자로 주입받는데, OAuth2SuccessHandler -> AuthService -> PasswordEncoder(SecurityConfig의 @Bean)
 * 로 이어지는 순환 참조가 생겨 컨텍스트 초기화가 실패한다. 의존성 없는 별도 설정으로 빼서 순환을 끊는다.
 */
@Configuration
class PasswordEncoderConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
