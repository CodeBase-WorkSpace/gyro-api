package com.gyro.api.daily_score.application

import com.gyro.api.common.time.TimeProvider
import com.gyro.api.daily_score.infrastructure.DailyScoreRepository
import com.gyro.api.goal.application.goal_schedule.ScheduleAwareDailyTargetLoader
import com.gyro.api.user.application.UserProfileView
import com.gyro.api.user.application.UserService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class DailyScoreService(
    private val dailyScoreRepository: DailyScoreRepository,
    private val dailyScoreEngine: DailyScoreEngine,
    private val dailyTargetLoader: ScheduleAwareDailyTargetLoader,
    private val userService: UserService,
    private val timeProvider: TimeProvider,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finalizedScoresForRange(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<DailyScoreReadModel> {
        val profile = userService.getProfile(userId)
        return finalizedScoresForRange(
            userId = userId,
            from = from,
            to = to,
            profile = profile,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finalizedScoresForRange(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
        profile: UserProfileView,
    ): List<DailyScoreReadModel> {
        val finalizableRange = finalizableRange(
            from = from,
            to = to,
            profile = profile,
        ) ?: return emptyList()

        val existing = dailyScoreRepository.loadScores(
            userId = userId,
            from = finalizableRange.first(),
            to = finalizableRange.last(),
        )
        val existingByDate = existing.associateBy { it.localDate }
        val diaryUpdates = dailyScoreRepository.loadDiaryDayUpdates(
            userId = userId,
            from = finalizableRange.first(),
            to = finalizableRange.last(),
        )
        val datesToFinalize = finalizableRange.filter { date ->
            val score = existingByDate[date] ?: return@filter true
            diaryUpdates[date]?.isAfter(score.finalizedAt) == true
        }
        if (datesToFinalize.isNotEmpty()) {
            dailyScoreRepository.upsert(
                buildDrafts(
                    userId = userId,
                    dates = datesToFinalize,
                )
            )
        }

        return dailyScoreRepository.loadScores(
            userId = userId,
            from = finalizableRange.first(),
            to = finalizableRange.last(),
        )
    }

    private fun buildDrafts(
        userId: UUID,
        dates: List<LocalDate>,
    ): List<DailyScoreDraft> {
        val scoreInputs = dailyScoreRepository.loadScoreInputs(
            userId = userId,
            dates = dates,
        )
        val targets = dailyTargetLoader.load(
            userId = userId,
            dates = dates,
        ).toDailyScoreTargets()
        val now = timeProvider.now()

        return dates.map { date ->
            val baseInput = scoreInputs.getValue(date)
            val input = baseInput.copy(target = targets[date])
            val result = dailyScoreEngine.calculate(input)
            DailyScoreDraft(
                userId = userId,
                localDate = date,
                score = result.score,
                mode = result.mode,
                goalId = result.goalId,
                goalType = result.goalType,
                formulaName = DailyScoreEngine.FORMULA_NAME,
                // A changed diary day is recalculated with the current formula. Keeping the
                // formula version on the row aligned with its breakdown lets analytics split
                // mixed historical versions instead of presenting unlike scores as identical.
                formulaVersion = DailyScoreEngine.FORMULA_VERSION,
                breakdown = result.breakdown,
                finalizedAt = now,
                createdAt = now,
            )
        }
    }

    private fun Map<LocalDate, com.gyro.api.goal.application.goal_schedule.ScheduleAwareDailyTarget>.toDailyScoreTargets() =
        mapValues { (_, scheduledTarget) ->
            DailyScoreTarget(
                goalId = scheduledTarget.planId,
                goalType = scheduledTarget.goalType,
                calories = scheduledTarget.target.calories,
                protein = scheduledTarget.target.protein,
                carbs = scheduledTarget.target.carbs,
                fat = scheduledTarget.target.fat,
            )
        }

    private fun finalizableRange(
        from: LocalDate,
        to: LocalDate,
        profile: UserProfileView,
    ): List<LocalDate>? {
        if (to.isBefore(from)) {
            return null
        }
        val zoneId = ZoneId.of(profile.timezone)
        val accountCreatedDate = timeProvider.toUserDate(profile.createdAt, zoneId)
        val yesterday = timeProvider.today(zoneId).minusDays(1)
        val first = maxOf(from, accountCreatedDate)
        val last = minOf(to, yesterday)
        if (last.isBefore(first)) {
            return null
        }
        return generateSequence(first) { date ->
            date.plusDays(1).takeUnless { it.isAfter(last) }
        }.toList()
    }
}
