package com.monticker.api

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

/**
 * Spring Modulith 모듈 경계 검증 테스트.
 *
 * 각 패키지의 package-info.java에 선언된 allowedDependencies 규칙을 기준으로
 * 모듈 간 불허 의존을 컴파일 타임에 가까운 수준에서 잡아낸다.
 *
 * verify()는 실패 시 AssertionError를 던지므로 CI에서 모듈 규칙 위반을 차단한다.
 */
class ModulithStructureTest {

    private val modules = ApplicationModules.of(ApiApplication::class.java)

    @Test
    fun `module boundaries must be respected`() {
        modules.verify()
    }

    @Test
    fun `generate module documentation`() {
        Documenter(modules)
            .writeModulesAsPlantUml()
            .writeIndividualModulesAsPlantUml()
    }
}
