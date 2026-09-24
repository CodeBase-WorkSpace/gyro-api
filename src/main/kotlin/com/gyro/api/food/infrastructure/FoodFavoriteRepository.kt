package com.gyro.api.food.infrastructure

import com.gyro.api.food.web.dto.FoodSearchType
import com.gyro.api.jooq.Tables.FOOD_FAVORITES
import com.gyro.api.jooq.Tables.FOODS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class FoodFavoriteRepository(
    private val dsl: DSLContext,
) {
    fun findVisibleFoodRowId(currentUserId: UUID, foodId: String): UUID? {
        return dsl.select(FOODS.ID)
            .from(FOODS)
            .where(
                FOODS.PUBLIC_ID.eq(foodId)
                    .and(FOODS.ARCHIVED_AT.isNull)
                    .and(FOODS.IS_SEARCHABLE.isTrue)
                    .and(FOODS.CURATION_STATUS.ne("HIDDEN"))
                    .and(FOODS.TYPE.eq(FoodSearchType.SYSTEM.name).or(FOODS.OWNER_USER_ID.eq(currentUserId)))
            )
            .fetchOne(FOODS.ID)
    }

    fun favoriteFood(currentUserId: UUID, foodRowId: UUID) {
        dsl.insertInto(FOOD_FAVORITES)
            .set(FOOD_FAVORITES.USER_ID, currentUserId)
            .set(FOOD_FAVORITES.FOOD_ID, foodRowId)
            .onConflict(FOOD_FAVORITES.USER_ID, FOOD_FAVORITES.FOOD_ID)
            .doNothing()
            .execute()
    }

    fun unfavoriteFood(currentUserId: UUID, foodRowId: UUID) {
        dsl.deleteFrom(FOOD_FAVORITES)
            .where(
                FOOD_FAVORITES.USER_ID.eq(currentUserId)
                    .and(FOOD_FAVORITES.FOOD_ID.eq(foodRowId))
            )
            .execute()
    }
}
