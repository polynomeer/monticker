package com.monticker.api.common.config

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * docs/security-review.md C1 — `jwt.secret`과 `app.security.credential-encryption-key`의
 * 로컬 개발용 기본값은 둘 다 git에 커밋된 적이 있어 공개돼 있다. 예전에는
 * `SPRING_PROFILES_ACTIVE=prod`를 켜는 배포 경로가 리포에 하나도 없어서, 이 공개된 기본값이
 * 실제 배포에 그대로 올라갈 수 있었다(JWT 위조로 인증 전체 우회, 또는 모든 사용자의 실제
 * 브로커리지 자격증명 복호화).
 *
 * `SPRING_PROFILES_ACTIVE=prod`를 켜는 게 근본 수정(infra/k8s/base/configmap.yaml 참고)이지만,
 * 프로파일 활성화에만 의존하면 미래에 같은 실수(새 배포 경로 추가 시 프로파일 설정을 빠뜨림,
 * 또는 운영자가 실수로 이 리터럴 값을 그대로 시크릿에 채워넣음)가 재발해도 조용히 통과한다.
 * 그래서 프로파일과 무관하게, 실제로 주입된 값 자체가 이 공개된 리터럴과 정확히 같은지 직접
 * 검사한다 — 순수 로컬 개발(테스트 없이 그냥 뜨는 용도)만 `ALLOW_INSECURE_DEV_SECRETS=true`로
 * 명시적으로 허용한다.
 */
@Component
class InsecureSecretGuard(
    @Value("\${jwt.secret}") private val jwtSecret: String,
    @Value("\${app.security.credential-encryption-key}") private val credentialEncryptionKey: String,
    @Value("\${app.security.allow-insecure-dev-secrets:false}") private val allowInsecureDevSecrets: Boolean,
) {
    @PostConstruct
    fun verify() {
        if (allowInsecureDevSecrets) return

        val offenders = buildList {
            if (jwtSecret == KNOWN_LEAKED_JWT_SECRET) add("jwt.secret (JWT_SECRET)")
            if (credentialEncryptionKey == KNOWN_LEAKED_CREDENTIAL_ENCRYPTION_KEY) {
                add("app.security.credential-encryption-key (CREDENTIAL_ENCRYPTION_KEY)")
            }
        }

        check(offenders.isEmpty()) {
            "다음 설정값이 git에 커밋된 적 있는 공개 기본값과 정확히 같습니다: ${offenders.joinToString()}. " +
                "실제 배포라면 반드시 새로 발급한 값으로 교체하세요(docs/security-review.md C1, " +
                "credential-encryption-key는 `openssl rand -base64 32`, jwt.secret은 최소 32바이트 " +
                "랜덤 문자열). 순수 로컬 개발 용도로 이 기본값을 그대로 쓰려면 " +
                "ALLOW_INSECURE_DEV_SECRETS=true를 명시적으로 설정하세요 — 프로덕션에서는 절대 설정하지 말 것."
        }
    }

    companion object {
        // 아래 두 값은 이미 git 히스토리에 커밋돼 공개된 값이다 — 이 값과 정확히 같은 게 감지되면
        // (ALLOW_INSECURE_DEV_SECRETS=true가 아닌 한) 기동을 막는다. application.yml의 기본값과 동기화할 것.
        private const val KNOWN_LEAKED_JWT_SECRET = "monticker-dev-secret-key-must-be-at-least-32-bytes-long"
        private const val KNOWN_LEAKED_CREDENTIAL_ENCRYPTION_KEY = "HSKZ1kiMBZ80UfTmpyhmFXh6TtteeeweRhbngzaD4yk="
    }
}
