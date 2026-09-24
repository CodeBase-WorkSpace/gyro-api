package com.gyro.api.common.id

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UuidParserTest {
    @Test
    fun `parse returns UUID for valid value`() {
        val id = UUID.randomUUID()

        assertEquals(id, UuidParser.parse(id.toString()))
    }

    @Test
    fun `parse returns null for invalid value`() {
        assertNull(UuidParser.parse("not-a-uuid"))
    }

    @Test
    fun `requireUuid rejects invalid value`() {
        assertFailsWith<IllegalArgumentException> {
            UuidParser.requireUuid("food_123", "foodId")
        }
    }
}
