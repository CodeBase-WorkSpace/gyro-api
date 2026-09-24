package com.gyro.api.auth

import com.gyro.api.auth.application.VerificationCodePolicy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerificationCodePolicyTest {
    @Test
    fun `dev accepts local staging bypass code`() {
        val policy = VerificationCodePolicy(MockEnvironment().apply { setActiveProfiles("dev") })

        assertTrue(policy.acceptsBypassCode("111000"))
    }

    @Test
    fun `staging accepts local staging bypass code`() {
        val policy = VerificationCodePolicy(MockEnvironment().apply { setActiveProfiles("staging") })

        assertTrue(policy.acceptsBypassCode("111000"))
    }

    @Test
    fun `production rejects local staging bypass code`() {
        val policy = VerificationCodePolicy(MockEnvironment().apply { setActiveProfiles("prod") })

        assertFalse(policy.acceptsBypassCode("111000"))
    }

    @Test
    fun `dev rejects other codes as bypass codes`() {
        val policy = VerificationCodePolicy(MockEnvironment().apply { setActiveProfiles("dev") })

        assertFalse(policy.acceptsBypassCode("123456"))
    }
}
