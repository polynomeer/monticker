package com.monticker.api.auth.config

import com.monticker.api.auth.infrastructure.CustomOAuth2UserService
import com.monticker.api.auth.infrastructure.HttpCookieOAuth2AuthorizationRequestRepository
import com.monticker.api.auth.infrastructure.JwtAuthenticationFilter
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.auth.infrastructure.OAuth2SuccessHandler
import com.monticker.api.common.idempotency.IdempotencyFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
class SecurityConfig(
    private val jwtTokenProvider: JwtTokenProvider,
    private val idempotencyFilter: IdempotencyFilter,
    private val oauth2SuccessHandler: OAuth2SuccessHandler,
    private val customOAuth2UserService: CustomOAuth2UserService,
    private val cookieAuthorizationRequestRepository: HttpCookieOAuth2AuthorizationRequestRepository,
    @Value("\${app.cors.allowed-origins:http://localhost:3000}") private val allowedOrigins: String,
    @Value("\${app.base-url:http://localhost:3000}") private val baseUrl: String,
) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            // OAuth2 인가 코드 플로우는 세션이 필요 — IF_REQUIRED로 완화
            // (JWT API 요청은 JwtAuthenticationFilter가 처리하므로 실질적으로 stateless)
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .authorizeHttpRequests { auth ->
                auth
                    // 부하 테스트 중 발견: RateLimitFilter.sendError(429)가 Tomcat의 에러 페이지
                    // 해석을 위해 /error로 내부 재디스패치를 트리거하는데, /error가 이 목록에 없어
                    // anyRequest().authenticated()에 걸려 401로 되돌아갔다 — 클라이언트는 항상 진짜
                    // 이유(429 rate limit) 대신 인증 안 됨만 보게 되는 상태였다.
                    .requestMatchers("/error").permitAll()
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/stocks/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/events/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/screener/**").permitAll()
                    .requestMatchers("/api/backtest/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/latency/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    // /actuator/metrics, /actuator/prometheus는 한때 denyAll이었는데, 그러면
                    // Prometheus의 스크레이프 요청도 401로 막혀 모니터링 전체가 죽어 있었다.
                    // Ingress(infra/k8s/base/ingress.yaml)는 /api, /ws, / 만 라우팅하고 /actuator/**는
                    // 아예 라우팅하지 않으므로 공인 인터넷에서는 원천적으로 도달 불가능하다 — 실제
                    // 보안 경계는 여기(Spring Security)가 아니라 네트워크 토폴로지다. 아래 permitAll에
                    // 이미 포함되지만, 왜 안전한지 남겨둔다.
                    .requestMatchers("/actuator/**").permitAll()
                    .requestMatchers("/ws/**").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
            .oauth2Login { oauth2 ->
                oauth2
                    .authorizationEndpoint { ep ->
                        ep.authorizationRequestRepository(cookieAuthorizationRequestRepository)
                    }
                    .userInfoEndpoint { it.userService(customOAuth2UserService) }
                    .successHandler(oauth2SuccessHandler)
                    .failureUrl("$baseUrl/login?error=oauth2")
            }
            .addFilterBefore(
                JwtAuthenticationFilter(jwtTokenProvider),
                UsernamePasswordAuthenticationFilter::class.java,
            )
            .addFilterAfter(
                idempotencyFilter,
                JwtAuthenticationFilter::class.java,
            )
        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration()
        config.allowedOriginPatterns = allowedOrigins.split(",").map { it.trim() }
        config.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("*")
        config.allowCredentials = true
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/api/**", config)
        source.registerCorsConfiguration("/ws/**", config)
        return source
    }
}
