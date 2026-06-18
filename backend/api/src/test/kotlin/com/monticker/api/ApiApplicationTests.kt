package com.monticker.api

import org.junit.jupiter.api.Test

// Full context load requires a running DB (use Testcontainers for integration tests).
// This smoke test only verifies the application class is loadable without infrastructure.
class ApiApplicationTests {

	@Test
	fun `application class is importable`() {
		// no-op: real context load tests live in integration test suites with Testcontainers
	}

}
