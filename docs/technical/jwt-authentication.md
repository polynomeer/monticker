# JWT 기반 무상태 인증 아키텍처

## 1. 설계 목표

monticker의 인증 계층은 수평 확장(horizontal scaling)을 전제로 설계되었다. 사용자 세션 상태를 서버 메모리에 저장하는 전통적인 세션 방식은 특정 서버 인스턴스에 묶인다. 로드 밸런서가 요청을 다른 인스턴스로 라우팅하면 세션을 찾지 못하므로, sticky session 설정이나 Redis 기반 세션 공유가 필요하다.

JWT(JSON Web Token)를 사용하면 토큰 자체에 사용자 식별자를 포함하기 때문에 서버는 데이터베이스 조회 없이도 토큰의 유효성을 검증하고 사용자를 식별할 수 있다. 어떤 인스턴스가 요청을 받든 동일한 비밀 키만 있으면 검증이 가능하다.

현재 `SecurityConfig`는 모든 요청을 허용하는 상태이며, JWT 필터는 별도로 구현 예정이다. 이 문서는 설계 방향과 완성 시 동작을 기술한다.

---

## 2. 토큰 구조: Access + Refresh 이중 토큰

단일 토큰 방식(long-lived access token)은 토큰이 탈취되면 만료 시까지 무효화가 불가능하다. 이중 토큰을 사용하면 이 위험을 제한할 수 있다.

| 토큰 | 수명 | 저장 위치 | 용도 |
|------|------|----------|------|
| Access Token | 15분 | 메모리(변수) | API 호출 시 Authorization 헤더 |
| Refresh Token | 7일 | HttpOnly 쿠키 + DB | Access Token 재발급 |

**Access Token을 15분으로 짧게 설정하는 이유**: 탈취되더라도 15분 후에는 무용지물이 된다. API 호출 시 헤더에 포함하므로 JavaScript에서 읽어야 하지만, 수명이 짧아 위험이 제한된다.

**Refresh Token을 DB에 저장하는 이유**: Access Token과 달리 Refresh Token은 서버가 무효화할 수 있어야 한다. 사용자가 로그아웃하거나 계정 도용이 의심될 때 DB에서 삭제하면 즉시 효력을 잃는다. DB 저장 없이 stateless하게만 처리하면 7일 수명의 Refresh Token을 무효화할 방법이 없다.

**Refresh Token을 HttpOnly 쿠키에 저장하는 이유**: `document.cookie`로 JavaScript에서 접근할 수 없으므로 XSS(Cross-Site Scripting) 공격으로부터 토큰 값을 보호할 수 있다. Access Token은 메모리(React state 또는 클로저 변수)에만 저장하고 localStorage에는 저장하지 않는다.

---

## 3. 발급/검증 흐름

### 최초 로그인

```
클라이언트                       서버 (Spring Boot)               DB
    │                                   │                         │
    │  POST /auth/login                 │                         │
    │  { email, password }              │                         │
    │──────────────────────────────────>│                         │
    │                                   │  SELECT user WHERE      │
    │                                   │  email = ?              │
    │                                   │────────────────────────>│
    │                                   │<────────────────────────│
    │                                   │  bcrypt.verify()        │
    │                                   │                         │
    │                                   │  INSERT refresh_tokens  │
    │                                   │  (userId, token, exp)   │
    │                                   │────────────────────────>│
    │                                   │                         │
    │  200 OK                           │                         │
    │  { accessToken }                  │                         │
    │  Set-Cookie: refreshToken=...; HttpOnly; Secure; SameSite=Strict
    │<──────────────────────────────────│                         │
    │                                   │                         │
    │  (메모리에 accessToken 저장)      │                         │
```

### API 호출

```
클라이언트                       서버
    │                               │
    │  GET /api/watchlist           │
    │  Authorization: Bearer <AT>   │
    │──────────────────────────────>│
    │                               │  JwtAuthenticationFilter
    │                               │  1. 헤더에서 AT 추출
    │                               │  2. 서명 검증 (HMAC-SHA256)
    │                               │  3. 만료 여부 확인
    │                               │  4. SecurityContext에 userId 주입
    │                               │
    │  200 OK { ... }               │
    │<──────────────────────────────│
```

### Access Token 만료 시 재발급

```
클라이언트                       서버                         DB
    │                               │                         │
    │  GET /api/watchlist           │                         │
    │  Authorization: Bearer <만료 AT>
    │──────────────────────────────>│                         │
    │  401 Unauthorized             │                         │
    │<──────────────────────────────│                         │
    │                               │                         │
    │  POST /auth/refresh           │                         │
    │  (쿠키에서 RT 자동 전송)       │                         │
    │──────────────────────────────>│                         │
    │                               │  SELECT refresh_tokens  │
    │                               │  WHERE token = ?        │
    │                               │────────────────────────>│
    │                               │<────────────────────────│
    │                               │  (유효성 및 만료 확인)  │
    │                               │                         │
    │                               │  새 AT + 새 RT 발급     │
    │                               │  기존 RT 삭제 (rotation)│
    │                               │  새 RT 저장             │
    │                               │────────────────────────>│
    │                               │                         │
    │  200 OK { newAccessToken }    │                         │
    │  Set-Cookie: refreshToken=<new RT>                      │
    │<──────────────────────────────│                         │
    │                               │                         │
    │  GET /api/watchlist           │                         │
    │  Authorization: Bearer <new AT>
    │──────────────────────────────>│                         │
```

---

## 4. Refresh Token Rotation

재발급 요청이 들어올 때마다 기존 Refresh Token을 폐기하고 새 토큰을 발급한다. 이를 **Refresh Token Rotation**이라고 한다.

**이유**: Refresh Token이 탈취되었을 때 감지가 가능해진다. 공격자가 탈취한 RT로 재발급을 요청하면 그 RT는 폐기된다. 이후 정상 사용자가 (이미 폐기된) 동일 RT로 재발급을 시도하면 서버는 해당 RT가 DB에 없음을 확인하고 해당 사용자의 모든 RT를 강제 폐기하여 전체 세션을 종료할 수 있다.

```kotlin
// 설계 의도 (구현 예정)
fun rotateRefreshToken(oldToken: String): TokenPair {
    val stored = refreshTokenRepository.findByToken(oldToken)
        ?: throw UnauthorizedException("유효하지 않은 refresh token")

    if (stored.isExpired()) throw UnauthorizedException("만료된 refresh token")

    // rotation: 기존 토큰 삭제
    refreshTokenRepository.delete(stored)

    // 새 토큰 쌍 발급 및 저장
    val newPair = jwtTokenProvider.generateTokenPair(stored.userId)
    refreshTokenRepository.save(RefreshToken(stored.userId, newPair.refreshToken))

    return newPair
}
```

---

## 5. JwtAuthenticationFilter 동작

Spring Security의 필터 체인에 `OncePerRequestFilter`를 상속하여 추가한다.

```kotlin
// 설계 의도 (구현 예정)
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = extractBearerToken(request)

        if (token != null && jwtTokenProvider.isValid(token)) {
            val userId = jwtTokenProvider.getUserId(token)

            // SecurityContext에 인증 객체 주입
            val auth = UsernamePasswordAuthenticationToken(
                userId,   // principal — @AuthenticationPrincipal로 접근
                null,
                emptyList(),
            )
            SecurityContextHolder.getContext().authentication = auth
        }

        filterChain.doFilter(request, response)
    }
}
```

컨트롤러에서는 `@AuthenticationPrincipal`로 userId를 받는다.

```kotlin
@GetMapping("/watchlist")
fun getWatchlist(
    @AuthenticationPrincipal userId: Long,
): ResponseEntity<List<WatchlistItem>> {
    return ResponseEntity.ok(watchlistService.findByUserId(userId))
}
```

Spring Security가 SecurityContext에서 principal을 꺼내 주입하므로, 컨트롤러는 토큰 파싱 로직과 완전히 분리된다.

---

## 6. Spring Security 통합

```kotlin
@Configuration
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .exceptionHandling {
                it.authenticationEntryPoint(
                    HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
                )
            }
            .authorizeHttpRequests {
                it.requestMatchers("/auth/**", "/api/stocks/**").permitAll()
                it.anyRequest().authenticated()
            }
            .addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter::class.java,
            )
        return http.build()
    }
}
```

**`STATELESS` 세션 정책**: 서버가 `HttpSession`을 생성하지 않는다. 매 요청은 JWT만으로 인증된다. 이는 Spring Security의 기본 동작(세션 생성)을 명시적으로 비활성화한다.

**`HttpStatusEntryPoint(401)` 선택 이유**: Spring Security의 기본 `authenticationEntryPoint`는 로그인 페이지로 리디렉션(302)을 반환한다. REST API 클라이언트는 리디렉션을 처리하지 않으므로, 인증되지 않은 요청에 대해 단순히 401을 반환하는 것이 적합하다. 프론트엔드는 401을 수신하면 refresh 흐름을 시작한다.

---

## 7. CORS 설정

```kotlin
@Bean
fun corsConfigurationSource(): CorsConfigurationSource {
    val config = CorsConfiguration()
    config.allowedOriginPatterns = listOf(
        "http://localhost:*",
        "https://*.monticker.com",
    )
    config.allowCredentials = true
    config.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
    config.allowedHeaders = listOf("*")

    val source = UrlBasedCorsConfigurationSource()
    source.registerCorsConfiguration("/**", config)
    return source
}
```

**`allowCredentials = true`가 필요한 이유**: Refresh Token이 HttpOnly 쿠키에 저장되므로, 브라우저가 cross-origin 요청 시 쿠키를 자동으로 포함하려면 서버가 `Access-Control-Allow-Credentials: true`를 반환해야 한다. 이때 `allowedOrigins = listOf("*")`는 사용할 수 없다. `*`와 `allowCredentials: true`는 CORS 명세에서 함께 사용할 수 없으므로, `allowedOriginPatterns`로 패턴을 지정해야 한다.

---

## 8. 프론트엔드: `authFetch`

Access Token은 메모리에만 보관하고 모든 인증 API 호출은 `authFetch` 래퍼를 통해 처리한다.

```typescript
// 설계 의도 (구현 예정)
let accessToken: string | null = null;

async function authFetch(url: string, options?: RequestInit): Promise<Response> {
  // 1. 보유한 AT로 요청
  const res = await fetch(url, {
    ...options,
    headers: {
      ...options?.headers,
      Authorization: `Bearer ${accessToken}`,
    },
  });

  // 2. 401이면 refresh 시도
  if (res.status === 401) {
    const refreshRes = await fetch("/auth/refresh", {
      method: "POST",
      credentials: "include", // HttpOnly 쿠키 자동 전송
    });

    if (refreshRes.ok) {
      const { accessToken: newAT } = await refreshRes.json();
      accessToken = newAT;

      // 3. 새 AT로 원래 요청 재시도
      return fetch(url, {
        ...options,
        headers: {
          ...options?.headers,
          Authorization: `Bearer ${accessToken}`,
        },
      });
    }

    // refresh도 실패하면 로그인 페이지로
    window.location.href = "/login";
  }

  return res;
}
```

이 패턴은 모든 API 호출을 `authFetch`로 대체함으로써 각 컴포넌트가 토큰 갱신 로직을 알지 못해도 된다는 점에서 관심사 분리가 명확하다.

---

## 9. 한계와 개선 방향

### JWT 탈취 시 즉시 무효화 불가

Access Token은 서버에 저장되지 않으므로, 발급 후 만료(15분) 전까지는 서버가 무효화할 방법이 없다. 계정 비밀번호 변경이나 강제 로그아웃 시에도 15분은 해당 AT가 유효하다.

**개선 방향**: Redis에 블랙리스트(JTI 기반)를 유지한다. 토큰 발급 시 `jti`(JWT ID) 클레임을 포함하고, 로그아웃이나 비밀번호 변경 시 해당 `jti`를 Redis에 저장한다. `JwtAuthenticationFilter`에서 블랙리스트를 확인하면 즉시 무효화가 가능하다. Redis 조회 비용은 메모리 기반이므로 DB 조회에 비해 무시할 수준이다.

```
블랙리스트 키: jwt:blacklist:{jti}
TTL: 해당 AT의 남은 만료 시간
```

### Refresh Token 패밀리(family) 추적 미구현

현재 설계는 RT Rotation 시 탈취 감지를 위한 패밀리 추적을 포함하지 않는다. 탈취된 RT로 재발급이 일어난 후 정상 사용자의 재발급 시도를 감지하려면, 같은 원본 RT에서 파생된 모든 토큰을 패밀리로 묶어 추적하는 추가 구현이 필요하다.
