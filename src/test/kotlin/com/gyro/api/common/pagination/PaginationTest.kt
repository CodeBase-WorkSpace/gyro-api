package com.gyro.api.common.pagination

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaginationTest {
    @Test
    fun `normalize uses defaults when page and size are missing`() {
        val request = Pagination.normalize(page = null, size = null)

        assertEquals(0, request.page)
        assertEquals(20, request.size)
    }

    @Test
    fun `normalize accepts valid page and size`() {
        val request = Pagination.normalize(page = 2, size = 50)

        assertEquals(2, request.page)
        assertEquals(50, request.size)
    }

    @Test
    fun `normalize rejects negative pages`() {
        assertFailsWith<IllegalArgumentException> {
            Pagination.normalize(page = -1, size = 20)
        }
    }

    @Test
    fun `normalize rejects sizes over the maximum`() {
        assertFailsWith<IllegalArgumentException> {
            Pagination.normalize(page = 0, size = 51)
        }
    }
}
