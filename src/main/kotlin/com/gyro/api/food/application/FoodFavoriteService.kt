package com.gyro.api.food.application

import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.food.infrastructure.FoodFavoriteRepository
import com.gyro.api.food.web.dto.FoodFavoriteResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class FoodFavoriteService(
    private val foodFavoriteRepository: FoodFavoriteRepository,
) {
    @Transactional
    fun favorite(currentUserId: UUID, foodId: String): FoodFavoriteResponse {
        val normalizedFoodId = foodId.trim()
        val foodRowId = foodFavoriteRepository.findVisibleFoodRowId(
            currentUserId = currentUserId,
            foodId = normalizedFoodId,
        ) ?: throw ResourceNotFoundException("Food")

        foodFavoriteRepository.favoriteFood(
            currentUserId = currentUserId,
            foodRowId = foodRowId,
        )

        return FoodFavoriteResponse(
            foodId = normalizedFoodId,
            favorite = true,
        )
    }

    @Transactional
    fun unfavorite(currentUserId: UUID, foodId: String): FoodFavoriteResponse {
        val normalizedFoodId = foodId.trim()
        val foodRowId = foodFavoriteRepository.findVisibleFoodRowId(
            currentUserId = currentUserId,
            foodId = normalizedFoodId,
        ) ?: throw ResourceNotFoundException("Food")

        foodFavoriteRepository.unfavoriteFood(
            currentUserId = currentUserId,
            foodRowId = foodRowId,
        )

        return FoodFavoriteResponse(
            foodId = normalizedFoodId,
            favorite = false,
        )
    }
}
