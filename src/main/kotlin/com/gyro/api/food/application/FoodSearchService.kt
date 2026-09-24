package com.gyro.api.food.application

import com.gyro.api.food.infrastructure.FoodSearchNormalizer
import com.gyro.api.food.infrastructure.FoodSearchRepository
import com.gyro.api.food.web.dto.FoodSearchResponse
import com.gyro.api.food.web.dto.FoodSearchType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class FoodSearchService(
    private val foodSearchNormalizer: FoodSearchNormalizer,
    private val foodSearchRepository: FoodSearchRepository,
) {
    @Transactional(readOnly = true)
    fun search(
        currentUserId: UUID,
        query: String?,
        locale: String?,
        type: FoodSearchType?,
        favorite: Boolean?,
        recent: Boolean?,
        page: Int,
        size: Int,
    ): FoodSearchResponse {
        return foodSearchRepository.search(
            currentUserId = currentUserId,
            normalizedQuery = foodSearchNormalizer.normalizeSearchQuery(query),
            requestedLocale = locale?.toFoodSearchLocale(),
            type = type,
            favorite = favorite,
            recent = recent,
            page = page,
            size = size,
        )
    }

    private fun String?.toFoodSearchLocale(): String? {
        val normalized = this?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null

        return when {
            normalized == "fa" || normalized.startsWith("fa-") -> "fa"
            normalized == "en" || normalized.startsWith("en-") -> "en"
            else -> normalized
        }
    }
}
