package com.monticker.api.batch.subscription

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.configuration.annotation.StepScope

/**
 * ADR-053 — 갱신 배치 리더의 설정을 지킨다.
 *
 * 예전 `RepositoryItemReader`는 리포지토리 메서드를 이름(문자열)으로 리플렉션 호출해, 시그니처가 어긋나도
 * 컴파일러가 잡지 못했다 — 갱신 잡이 매 실행 `NoSuchMethodException`으로 죽은 적이 있다(CH-13). 지금은
 * [com.monticker.api.batch.KeysetItemReader]가 람다로 직접 호출하므로 그 계약은 컴파일러가 검사한다.
 */
class SubscriptionRenewalJobConfigTest {

    @Test
    fun `리더는 StepScope다 — 기동 시각이 임계로 굳지 않는다`() {
        // 싱글턴이면 arguments의 Instant.now()가 애플리케이션 기동 시각으로 고정되어,
        // 몇 주째 떠 있는 인스턴스는 영원히 기동일 기준으로 만료 예정을 찾는다.
        val bean = SubscriptionRenewalJobConfig::class.java
            .getDeclaredMethod("expiringSubscriptionReader")

        assertThat(bean.isAnnotationPresent(StepScope::class.java)).isTrue()
    }
}
