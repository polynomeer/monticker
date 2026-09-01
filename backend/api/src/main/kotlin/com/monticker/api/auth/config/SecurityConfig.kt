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
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/stocks/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/events/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/screener/**").permitAll()
                    .requestMatchers("/api/backtest/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/latency/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .requestMatchers("/actuator/metrics", "/actuator/prometheus").denyAll()
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
