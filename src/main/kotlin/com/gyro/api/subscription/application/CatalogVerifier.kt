package com.gyro.api.subscription.application

import com.gyro.api.subscription.infrastructure.*
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class CatalogVerifier(
    private val planRepository: SubscriptionPlanRepository,
    private val priceRepository: SubscriptionPriceRepository,
    private val featureRepository: PlanFeatureRepository,
    private val localizationRepository: PlanLocalizationRepository,
    private val subscriptionFeatureRepository: SubscriptionFeatureRepository,
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
    @Value("\${app.subscription.catalog-verify-enabled:true}")
    private val enabled: Boolean,
    @Value("\${spring.profiles.active:default}")
    private val environment: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun verify() {
        if (!enabled) {
            recordOutcome("disabled")
            log.info(
                "event=subscription_catalog_verification outcome=disabled environment={}",
                environment,
            )
            return
        }
        log.info(
            "event=subscription_catalog_verification outcome=started environment={}",
            environment,
        )
        val plans = planRepository.findAll()
        if (plans.isEmpty()) {
            log.error("Subscription catalog has no plans — seed data may be missing")
            throw IllegalStateException("Subscription catalog has no plans")
        }
        val errors = mutableListOf<String>()

        // 1. Every non-FREE plan must have at least one active price
        for (plan in plans) {
            if (!plan.free) {
                val prices = priceRepository.findActiveByPlanId(plan.id!!)
                if (prices.isEmpty()) {
                    errors.add("Plan '${plan.code}' (id=${plan.id}) has no active prices")
                }
            }
        }

        // 2. Verify no duplicate active price for same (planId, billingPeriodDays, currency)
        for (plan in plans) {
            val prices = priceRepository.findActiveByPlanId(plan.id!!)
            val grouped = prices.groupBy { "${it.billingPeriodDays}_${it.price.currency}" }
            for ((key, group) in grouped) {
                if (group.size > 1) {
                    errors.add("Plan '${plan.code}' has ${group.size} active prices for $key")
                }
            }
        }

        // 3. ADVANCED plan must have all required feature keys
        val requiredFeatures = setOf(
            "premium_schedules",
            "advanced_analytics",
            "data_export",
            "future_meal_planning",
            "higher_limits",
            "goal_recalibration",
        )
        val activeFeatures = subscriptionFeatureRepository.findAll().filter { it.active }
        val activeFeatureKeys = activeFeatures.map { it.key }.toSet()
        val advancedPlan = plans.find { it.code == "ADVANCED" }
        if (advancedPlan != null) {
            val planFeatures = featureRepository.findActiveFeatureMappingsByPlanId(advancedPlan.id!!)
                .filter { it.enabled }
            val planFeatureKeys = planFeatures.map { it.featureKey }.toSet()
            for (key in requiredFeatures) {
                if (key !in activeFeatureKeys) {
                    errors.add("Required feature key is inactive or missing: $key")
                }
                if (key !in planFeatureKeys) {
                    errors.add("ADVANCED plan is missing active required feature key: $key")
                }
            }
        }

        // 4. Required locale fa-IR must exist for every plan
        for (plan in plans) {
            val hasFaIr = localizationRepository.findByPlanIdAndLocale(plan.id!!, "fa-IR").isPresent
            if (!hasFaIr) {
                errors.add("Plan '${plan.code}' is missing fa-IR localization")
            }
        }

        // 5. Every active SubscriptionFeature must be referenced by at least one plan_feature
        val allPlanFeatures = featureRepository.findAll().filter { it.enabled }
        val referencedFeatureKeys = allPlanFeatures.map { it.featureKey }.toSet()
        for (feature in activeFeatures) {
            if (feature.key !in referencedFeatureKeys) {
                errors.add("Active feature '${feature.key}' is not mapped to any plan")
            }
        }

        // 6. Every subscription_prices.planId must reference an active plan
        for (plan in plans) {
            if (!plan.active) {
                val prices = priceRepository.findActiveByPlanId(plan.id!!)
                if (prices.isNotEmpty()) {
                    errors.add("Inactive plan '${plan.code}' has ${prices.size} active prices")
                }
            }
        }

        if (errors.isNotEmpty()) {
            val message = "Subscription catalog verification failed:\n${errors.joinToString("\n")}"
            recordOutcome("failure")
            log.error(
                "event=subscription_catalog_verification outcome=failure environment={} error_count={} errors={}",
                environment,
                errors.size,
                errors.joinToString("|"),
            )
            throw IllegalStateException(message)
        }

        recordOutcome("success")
        log.info(
            "event=subscription_catalog_verification outcome=success environment={} plan_count={} feature_count={}",
            environment,
            plans.size,
            activeFeatures.size,
        )
    }

    private fun recordOutcome(outcome: String) {
        val meterRegistry = meterRegistryProvider.ifAvailable ?: return
        Counter.builder("gyro.subscription.catalog.verification")
            .description("Subscription catalog verifier executions")
            .tag("environment", environment)
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment()
    }
}
