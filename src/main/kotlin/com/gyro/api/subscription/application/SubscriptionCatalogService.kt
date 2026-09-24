package com.gyro.api.subscription.application

import com.gyro.api.common.error.ResourceNotFoundException
import com.gyro.api.subscription.domain.*
import com.gyro.api.subscription.infrastructure.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

private const val DEFAULT_CATALOG_LOCALE = "fa-IR"

@Service
class SubscriptionCatalogService(
    private val planRepository: SubscriptionPlanRepository,
    private val priceRepository: SubscriptionPriceRepository,
    private val featureRepository: PlanFeatureRepository,
    private val localizationRepository: PlanLocalizationRepository,
    private val providerMappingRepository: ProviderPriceMappingRepository,
    private val subscriptionFeatureRepository: SubscriptionFeatureRepository,
) {
    @Transactional(readOnly = true)
    fun findPlanByCode(code: String): SubscriptionPlan {
        return planRepository.findByCode(code)
            ?: throw ResourceNotFoundException("SubscriptionPlan with code '$code'")
    }

    @Transactional(readOnly = true)
    fun findPlanById(id: Long): SubscriptionPlan {
        return planRepository.findById(id)
            .orElseThrow { ResourceNotFoundException("SubscriptionPlan") }
    }

    @Transactional(readOnly = true)
    fun findActivePrices(planId: Long): List<SubscriptionPrice> {
        return priceRepository.findActiveByPlanId(planId)
    }

    @Transactional(readOnly = true)
    fun findPriceById(priceId: Long): SubscriptionPrice {
        return priceRepository.findById(priceId)
            .orElseThrow { ResourceNotFoundException("SubscriptionPrice") }
    }

    @Transactional(readOnly = true)
    fun findEnabledFeatures(planId: Long): List<PlanFeature> {
        return featureRepository.findEnabledByPlanId(planId)
    }

    @Transactional(readOnly = true)
    fun findLocalization(planId: Long, locale: String): PlanLocalization {
        return localizationRepository.findByPlanIdAndLocale(planId, locale).orElseGet {
            localizationRepository.findByPlanIdAndLocale(planId, "fa-IR").orElse(null)
                ?: throw ResourceNotFoundException("PlanLocalization for plan $planId")
        }
    }

    @Transactional(readOnly = true)
    fun findProviderMapping(
        priceId: Long,
        provider: PaymentProvider,
        environment: ProviderEnvironment,
    ): ProviderPriceMapping {
        return providerMappingRepository
            .findBySubscriptionPriceIdAndProviderAndEnvironmentAndActiveTrue(priceId, provider, environment)
            .orElseThrow { ResourceNotFoundException("ProviderPriceMapping") }
    }

    @Transactional(readOnly = true)
    fun getPublicCatalog(locale: String?): SubscriptionCatalog {
        val requestedLocale = locale?.takeIf { it.isNotBlank() } ?: DEFAULT_CATALOG_LOCALE
        val featuresByKey = subscriptionFeatureRepository.findByActiveTrue().associateBy { it.key }
        val plans = planRepository.findByActiveTrueOrderByFreeDescCodeAsc().map { plan ->
            val planId = plan.id ?: throw IllegalStateException("Active subscription plan ${plan.code} has no id")
            val localization = findCatalogLocalization(plan, requestedLocale)
            val prices = priceRepository.findActiveByPlanId(planId).map { price ->
                SubscriptionCatalogPrice(
                    id = price.id ?: throw IllegalStateException("Active subscription price has no id"),
                    billingPeriodDays = price.billingPeriodDays,
                    baseAmount = price.baseAmount,
                    discountPercent = price.discountPercent,
                    amount = price.price.amount,
                    currency = price.price.currency,
                    badge = price.badge,
                )
            }
            val features = featureRepository.findActiveFeatureMappingsByPlanId(planId).map { feature ->
                SubscriptionCatalogFeature(
                    key = feature.featureKey,
                    description = featuresByKey[feature.featureKey]?.description ?: feature.featureKey,
                    enabled = feature.enabled,
                )
            }

            SubscriptionCatalogPlan(
                code = plan.code,
                name = plan.name,
                free = plan.free,
                gracePeriodDays = plan.gracePeriodDays,
                displayName = localization.displayName,
                shortDescription = localization.shortDescription,
                featureSummary = localization.featureSummary,
                locale = localization.locale,
                features = features,
                prices = prices,
            )
        }

        return SubscriptionCatalog(plans = plans)
    }

    private fun findCatalogLocalization(
        plan: SubscriptionPlan,
        requestedLocale: String,
    ): CatalogLocalization {
        val planId = plan.id ?: throw IllegalStateException("Subscription plan ${plan.code} has no id")
        val localization = localizationRepository.findByPlanIdAndLocale(planId, requestedLocale)
            .orElseGet {
                localizationRepository.findByPlanIdAndLocale(planId, DEFAULT_CATALOG_LOCALE)
                    .orElse(null)
            }

        return CatalogLocalization(
            locale = localization?.locale ?: requestedLocale,
            displayName = localization?.displayName ?: plan.name,
            shortDescription = localization?.shortDescription,
            featureSummary = localization?.featureSummary,
        )
    }
}

data class SubscriptionCatalog(
    val plans: List<SubscriptionCatalogPlan>,
)

data class SubscriptionCatalogPlan(
    val code: String,
    val name: String,
    val free: Boolean,
    val gracePeriodDays: Int,
    val displayName: String,
    val shortDescription: String?,
    val featureSummary: String?,
    val locale: String,
    val features: List<SubscriptionCatalogFeature>,
    val prices: List<SubscriptionCatalogPrice>,
)

data class SubscriptionCatalogFeature(
    val key: String,
    val description: String,
    val enabled: Boolean,
)

data class SubscriptionCatalogPrice(
    val id: Long,
    val billingPeriodDays: Int,
    val baseAmount: BigDecimal,
    val discountPercent: BigDecimal,
    val amount: BigDecimal,
    val currency: String,
    val badge: String?,
)

private data class CatalogLocalization(
    val locale: String,
    val displayName: String,
    val shortDescription: String?,
    val featureSummary: String?,
)
