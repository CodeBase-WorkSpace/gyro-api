package com.gyro.api.common.idempotency

import com.gyro.api.common.error.IdempotencyKeyConflictException
import com.gyro.api.common.error.InvalidIdempotencyKeyException
import com.gyro.api.common.idempotency.domain.IdempotencyKey
import com.gyro.api.common.idempotency.repository.IdempotencyKeyRepository
import com.gyro.api.common.idempotency.service.IdempotencyResult
import com.gyro.api.common.idempotency.service.IdempotencyServiceImpl
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class IdempotencyServiceImplTest {
    private lateinit var repository: IdempotencyKeyRepository
    private lateinit var service: IdempotencyServiceImpl

    @BeforeEach
    fun setUp() {
        repository = Mockito.mock(IdempotencyKeyRepository::class.java)
        Mockito.`when`(repository.save(Mockito.any(IdempotencyKey::class.java))).thenAnswer { invocation ->
            invocation.arguments[0] as IdempotencyKey
        }
        service = IdempotencyServiceImpl(
            idempotencyKeyRepository = repository,
            objectMapper = JsonMapper.builder().build(),
            ttl = Duration.ofHours(24),
        )
    }

    @Test
    fun `blank key executes action without storing idempotency record`() {
        var actionCalls = 0

        val result = service.execute(
            scope = "custom-food:create",
            ownerUserId = null,
            idempotencyKey = "   ",
            request = TestRequest("rice"),
            responseType = TestResponse::class.java,
            responseStatus = 201,
        ) {
            actionCalls += 1
            TestResponse("created")
        }

        assertEquals(201, result.responseStatus)
        assertEquals(TestResponse("created"), result.body)
        assertEquals(1, actionCalls)
        Mockito.verify(repository, Mockito.never()).save(Mockito.any(IdempotencyKey::class.java))
    }

    @Test
    fun `new valid key stores serialized response and ttl`() {
        Mockito.`when`(repository.findByScopeAndIdempotencyKey("custom-food:create", "key-12345"))
            .thenReturn(null)
        val savedCaptor = ArgumentCaptor.forClass(IdempotencyKey::class.java)

        val result = service.execute(
            scope = "custom-food:create",
            ownerUserId = null,
            idempotencyKey = " key-12345 ",
            request = TestRequest("rice"),
            responseType = TestResponse::class.java,
            responseStatus = 201,
        ) {
            TestResponse("created")
        }

        Mockito.verify(repository).save(savedCaptor.capture())
        val saved = savedCaptor.value
        assertEquals(201, result.responseStatus)
        assertEquals(TestResponse("created"), result.body)
        assertEquals("custom-food:create", saved.scope)
        assertEquals("key-12345", saved.idempotencyKey)
        assertEquals(201, saved.responseStatus)
        assertNotNull(saved.requestHash)
        assertNotNull(saved.responseBody)
        assertEquals(Duration.ofHours(24), Duration.between(saved.createdAt, saved.expiresAt))
    }

    @Test
    fun `matching existing key replays stored response without executing action`() {
        val existing = storedRecord(
            request = TestRequest("rice"),
            response = TestResponse("created"),
            responseStatus = 201,
        )
        Mockito.`when`(repository.findByScopeAndIdempotencyKey("custom-food:create", "key-12345"))
            .thenReturn(existing)
        var actionCalls = 0

        val result = service.execute(
            scope = "custom-food:create",
            ownerUserId = null,
            idempotencyKey = "key-12345",
            request = TestRequest("rice"),
            responseType = TestResponse::class.java,
            responseStatus = 409,
        ) {
            actionCalls += 1
            TestResponse("should-not-run")
        }

        assertEquals(201, result.responseStatus)
        assertEquals(TestResponse("created"), result.body)
        assertEquals(0, actionCalls)
        Mockito.verify(repository, Mockito.never()).save(Mockito.any(IdempotencyKey::class.java))
    }

    @Test
    fun `dynamic status result stores and replays original response status`() {
        Mockito.`when`(repository.findByScopeAndIdempotencyKey("weight:entries:batch", "key-12345"))
            .thenReturn(null)
        val savedCaptor = ArgumentCaptor.forClass(IdempotencyKey::class.java)

        val result = service.executeResult(
            scope = "weight:entries:batch",
            ownerUserId = null,
            idempotencyKey = "key-12345",
            request = TestRequest("invalid-batch"),
            responseType = TestResponse::class.java,
        ) {
            IdempotencyResult(
                responseStatus = 400,
                body = TestResponse("invalid"),
            )
        }

        Mockito.verify(repository).save(savedCaptor.capture())
        val saved = savedCaptor.value
        assertEquals(400, result.responseStatus)
        assertEquals(400, saved.responseStatus)
        assertEquals(TestResponse("invalid"), result.body)

        Mockito.clearInvocations(repository)
        Mockito.`when`(repository.findByScopeAndIdempotencyKey("weight:entries:batch", "key-12345"))
            .thenReturn(saved)

        val replay = service.executeResult(
            scope = "weight:entries:batch",
            ownerUserId = null,
            idempotencyKey = "key-12345",
            request = TestRequest("invalid-batch"),
            responseType = TestResponse::class.java,
        ) {
            IdempotencyResult(
                responseStatus = 200,
                body = TestResponse("should-not-run"),
            )
        }

        assertEquals(400, replay.responseStatus)
        assertEquals(TestResponse("invalid"), replay.body)
        Mockito.verify(repository, Mockito.never()).save(Mockito.any(IdempotencyKey::class.java))
    }

    @Test
    fun `same key with different request fails with conflict`() {
        val existing = storedRecord(
            request = TestRequest("rice"),
            response = TestResponse("created"),
            responseStatus = 201,
        )
        Mockito.`when`(repository.findByScopeAndIdempotencyKey("custom-food:create", "key-12345"))
            .thenReturn(existing)

        assertFailsWith<IdempotencyKeyConflictException> {
            service.execute(
                scope = "custom-food:create",
                ownerUserId = null,
                idempotencyKey = "key-12345",
                request = TestRequest("chicken"),
                responseType = TestResponse::class.java,
                responseStatus = 201,
            ) {
                TestResponse("created")
            }
        }
    }

    @Test
    fun `invalid key format is rejected`() {
        assertFailsWith<InvalidIdempotencyKeyException> {
            service.execute(
                scope = "custom-food:create",
                ownerUserId = null,
                idempotencyKey = "bad key",
                request = TestRequest("rice"),
                responseType = TestResponse::class.java,
                responseStatus = 201,
            ) {
                TestResponse("created")
            }
        }
    }

    private fun storedRecord(
        request: TestRequest,
        response: TestResponse,
        responseStatus: Int,
    ): IdempotencyKey {
        service.execute(
            scope = "custom-food:create",
            ownerUserId = null,
            idempotencyKey = "seed-12345",
            request = request,
            responseType = TestResponse::class.java,
            responseStatus = responseStatus,
        ) {
            response
        }

        val captor = ArgumentCaptor.forClass(IdempotencyKey::class.java)
        Mockito.verify(repository).save(captor.capture())
        Mockito.clearInvocations(repository)
        return captor.value.copyWithoutKey()
    }

    private fun IdempotencyKey.copyWithoutKey(): IdempotencyKey {
        return IdempotencyKey(
            scope = scope,
            idempotencyKey = "key-12345",
            requestHash = requestHash,
            responseStatus = responseStatus,
            responseBody = responseBody,
            createdAt = createdAt,
            expiresAt = expiresAt,
        )
    }

    data class TestRequest(val name: String)

    data class TestResponse(val status: String)
}
