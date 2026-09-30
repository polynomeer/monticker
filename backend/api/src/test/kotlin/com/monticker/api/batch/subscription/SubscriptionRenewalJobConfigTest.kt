package com.monticker.api.batch.subscription

import com.monticker.api.subscription.infrastructure.UserSubscriptionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import java.time.Instant

/**
 * ADR-053 — 갱신 배치가 **읽을 수는 있는가**를 지킨다.
 *
 * 이 테스트가 없던 동안 정기결제 갱신 잡은 실행할 때마다
 * `NoSuchMethodException: findExpiringBefore(Instant, PageRequest)` 로 죽고 skip limit을
 * 넘겨 FAILED로 끝났다 — 단 한 번도 구독을 갱신한 적이 없었다. CH-13 카오스 실험을 처음
 * 돌리다 발견했다. 배치가 "돌긴 도는데 0건 처리"와 "아예 읽지 못함"은 로그를 열기 전에는
 * 구분되지 않고, 실행 결과를 보는 사람이 아무도 없었다.
 *
 * `RepositoryItemReader`는 설정한 arguments 뒤에 `PageRequest`를 덧붙여 리플렉션으로
 * 호출하므로, 리포지토리 메서드의 마지막 파라미터는 반드시 `Pageable`이어야 한다.
 * 컴파일러는 이 계약을 검사하지 않는다 — 메서드 이름이 문자열이기 때문이다.
 */
class SubscriptionRenewalJobConfigTest {

    @Test
    fun `리더가 부르는 리포지토리 메서드는 RepositoryItemReader의 계약에 맞는다`() {
        val method = UserSubscriptionRepository::class.java.methods
            .firstOrNull { it.name == "findExpiringBefore" }

        assertThat(method).isNotNull()
        // 마지막 인자는 Pageable — 리더가 PageRequest를 덧붙여 호출한다.
        assertThat(method!!.parameterTypes.toList())
            .containsExactly(Instant::class.java, Pageable::class.java)
        // 반환은 Slice — 리더가 doPageRead에서 Slice로 캐스팅한다. List를 돌려주면
        // ClassCastException으로 죽는다(실제로 그랬다).
        assertThat(Slice::class.java).isAssignableFrom(method.returnType)
    }

    @Test
    fun `리더는 StepScope다 — 기동 시각이 임계로 굳지 않는다`() {
        // 싱글턴이면 arguments의 Instant.now()가 애플리케이션 기동 시각으로 고정되어,
        // 몇 주째 떠 있는 인스턴스는 영원히 기동일 기준으로 만료 예정을 찾는다.
        val bean = SubscriptionRenewalJobConfig::class.java
            .getDeclaredMethod("expiringSubscriptionReader")

        assertThat(bean.isAnnotationPresent(StepScope::class.java)).isTrue()
    }
}
