package com.gyro.api.food.application

import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.food.infrastructure.FoodDetailRepository
import com.gyro.api.food.web.dto.FoodDetailResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class FoodDetailService(
    private val foodDetailRepository: FoodDetailRepository,
) {
    @Transactional(readOnly = true)
    fun getFoodDetail(
        currentUserId: UUID,
        foodId: String,
        locale: String?,
    ): FoodDetailResponse {
        return foodDetailRepository.findVisibleFoodDetail(
            currentUserId = currentUserId,
            foodId = foodId.trim(),
            requestedLocale = locale?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
        ) ?: throw ResourceNotFoundException("Food")
    }
}
