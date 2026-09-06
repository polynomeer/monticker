package com.monticker.api.subscription.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.subscription.domain.UserBillingKey
import com.monticker.api.subscription.infrastructure.UserBillingKeyRepository
import com.monticker.api.subscription.infrastructure.pg.BillingKeyResult
import com.monticker.api.subscription.infrastructure.pg.PgClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class BillingControllerTest {

    private val pgClient = mockk<PgClient>()
    private val billingKeyRepo = mockk<UserBillingKeyRepository>()
    private val jwtTokenProvider = mockk<JwtTokenProvider>()

    private val controller = BillingController(pgClient, billingKeyRepo, jwtTokenProvider)

    @Test
    fun `customer-key는 기존 등록이 없으면 새로 생성해서 돌려준다`() {
        every { jwtTokenProvider.getUserId("t") } returns 1L
        every { billingKeyRepo.findByUserId(1L) } returns Optional.empty()

        val response = controller.getOrCreateCustomerKey("Bearer t")

        assertThat(response.body!!.customerKey).startsWith("user_1_")
    }

    @Test
    fun `customer-key는 기존 등록이 있으면 그 값을 재사용한다`() {
        val existing = UserBillingKey(userId = 1L, customerKey = "existing_key", billingKeyValue = "bk")
        every { jwtTokenProvider.getUserId("t") } returns 1L
        every { billingKeyRepo.findByUserId(1L) } returns Optional.of(existing)

        val response = controller.getOrCreateCustomerKey("Bearer t")

        assertThat(response.body!!.customerKey).isEqualTo("existing_key")
    }

    @Test
    fun `register는 PG 발급 성공 시 신규 빌링키를 저장한다`() {
        every { jwtTokenProvider.getUserId("t") } returns 1L
        every { pgClient.issueBillingKey("auth1", "cust1") } returns
            BillingKeyResult(success = true, billingKey = "bk_1", cardCompany = "신한", cardLast4 = "5678")
        every { billingKeyRepo.findByUserId(1L) } returns Optional.empty()
        val saved = slot<UserBillingKey>()
        every { billingKeyRepo.save(capture(saved)) } answers { saved.captured }

        val response = controller.register("Bearer t", BillingController.RegisterBillingRequest("auth1", "cust1"))

        assertThat(response.statusCode.is2xxSuccessful).isTrue()
        assertThat(response.body!!.registered).isTrue()
        assertThat(saved.captured.userId).isEqualTo(1L)
        assertThat(saved.captured.billingKeyValue).isEqualTo("bk_1")
    }

    @Test
    fun `register는 PG 발급 실패 시 저장하지 않고 400을 반환한다`() {
        every { jwtTokenProvider.getUserId("t") } returns 1L
        every { pgClient.issueBillingKey(any(), any()) } returns
            BillingKeyResult(success = false, failureReason = "카드 인증 실패")

        val response = controller.register("Bearer t", BillingController.RegisterBillingRequest("bad", "cust1"))

        assertThat(response.statusCode.is4xxClientError).isTrue()
        verify(exactly = 0) { billingKeyRepo.save(any()) }
    }

    @Test
    fun `getStatus는 등록된 카드 정보를 반환한다`() {
        val existing = UserBillingKey(userId = 1L, customerKey = "c", billingKeyValue = "bk", cardCompany = "국민", cardLast4 = "9999")
        every { jwtTokenProvider.getUserId("t") } returns 1L
        every { billingKeyRepo.findByUserId(1L) } returns Optional.of(existing)

        val response = controller.getStatus("Bearer t")

        assertThat(response.body!!.registered).isTrue()
        assertThat(response.body!!.cardCompany).isEqualTo("국민")
    }

    @Test
    fun `deregister는 인증된 사용자 자신의 빌링키만 삭제한다`() {
        every { jwtTokenProvider.getUserId("t") } returns 1L
        every { billingKeyRepo.deleteByUserId(1L) } returns Unit

        val response = controller.deregister("Bearer t")

        assertThat(response.statusCode.value()).isEqualTo(204)
        verify { billingKeyRepo.deleteByUserId(1L) }
    }
}
