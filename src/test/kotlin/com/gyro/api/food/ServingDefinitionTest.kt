package com.gyro.api.food

import com.gyro.api.food.domain.ServingDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ServingDefinitionTest {

    @Test
    fun `servings per batch and batch fraction scale by weight`() {
        val definition = ServingDefinition(
            totalBatchWeight = BigDecimal("200"),
            servingWeight = BigDecimal("10"),
        )

        assertEquals(BigDecimal("20.0000"), definition.servingsPerBatch)
        assertEquals(BigDecimal("0.05000000"), definition.batchFraction(BigDecimal.ONE))
        assertEquals(BigDecimal("0.10000000"), definition.batchFraction(BigDecimal("2")))
    }

    @Test
    fun `ofNullable returns null only when both weights are absent`() {
        assertNull(ServingDefinition.ofNullable(null, null))

        val definition = ServingDefinition.ofNullable(BigDecimal("200"), BigDecimal("10"))
        assertEquals(BigDecimal("200"), definition?.totalBatchWeight)

        assertThrows(IllegalArgumentException::class.java) {
            ServingDefinition.ofNullable(BigDecimal("200"), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServingDefinition.ofNullable(null, BigDecimal("10"))
        }
    }

    @Test
    fun `rejects non positive weights and serving heavier than batch`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServingDefinition(BigDecimal.ZERO, BigDecimal("10"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServingDefinition(BigDecimal("200"), BigDecimal.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServingDefinition(BigDecimal("10"), BigDecimal("200"))
        }
    }
}
