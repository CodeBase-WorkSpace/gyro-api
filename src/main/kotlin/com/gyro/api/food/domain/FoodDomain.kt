package com.gyro.api.food.domain

import com.gyro.api.auth.domain.GyroUser
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.*

enum class FoodImportStatus {
    STARTED,
    COMPLETED,
    FAILED,
    SKIPPED,
}

enum class FoodSource {
    USDA_FDC,
    USER_CURATED,
    GYRO_CURATED,
}

enum class FoodType {
    SYSTEM,
    CUSTOM,
}

enum class FoodDataQuality {
    FOUNDATION,
    SR_LEGACY,
    USER_SUBMITTED,
    CURATED,
    UNREVIEWED,
}

enum class FoodCurationStatus {
    REVIEWED,
    UNREVIEWED,
    HIDDEN,
}

enum class ServingUnitType {
    MASS,
    VOLUME,
    COUNT,
    HOUSEHOLD,
}

enum class FoodLocale {
    en,
    fa,
}

enum class FoodAliasSource {
    USDA,
    USER,
    GYRO_CURATED,
    IMPORT,
}

enum class FoodReviewStatus {
    UNREVIEWED,
    REVIEWED,
    REJECTED,
}

enum class FoodSearchTermKind {
    NAME,
    ALIAS,
    CATEGORY,
    BRAND,
}

@Entity
@Table(name = "food_import_batches")
class FoodImportBatch(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @Column(nullable = false, length = 50)
    var source: String,

    @Column(name = "source_file_name", nullable = false)
    var sourceFileName: String,

    @Column(name = "source_version", length = 100)
    var sourceVersion: String? = null,

    @Column(name = "file_checksum", nullable = false, length = 128)
    var fileChecksum: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: FoodImportStatus = FoodImportStatus.STARTED,

    @Column(name = "imported_count", nullable = false)
    var importedCount: Int = 0,

    @Column(name = "skipped_count", nullable = false)
    var skippedCount: Int = 0,

    @Column(name = "error_count", nullable = false)
    var errorCount: Int = 0,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "error_message", columnDefinition = "text")
    var errorMessage: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "food_categories")
class FoodCategory(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @Column(nullable = false, length = 50)
    var source: String,

    @Column(name = "source_category_id", length = 100)
    var sourceCategoryId: String? = null,

    @Column(nullable = false)
    var name: String,

    @Column(name = "normalized_name", nullable = false)
    var normalizedName: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "food_category_aliases")
class FoodCategoryAlias(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    var category: FoodCategory,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var locale: FoodLocale,

    @Column(nullable = false)
    var alias: String,

    @Column(name = "normalized_alias", nullable = false)
    var normalizedAlias: String,

    @Column(name = "is_primary", nullable = false)
    var isPrimary: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    var reviewStatus: FoodReviewStatus = FoodReviewStatus.REVIEWED,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "food_category_localizations")
class FoodCategoryLocalization(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    var category: FoodCategory,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var locale: FoodLocale,

    @Column(name = "display_name", nullable = false)
    var displayName: String,

    @Column(name = "normalized_display_name", nullable = false)
    var normalizedDisplayName: String,

    @Column(nullable = false, length = 50)
    var source: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    var reviewStatus: FoodReviewStatus = FoodReviewStatus.UNREVIEWED,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "serving_units")
class ServingUnit(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @Column(nullable = false, unique = true, length = 50)
    var code: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_type", nullable = false)
    var unitType: ServingUnitType,

    @Column(name = "gram_multiplier", precision = 12, scale = 6)
    var gramMultiplier: BigDecimal? = null,

    @Column(name = "milliliter_multiplier", precision = 12, scale = 6)
    var milliliterMultiplier: BigDecimal? = null,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "serving_unit_aliases")
class ServingUnitAlias(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "serving_unit_id", nullable = false)
    var servingUnit: ServingUnit,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var locale: FoodLocale,

    @Column(nullable = false, length = 100)
    var alias: String,

    @Column(name = "normalized_alias", nullable = false, length = 100)
    var normalizedAlias: String,

    @Column(name = "is_primary", nullable = false)
    var isPrimary: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "serving_unit_localizations")
class ServingUnitLocalization(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "serving_unit_id", nullable = false)
    var servingUnit: ServingUnit,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var locale: FoodLocale,

    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,

    @Column(name = "normalized_display_name", nullable = false, length = 100)
    var normalizedDisplayName: String,

    @Column(nullable = false, length = 50)
    var source: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    var reviewStatus: FoodReviewStatus = FoodReviewStatus.UNREVIEWED,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "foods")
class Food(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @Column(name = "public_id", nullable = false, unique = true, length = 80)
    var publicId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    var ownerUser: GyroUser? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: FoodType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var source: FoodSource,

    @Column(name = "source_food_id", length = 100)
    var sourceFoodId: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    var category: FoodCategory? = null,

    @Column(nullable = false)
    var name: String,

    @Column(name = "normalized_name", nullable = false)
    var normalizedName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "data_quality", nullable = false)
    var dataQuality: FoodDataQuality = FoodDataQuality.UNREVIEWED,

    @Enumerated(EnumType.STRING)
    @Column(name = "curation_status", nullable = false)
    var curationStatus: FoodCurationStatus = FoodCurationStatus.UNREVIEWED,

    @Column(name = "is_searchable", nullable = false)
    var isSearchable: Boolean = true,

    @Column(name = "brand_name")
    var brandName: String? = null,

    @Column(name = "normalized_brand_name")
    var normalizedBrandName: String? = null,

    @Version
    @Column(name = "lock_version", nullable = false)
    var lockVersion: Int = 0,

    @Column(name = "archived_at")
    var archivedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "food_localizations")
class FoodLocalization(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_id", nullable = false)
    var food: Food,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var locale: FoodLocale,

    @Column(name = "display_name", nullable = false)
    var displayName: String,

    @Column(name = "normalized_display_name", nullable = false)
    var normalizedDisplayName: String,

    @Column(nullable = false, length = 50)
    var source: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    var reviewStatus: FoodReviewStatus = FoodReviewStatus.UNREVIEWED,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "food_nutrition_facts")
class FoodNutritionFacts(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_id", nullable = false)
    var food: Food,

    @Column(name = "base_quantity", nullable = false, precision = 12, scale = 4)
    var baseQuantity: BigDecimal,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "base_unit_id", nullable = false)
    var baseUnit: ServingUnit,

    @Column(nullable = false, precision = 10, scale = 2)
    var calories: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 10, scale = 2)
    var protein: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 10, scale = 2)
    var carbs: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 10, scale = 2)
    var fat: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 10, scale = 2)
    var fiber: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 10, scale = 2)
    var sugar: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 10, scale = 2)
    var sodium: BigDecimal = BigDecimal.ZERO,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "food_serving_portions")
class FoodServingPortion(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_id", nullable = false)
    var food: Food,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "serving_unit_id")
    var servingUnit: ServingUnit? = null,

    @Column(nullable = false, precision = 12, scale = 4)
    var amount: BigDecimal,

    @Column(name = "gram_weight", precision = 12, scale = 4)
    var gramWeight: BigDecimal? = null,

    @Column(name = "raw_unit_name")
    var rawUnitName: String? = null,

    @Column
    var modifier: String? = null,

    @Column(name = "portion_description")
    var portionDescription: String? = null,

    @Column(name = "source_portion_id", length = 100)
    var sourcePortionId: String? = null,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "food_aliases")
class FoodAlias(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_id", nullable = false)
    var food: Food,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var locale: FoodLocale,

    @Column(nullable = false)
    var alias: String,

    @Column(name = "normalized_alias", nullable = false)
    var normalizedAlias: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var source: FoodAliasSource,

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    var reviewStatus: FoodReviewStatus = FoodReviewStatus.UNREVIEWED,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "food_source_metadata")
class FoodSourceMetadata(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_id", nullable = false)
    var food: Food,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_batch_id")
    var importBatch: FoodImportBatch? = null,

    @Column(nullable = false, length = 50)
    var source: String,

    @Column(name = "source_food_id", nullable = false, length = 100)
    var sourceFoodId: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var payload: MutableMap<String, Any?> = mutableMapOf(),

    @Column(name = "imported_at", nullable = false)
    var importedAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "food_search_terms")
class FoodSearchTerm(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_id", nullable = false)
    var food: Food,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var locale: FoodLocale,

    @Column(nullable = false)
    var term: String,

    @Column(name = "normalized_term", nullable = false)
    var normalizedTerm: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "term_kind", nullable = false)
    var termKind: FoodSearchTermKind,

    @Column(nullable = false, precision = 8, scale = 3)
    var weight: BigDecimal = BigDecimal.ONE,

    @Column(name = "search_vector", nullable = false, insertable = false, updatable = false, columnDefinition = "tsvector")
    var searchVector: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "food_favorites")
class FoodFavorite(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: GyroUser,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_id", nullable = false)
    var food: Food,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "recent_foods")
class RecentFood(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: GyroUser,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_id", nullable = false)
    var food: Food,

    @Column(name = "last_used_at", nullable = false)
    var lastUsedAt: Instant = Instant.now(),

    @Column(name = "use_count", nullable = false)
    var useCount: Int = 1,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun markUpdated() {
        updatedAt = Instant.now()
    }
}
