package com.gyro.api.diary.application

import com.gyro.api.common.date.DateAccessPolicy
import com.gyro.api.common.error.InvalidDiaryEntryException
import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.common.event.UserDashboardChangeSource
import com.gyro.api.common.event.UserDashboardDataChangedPublisher
import com.gyro.api.common.id.UuidParser
import com.gyro.api.diary.infrastructure.DiaryDayRecord
import com.gyro.api.diary.infrastructure.DiaryEntryRecord
import com.gyro.api.diary.infrastructure.DiaryEntrySnapshot
import com.gyro.api.diary.infrastructure.DiaryRepository
import com.gyro.api.diary.web.dto.*
import com.gyro.api.food.application.MealService
import com.gyro.api.food.infrastructure.MealFoodNutritionRecord
import com.gyro.api.food.infrastructure.MealRepository
import com.gyro.api.food.infrastructure.MealServingUnitRecord
import com.gyro.api.food.infrastructure.PreparedMealItem
import com.gyro.api.goal.application.nutrition_plan.NutritionPlanService
import com.gyro.api.user.config.UserPreferencesProperties
import com.gyro.api.user.infrastructure.UserProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.*

@Service
class DiaryService(
    private val diaryRepository: DiaryRepository,
    private val mealRepository: MealRepository,
    private val mealService: MealService,
    private val userProfileRepository: UserProfileRepository,
    private val userPreferencesProperties: UserPreferencesProperties,
    private val nutritionPlanService: NutritionPlanService,
    private val dateAccessPolicy: DateAccessPolicy,
    private val dashboardDataChangedPublisher: UserDashboardDataChangedPublisher,
) {
    @Transactional
    fun getDay(userId: UUID, date: LocalDate): DiaryDayResponse {
        val day = getOrCreateDay(userId, date)
        return dayResponse(userId, day)
    }

    @Transactional
    fun createEntry(
        userId: UUID,
        date: LocalDate,
        request: DiaryEntryRequest,
    ): DiaryDayResponse {
        dateAccessPolicy.assertWritable(userId, date)
        val snapshot = snapshotFor(userId, request)
        val day = getOrCreateDay(userId, date)
        diaryRepository.createEntry(
            userId = userId,
            day = day,
            snapshot = snapshot,
        )
        dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.DIARY)
        return dayResponse(userId, day)
    }

    @Transactional
    fun createEntriesBatch(
        userId: UUID,
        date: LocalDate,
        request: BatchDiaryEntryRequest,
    ): DiaryDayResponse {
        dateAccessPolicy.assertWritable(userId, date)
        val locale = userFoodLocale(userId)
        val unitIds = request.entries.map { entry ->
            UuidParser.parse(entry.servingUnitId.trim())
                ?: throw InvalidDiaryEntryException("servingUnitId is invalid.")
        }
        val unitsById = mealRepository.findServingUnitsById(unitIds.toSet())
        if (unitsById.size != unitIds.toSet().size) {
            throw InvalidDiaryEntryException("servingUnitId is invalid.")
        }

        val sourceIds = request.entries.map { it.sourceId.trim() }
        val parsedSourceIds = sourceIds.map(UuidParser::parse)
        val nutritionById = mealRepository.findVisibleFoodNutrition(
            ownerUserId = userId,
            foodIds = parsedSourceIds.filterNotNull().toSet(),
            locale = locale,
        )
        val nutritionByPublicId = mealRepository.findVisibleFoodNutritionByPublicIds(
            ownerUserId = userId,
            publicIds = sourceIds.zip(parsedSourceIds)
                .filter { (_, parsedId) -> parsedId == null }
                .map { (sourceId, _) -> sourceId }
                .toSet(),
            locale = locale,
        )
        val resolvedNutrition = sourceIds.mapIndexed { index, sourceId ->
            parsedSourceIds[index]?.let(nutritionById::get) ?: nutritionByPublicId[sourceId]
        }
        if (resolvedNutrition.any { it == null }) {
            throw InvalidDiaryEntryException("Food was not found.")
        }

        val snapshots = request.entries.mapIndexed { index, entry ->
            resolvedFoodSnapshot(
                mealType = request.mealType,
                servingQuantity = entry.quantity,
                selectedUnit = unitsById.getValue(unitIds[index]),
                nutrition = requireNotNull(resolvedNutrition[index]),
            )
        }
        val entriesToCreate = request.quickPlate?.let { quickPlate ->
            val meal = mealService.createLimitedMeal(
                ownerUserId = userId,
                name = quickPlate.name.trim(),
                items = request.entries.mapIndexed { index, entry ->
                    PreparedMealItem(
                        foodId = requireNotNull(resolvedNutrition[index]).foodId,
                        quantity = entry.quantity.scaledQuantity(),
                        servingUnit = unitsById.getValue(unitIds[index]),
                    )
                },
            )
            listOf(quickPlateSnapshot(request.mealType, meal.mealId, quickPlate.name.trim(), snapshots))
        } ?: snapshots

        val day = getOrCreateDay(userId, date)
        diaryRepository.createEntries(userId, day, entriesToCreate)
        mealRepository.markFoodsRecent(userId, snapshots.mapNotNull { it.sourceFoodId }.toSet())
        dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.DIARY)
        return dayResponse(userId, day)
    }

    private fun quickPlateSnapshot(
        mealType: String,
        mealId: UUID,
        name: String,
        foodSnapshots: List<DiaryEntrySnapshot>,
    ): DiaryEntrySnapshot {
        val nutrition = foodSnapshots.map {
            NutritionValues(it.calories, it.protein, it.carbs, it.fat, it.fiber, it.sugar, it.sodium)
        }.sumNutrition()
        return DiaryEntrySnapshot(
            mealType = mealType,
            sourceType = DiarySourceType.MEAL.name,
            sourceFoodId = null,
            sourceMealId = mealId,
            displayName = name,
            servingQuantity = BigDecimal.ONE,
            servingUnitId = null,
            servingUnitCode = "SERVING",
            servingUnitName = "Serving",
            calories = nutrition.calories,
            protein = nutrition.protein,
            carbs = nutrition.carbs,
            fat = nutrition.fat,
            fiber = nutrition.fiber,
            sugar = nutrition.sugar,
            sodium = nutrition.sodium,
        )
    }

    @Transactional
    fun updateEntry(
        userId: UUID,
        date: LocalDate,
        entryId: String,
        request: DiaryEntryRequest,
    ): DiaryDayResponse {
        dateAccessPolicy.assertWritable(userId, date)
        val day = findDay(userId, date) ?: throw ResourceNotFoundException("Diary entry")
        val parsedEntryId = UuidParser.parse(entryId.trim()) ?: throw ResourceNotFoundException("Diary entry")
        if (!diaryRepository.updateEntry(userId, day, parsedEntryId, snapshotFor(userId, request))) {
            throw ResourceNotFoundException("Diary entry")
        }
        dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.DIARY)
        return dayResponse(userId, day)
    }

    @Transactional
    fun deleteEntry(
        userId: UUID,
        date: LocalDate,
        entryId: String,
    ): DiaryDayResponse {
        val day = findDay(userId, date) ?: throw ResourceNotFoundException("Diary entry")
        val parsedEntryId = UuidParser.parse(entryId.trim()) ?: throw ResourceNotFoundException("Diary entry")
        if (!diaryRepository.deleteEntry(userId, day, parsedEntryId)) {
            throw ResourceNotFoundException("Diary entry")
        }
        dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.DIARY)
        return dayResponse(userId, day)
    }

    @Transactional
    fun copyFromDate(
        userId: UUID,
        targetDate: LocalDate,
        sourceDate: LocalDate,
    ): DiaryDayResponse {
        dateAccessPolicy.assertWritable(userId, targetDate)
        if (targetDate == sourceDate) {
            throw InvalidDiaryEntryException("targetDate and sourceDate must be different.")
        }

        val sourceDay = findDay(userId, sourceDate)
            ?: throw ResourceNotFoundException("Source diary day")
        val sourceEntries = diaryRepository.findEntries(userId, sourceDay)
        if (sourceEntries.isEmpty()) {
            throw ResourceNotFoundException("Source diary entries")
        }

        val targetDay = getOrCreateDay(userId, targetDate)
        diaryRepository.copyEntries(
            userId = userId,
            sourceEntries = sourceEntries,
            targetDay = targetDay,
        )

        dashboardDataChangedPublisher.publish(userId, UserDashboardChangeSource.DIARY)
        return dayResponse(userId, targetDay)
    }


    private fun getOrCreateDay(userId: UUID, date: LocalDate) = diaryRepository.getOrCreateDay(
        userId = userId,
        date = date,
        timezone = userProfileRepository.findByUser_Id(userId)?.timezone
            ?: userPreferencesProperties.normalizedDefaultTimezone,
    )

    private fun findDay(userId: UUID, date: LocalDate) = diaryRepository.findDay(
        userId = userId,
        date = date,
    )

    private fun dayResponse(userId: UUID, day: DiaryDayRecord): DiaryDayResponse {
        val entries = diaryRepository.findEntries(userId, day)
        val activePlan = nutritionPlanService.findActivePlan(userId, day.date)
        val dailyTarget = activePlan?.dailyTarget
        val goal = DiaryGoalSummaryResponse(
            configured = dailyTarget != null,
            calories = dailyTarget?.calories?.scaled(),
            protein = dailyTarget?.protein?.scaled(),
            carbs = dailyTarget?.carbs?.scaled(),
            fat = dailyTarget?.fat?.scaled(),
            targetSource = dailyTarget?.planScheduleSummary?.source?.name,
        )
        val totals = entries.toNutritionSummary()

        return DiaryDayResponse(
            date = day.date.toString(),
            timezone = day.timezone,
            canWriteDiary = dateAccessPolicy.canWrite(userId, day.date),
            goal = goal,
            totals = totals,
            remainingCalories = DiaryRemainingCaloriesResponse(
                configured = goal.configured,
                value = goal.calories?.subtract(totals.calories)?.scaled(),
            ),
            macroProgress = DiaryMacroProgressResponse(
                configured = goal.configured,
                protein = nutrientProgress(totals.protein, goal.protein),
                carbs = nutrientProgress(totals.carbs, goal.carbs),
                fat = nutrientProgress(totals.fat, goal.fat),
            ),
            mealGroups = MealType.entries.map { mealType ->
                val groupEntries = entries.filter { it.mealType == mealType.name }
                DiaryMealGroupResponse(
                    mealType = mealType.name,
                    entries = groupEntries.map { it.toResponse() },
                    totals = groupEntries.toNutritionSummary(),
                )
            },
            warnings = if (goal.configured) {
                emptyList()
            } else {
                listOf(
                    DiaryWarningResponse(
                        code = "GOALS_NOT_CONFIGURED",
                        message = "Daily nutrition goals are not configured.",
                    ),
                )
            },
        )
    }

    private fun snapshotFor(userId: UUID, request: DiaryEntryRequest): DiaryEntrySnapshot {
        return when (request.sourceType) {
            DiarySourceType.FOOD.name -> foodSnapshot(userId, request)
            DiarySourceType.MEAL.name -> mealSnapshot(userId, request)
            DiarySourceType.MANUAL.name -> manualSnapshot(request)
            else -> throw InvalidDiaryEntryException("sourceType is invalid.")
        }
    }

    private fun foodSnapshot(userId: UUID, request: DiaryEntryRequest): DiaryEntrySnapshot {
        val sourceFoodId = request.sourceFoodId?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidDiaryEntryException("sourceFoodId is required.")
        if (request.sourceMealId != null || request.manualNutrition != null || request.displayName != null) {
            throw InvalidDiaryEntryException("meal and manual fields must be omitted for food entries.")
        }
        val unitCode = request.servingUnit?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: throw InvalidDiaryEntryException("servingUnit is required for food entries.")
        val selectedUnit = mealRepository.findServingUnitsByCode(setOf(unitCode))[unitCode]
            ?: throw InvalidDiaryEntryException("servingUnit is invalid.")
        val parsedFoodId = UuidParser.parse(sourceFoodId)
        val locale = userFoodLocale(userId)
        val nutrition = if (parsedFoodId != null) {
            mealRepository.findVisibleFoodNutrition(userId, setOf(parsedFoodId), locale)[parsedFoodId]
        } else {
            mealRepository.findVisibleFoodNutritionByPublicId(userId, sourceFoodId, locale)
        }
            ?: throw InvalidDiaryEntryException("Food was not found.")
        return resolvedFoodSnapshot(
            mealType = request.mealType,
            servingQuantity = request.servingQuantity,
            selectedUnit = selectedUnit,
            nutrition = nutrition,
        )
    }

    private fun resolvedFoodSnapshot(
        mealType: String,
        servingQuantity: BigDecimal,
        selectedUnit: MealServingUnitRecord,
        nutrition: MealFoodNutritionRecord,
    ): DiaryEntrySnapshot {
        val scaledQuantity = servingQuantity.scaledQuantity()
        val nutrientValues = nutritionFor(
            quantity = scaledQuantity,
            selectedUnit = selectedUnit,
            nutrition = nutrition,
        )

        return DiaryEntrySnapshot(
            mealType = mealType,
            sourceType = DiarySourceType.FOOD.name,
            sourceFoodId = nutrition.foodId,
            sourceMealId = null,
            displayName = nutrition.foodName,
            servingQuantity = scaledQuantity,
            servingUnitId = selectedUnit.id,
            servingUnitCode = selectedUnit.code,
            servingUnitName = selectedUnit.code,
            calories = nutrientValues.calories,
            protein = nutrientValues.protein,
            carbs = nutrientValues.carbs,
            fat = nutrientValues.fat,
            fiber = nutrientValues.fiber,
            sugar = nutrientValues.sugar,
            sodium = nutrientValues.sodium,
        )
    }

    private fun mealSnapshot(userId: UUID, request: DiaryEntryRequest): DiaryEntrySnapshot {
        val mealId = request.sourceMealId?.trim()?.let(UuidParser::parse)
            ?: throw InvalidDiaryEntryException("sourceMealId must be a valid UUID.")
        if (request.sourceFoodId != null || request.servingUnit != null || request.manualNutrition != null || request.displayName != null) {
            throw InvalidDiaryEntryException("food and manual fields must be omitted for meal entries.")
        }
        val meal = mealRepository.findOwnerMeal(userId, mealId)
            ?: throw InvalidDiaryEntryException("Meal was not found.")
        val items = mealRepository.findMealNutritionItems(userId, setOf(mealId))[mealId].orEmpty()
        if (items.isEmpty()) {
            throw InvalidDiaryEntryException("Meal has no available items.")
        }
        val servingQuantity = request.servingQuantity.scaledQuantity()
        // With a serving definition, quantity means "number of servings" and each serving is a
        // weight fraction of the whole batch; without one it means "number of whole recipes".
        val itemFactor = meal.servingDefinition?.batchFraction(servingQuantity) ?: servingQuantity
        val nutrientValues = items.map { item ->
            nutritionFor(
                quantity = item.quantity.multiply(itemFactor),
                selectedUnit = item.selectedUnit,
                nutrition = item.foodNutrition,
            )
        }.sumNutrition()

        return DiaryEntrySnapshot(
            mealType = request.mealType,
            sourceType = DiarySourceType.MEAL.name,
            sourceFoodId = null,
            sourceMealId = mealId,
            displayName = meal.name,
            servingQuantity = servingQuantity,
            servingUnitId = null,
            servingUnitCode = "SERVING",
            servingUnitName = "Serving",
            calories = nutrientValues.calories,
            protein = nutrientValues.protein,
            carbs = nutrientValues.carbs,
            fat = nutrientValues.fat,
            fiber = nutrientValues.fiber,
            sugar = nutrientValues.sugar,
            sodium = nutrientValues.sodium,
        )
    }

    private fun manualSnapshot(request: DiaryEntryRequest): DiaryEntrySnapshot {
        if (request.sourceFoodId != null || request.sourceMealId != null) {
            throw InvalidDiaryEntryException("source ids must be omitted for manual entries.")
        }
        val displayName = request.displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: throw InvalidDiaryEntryException("displayName is required for manual entries.")
        val unitCode = request.servingUnit?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
            ?: throw InvalidDiaryEntryException("servingUnit is required for manual entries.")
        val nutrition = request.manualNutrition
            ?: throw InvalidDiaryEntryException("manualNutrition is required for manual entries.")

        return DiaryEntrySnapshot(
            mealType = request.mealType,
            sourceType = DiarySourceType.MANUAL.name,
            sourceFoodId = null,
            sourceMealId = null,
            displayName = displayName,
            servingQuantity = request.servingQuantity.scaledQuantity(),
            servingUnitId = null,
            servingUnitCode = unitCode,
            servingUnitName = unitCode,
            calories = nutrition.calories.scaled(),
            protein = nutrition.protein.scaled(),
            carbs = nutrition.carbs.scaled(),
            fat = nutrition.fat.scaled(),
            fiber = nutrition.fiber.scaled(),
            sugar = nutrition.sugar.scaled(),
            sodium = nutrition.sodium.scaled(),
        )
    }

    private fun userFoodLocale(userId: UUID): String {
        val locale = userProfileRepository.findByUser_Id(userId)?.locale
            ?: userPreferencesProperties.normalizedDefaultLocale
        val normalized = locale.trim().lowercase(Locale.ROOT)

        return when {
            normalized == "fa" || normalized.startsWith("fa-") -> "fa"
            normalized == "en" || normalized.startsWith("en-") -> "en"
            else -> normalized.ifBlank { "en" }
        }
    }

    private fun nutritionFor(
        quantity: BigDecimal,
        selectedUnit: MealServingUnitRecord,
        nutrition: MealFoodNutritionRecord,
    ): NutritionValues {
        val selectedQuantityInBaseUnit = when {
            selectedUnit.id == nutrition.baseUnit.id -> quantity
            selectedUnit.gramMultiplier != null && nutrition.baseUnit.gramMultiplier != null -> {
                quantity.multiply(selectedUnit.gramMultiplier)
                    .divide(nutrition.baseUnit.gramMultiplier, 8, RoundingMode.HALF_UP)
            }

            selectedUnit.milliliterMultiplier != null && nutrition.baseUnit.milliliterMultiplier != null -> {
                quantity.multiply(selectedUnit.milliliterMultiplier)
                    .divide(nutrition.baseUnit.milliliterMultiplier, 8, RoundingMode.HALF_UP)
            }

            else -> throw InvalidDiaryEntryException("servingUnit is incompatible with food base unit.")
        }
        val ratio = selectedQuantityInBaseUnit.divide(nutrition.baseQuantity, 8, RoundingMode.HALF_UP)

        return NutritionValues(
            calories = nutrition.calories.multiply(ratio).scaled(),
            protein = nutrition.protein.multiply(ratio).scaled(),
            carbs = nutrition.carbs.multiply(ratio).scaled(),
            fat = nutrition.fat.multiply(ratio).scaled(),
            fiber = nutrition.fiber.multiply(ratio).scaled(),
            sugar = nutrition.sugar.multiply(ratio).scaled(),
            sodium = nutrition.sodium.multiply(ratio).scaled(),
        )
    }

    @Transactional
    fun repeatEntry(
        userId: UUID,
        targetDate: LocalDate,
        entryId: String,
    ): DiaryDayResponse {
        dateAccessPolicy.assertWritable(userId, targetDate)
        val parsedEntryId = UuidParser.parse(entryId.trim())
            ?: throw ResourceNotFoundException("Diary entry")
        val sourceEntry = diaryRepository.findEntryById(
            userId = userId,
            entryId = parsedEntryId,
        ) ?: throw ResourceNotFoundException("Diary entry")

        val targetDay = getOrCreateDay(
            userId = userId,
            date = targetDate,
        )
        diaryRepository.copyEntry(
            userId = userId,
            sourceEntry = sourceEntry,
            targetDay = targetDay,
        )

        return dayResponse(
            userId = userId,
            day = targetDay,
        )
    }

}

private enum class MealType {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK,
    CUSTOM,
}

private enum class DiarySourceType {
    FOOD,
    MEAL,
    MANUAL,
}

private data class NutritionValues(
    val calories: BigDecimal,
    val protein: BigDecimal,
    val carbs: BigDecimal,
    val fat: BigDecimal,
    val fiber: BigDecimal,
    val sugar: BigDecimal,
    val sodium: BigDecimal,
)

private fun DiaryEntryRecord.toResponse(): DiaryEntryResponse {
    return DiaryEntryResponse(
        id = id.toString(),
        mealType = mealType,
        sourceType = sourceType,
        sourceFoodId = sourceFoodId?.toString(),
        sourceMealId = sourceMealId?.toString(),
        displayName = displayName,
        servingQuantity = servingQuantity,
        servingUnitCode = servingUnitCode,
        servingUnitName = servingUnitName,
        sortOrder = sortOrder,
        nutrition = listOf(this).toNutritionSummary(),
    )
}

private fun Iterable<DiaryEntryRecord>.toNutritionSummary(): DiaryNutritionSummaryResponse {
    return DiaryNutritionSummaryResponse(
        calories = sumOf { it.calories }.scaled(),
        protein = sumOf { it.protein }.scaled(),
        carbs = sumOf { it.carbs }.scaled(),
        fat = sumOf { it.fat }.scaled(),
        fiber = sumOf { it.fiber }.scaled(),
        sugar = sumOf { it.sugar }.scaled(),
        sodium = sumOf { it.sodium }.scaled(),
    )
}

private fun Iterable<NutritionValues>.sumNutrition(): NutritionValues {
    return NutritionValues(
        calories = sumOf { it.calories }.scaled(),
        protein = sumOf { it.protein }.scaled(),
        carbs = sumOf { it.carbs }.scaled(),
        fat = sumOf { it.fat }.scaled(),
        fiber = sumOf { it.fiber }.scaled(),
        sugar = sumOf { it.sugar }.scaled(),
        sodium = sumOf { it.sodium }.scaled(),
    )
}

private fun nutrientProgress(
    consumed: BigDecimal,
    target: BigDecimal?,
): DiaryNutrientProgressResponse {
    return DiaryNutrientProgressResponse(
        consumed = consumed.scaled(),
        target = target?.scaled(),
        remaining = target?.subtract(consumed)?.scaled(),
        goalPercent = consumed.toGoalPercent(target),
    )
}

private fun BigDecimal.toGoalPercent(target: BigDecimal?): BigDecimal? {
    if (target == null || target.compareTo(BigDecimal.ZERO) == 0) {
        return null
    }
    return multiply(BigDecimal("100"))
        .divide(target, 2, RoundingMode.HALF_UP)
}

private fun BigDecimal.scaled(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

private fun BigDecimal.scaledQuantity(): BigDecimal = setScale(4, RoundingMode.HALF_UP)
