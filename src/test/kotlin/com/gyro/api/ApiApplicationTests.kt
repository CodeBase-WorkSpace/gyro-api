package com.gyro.api

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
@SpringBootTest
class ApiApplicationTests {

	@Test
	fun contextLoads() {
	}

}
