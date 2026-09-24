package com.gyro.api.common.error

import org.springframework.http.HttpStatus

sealed class DomainException(
    val status: HttpStatus,
    val code: ApiErrorCode,
    override val message: String,
    val metadata: Map<String, String> = emptyMap(),
) : RuntimeException(message)

class InvalidCredentialsException : DomainException(
    status = HttpStatus.UNAUTHORIZED,
    code = ApiErrorCode.INVALID_CREDENTIALS,
    message = "Invalid email, phone number, or password.",
)

class InvalidRefreshTokenException : DomainException(
    status = HttpStatus.UNAUTHORIZED,
    code = ApiErrorCode.INVALID_REFRESH_TOKEN,
    message = "Refresh token is invalid or expired.",
)

class AccountDisabledException : DomainException(
    status = HttpStatus.FORBIDDEN,
    code = ApiErrorCode.ACCOUNT_DISABLED,
    message = "This account is disabled.",
)

class AccountNotVerifiedException : DomainException(
    status = HttpStatus.FORBIDDEN,
    code = ApiErrorCode.ACCOUNT_NOT_VERIFIED,
    message = "This account must be verified before continuing.",
)

class EmailAlreadyRegisteredException : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.EMAIL_ALREADY_REGISTERED,
    message = "This email is already registered.",
)

class PhoneAlreadyRegisteredException : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.PHONE_ALREADY_REGISTERED,
    message = "This phone number is already registered.",
)

class ResourceNotFoundException(
    resourceName: String,
) : DomainException(
    status = HttpStatus.NOT_FOUND,
    code = ApiErrorCode.RESOURCE_NOT_FOUND,
    message = "$resourceName was not found.",
)

class RateLimitExceededException : DomainException(
    status = HttpStatus.TOO_MANY_REQUESTS,
    code = ApiErrorCode.RATE_LIMIT_EXCEEDED,
    message = "You have exceeded the allowed number of requests. Please try again later.",
)

class AdminAnnouncementContentConflictException(announcementId: java.util.UUID, difference: String) : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.ANNOUNCEMENT_CONTENT_CONFLICT,
    message = "Announcement $announcementId already exists with different $difference; use a new announcement id.",
)

class InvalidAdminAnnouncementException(reason: String) : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VALIDATION_ERROR,
    message = reason,
)

class AdminAnnouncementAudienceTooLargeException(size: Int, maximum: Int) : DomainException(
    status = HttpStatus.UNPROCESSABLE_ENTITY,
    code = ApiErrorCode.ANNOUNCEMENT_AUDIENCE_TOO_LARGE,
    message = "Announcement audience ($size) exceeds the synchronous fan-out limit of $maximum.",
    metadata = mapOf("audienceSize" to size.toString(), "maximum" to maximum.toString()),
)

class ForbiddenResourceException(
    resourceName: String,
) : DomainException(
    status = HttpStatus.FORBIDDEN,
    code = ApiErrorCode.FORBIDDEN_RESOURCE,
    message = "You do not have access to this $resourceName.",
)

class DiaryEntryLockedException : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.DIARY_ENTRY_LOCKED,
    message = "This diary entry can no longer be changed.",
)

class FeatureDisabledException(
    featureName: String,
) : DomainException(
    status = HttpStatus.FORBIDDEN,
    code = ApiErrorCode.FEATURE_DISABLED,
    message = "$featureName is currently disabled.",
)

class SubscriptionRequiredException : DomainException(
    status = HttpStatus.PAYMENT_REQUIRED,
    code = ApiErrorCode.SUBSCRIPTION_REQUIRED,
    message = "An active subscription is required for this action.",
)

class PlanLimitReachedException(
    featureKey: String,
    limitName: String,
    limitValue: Int,
) : DomainException(
    status = HttpStatus.PAYMENT_REQUIRED,
    code = ApiErrorCode.SUBSCRIPTION_REQUIRED,
    message = "The free plan limit has been reached. Upgrade is required to continue.",
    metadata = mapOf(
        "featureKey" to featureKey,
        "limitName" to limitName,
        "limitValue" to limitValue.toString(),
        "supportReasonCode" to "PLAN_LIMIT_REACHED",
        "recoveryPath" to "/profile/billing?status=plan_limit",
    ),
)

class SubscriptionExpiredException : DomainException(
    status = HttpStatus.PAYMENT_REQUIRED,
    code = ApiErrorCode.SUBSCRIPTION_EXPIRED,
    message = "Your subscription has expired.",
)

class EntitlementGracePeriodException(
    featureKey: String,
) : DomainException(
    status = HttpStatus.PAYMENT_REQUIRED,
    code = ApiErrorCode.GRACE_PERIOD,
    message = "Payment recovery is required before using this premium feature.",
    metadata = mapOf(
        "featureKey" to featureKey,
        "recoveryPath" to "/billing",
        "supportReasonCode" to "PAYMENT_PAST_DUE",
    ),
)

class DuplicateRequestException : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.DUPLICATE_REQUEST,
    message = "This request has already been processed.",
)

class SubscriptionPriceManagementException(
    message: String,
    conflict: Boolean = false,
) : DomainException(
    status = if (conflict) HttpStatus.CONFLICT else HttpStatus.BAD_REQUEST,
    code = if (conflict) ApiErrorCode.DUPLICATE_REQUEST else ApiErrorCode.VALIDATION_ERROR,
    message = message,
)

class IdempotencyKeyConflictException : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.IDEMPOTENCY_KEY_CONFLICT,
    message = "This idempotency key was already used with a different request.",
)

class InvalidIdempotencyKeyException : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.INVALID_IDEMPOTENCY_KEY,
    message = "Idempotency-Key must be 8 to 255 characters and contain only letters, numbers, dots, underscores, colons, or hyphens.",
)

class InvalidProfilePreferenceException(
    fieldName: String,
) : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VALIDATION_ERROR,
    message = "$fieldName is invalid.",
)

class InvalidServingUnitException : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VALIDATION_ERROR,
    message = "servingUnit is invalid.",
)

class InvalidServingDefinitionException(
    message: String = "Serving definition is invalid.",
) : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VALIDATION_ERROR,
    message = message,
)

class InvalidCustomFoodPortionException(
    message: String = "Custom food portion is invalid.",
) : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VALIDATION_ERROR,
    message = message,
)

class InvalidMealItemException(
    message: String = "Meal item is invalid.",
) : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VALIDATION_ERROR,
    message = message,
)

class InvalidDiaryDateException : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VALIDATION_ERROR,
    message = "date must be an ISO-8601 calendar date.",
)

class FutureDateLimitException(
    date: java.time.LocalDate,
    maximumDate: java.time.LocalDate,
) : DomainException(
    status = HttpStatus.FORBIDDEN,
    code = ApiErrorCode.FUTURE_DATE_LIMIT,
    message = "Date $date is outside the available diary window.",
    metadata = mapOf("maximumDate" to maximumDate.toString()),
)

class InvalidDiaryEntryException(
    message: String = "Diary entry is invalid.",
) : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VALIDATION_ERROR,
    message = message,
)

class InvalidGoalScheduleException(
    message: String = "Goal schedule is invalid.",
) : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VALIDATION_ERROR,
    message = message,
)

class InvalidNutritionGoalException(
    message: String = "Nutrition goal is invalid.",
) : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VALIDATION_ERROR,
    message = message,
)

class InvalidWeightEntryException(
    message: String = "Weight entry is invalid.",
) : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VALIDATION_ERROR,
    message = message,
)

class InvalidProgressRangeException(
    message: String = "Progress range is invalid.",
) : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VALIDATION_ERROR,
    message = message,
)

class FieldValidationException(
    message: String = "Request validation failed.",
    val fieldErrors: List<ApiErrorResponse.FieldError>,
) : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VALIDATION_ERROR,
    message = message,
)

class InvalidVerificationCodeException : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.INVALID_VERIFICATION_CODE,
    message = "The verification code is invalid.",
)

class VerificationCodeExpiredException : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.VERIFICATION_CODE_EXPIRED,
    message = "The verification code has expired.",
)

class TooManyVerificationAttemptsException : DomainException(
    status = HttpStatus.TOO_MANY_REQUESTS,
    code = ApiErrorCode.TOO_MANY_VERIFICATION_ATTEMPTS,
    message = "Too many verification attempts. Please try again later.",
)

class AccountAlreadyVerifiedException : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.ACCOUNT_ALREADY_VERIFIED,
    message = "This account is already verified.",
)

class PasswordAlreadySetException : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.PASSWORD_ALREADY_SET,
    message = "A password is already set for this account. Change it instead.",
)

class RecentVerificationRequiredException : DomainException(
    status = HttpStatus.FORBIDDEN,
    code = ApiErrorCode.RECENT_VERIFICATION_REQUIRED,
    message = "Please verify your identity with a one-time code before changing your password.",
)

class NoRemainingLoginIdentifierException : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.NO_REMAINING_LOGIN_IDENTIFIER,
    message = "Removing the password would leave the account without a verified login method.",
)

class ExternalServiceException(
    serviceName: String,
) : DomainException(
    status = HttpStatus.SERVICE_UNAVAILABLE,
    code = ApiErrorCode.EXTERNAL_SERVICE_UNAVAILABLE,
    message = "$serviceName is currently unavailable. Please try again later.",
)

class InvoiceNotFoundException : DomainException(
    status = HttpStatus.NOT_FOUND,
    code = ApiErrorCode.INVOICE_NOT_FOUND,
    message = "Invoice was not found.",
)

class InvoiceStatusConflictException(
    expected: String,
    actual: String,
) : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.INVOICE_STATUS_CONFLICT,
    message = "Invoice status is $actual, expected $expected.",
)

class SubscriptionNotFoundException : DomainException(
    status = HttpStatus.NOT_FOUND,
    code = ApiErrorCode.SUBSCRIPTION_NOT_FOUND,
    message = "Subscription was not found.",
)

class SubscriptionStatusConflictException(expected: String, actual: String) : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.SUBSCRIPTION_STATUS_CONFLICT,
    message = "Subscription status is $actual, expected $expected.",
)

class ManualGrantNotFoundException : DomainException(
    status = HttpStatus.NOT_FOUND,
    code = ApiErrorCode.MANUAL_GRANT_NOT_FOUND,
    message = "Manual grant was not found.",
)

class BillingBlockedException : DomainException(
    status = HttpStatus.FORBIDDEN,
    code = ApiErrorCode.BILLED_BLOCKED,
    message = "Billing is blocked for this account.",
)

class InvoicePriceSnapshotMissingException(
    invoiceId: String,
) : DomainException(
    status = HttpStatus.INTERNAL_SERVER_ERROR,
    code = ApiErrorCode.INTERNAL_ERROR,
    message = "Invoice $invoiceId references a subscription price that no longer exists.",
)

class InactivePlanException : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.INACTIVE_PLAN,
    message = "The specified plan is not active.",
)

class DeletedUserException : DomainException(
    status = HttpStatus.NOT_FOUND,
    code = ApiErrorCode.RESOURCE_NOT_FOUND,
    message = "The specified user was not found.",
)

class PromotionException(
    val reasonCode: String,
    message: String,
) : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.PROMOTION_INVALID,
    message = message,
)

class GrantReasonRequiredException : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.GRANT_REASON_REQUIRED,
    message = "A reason is required for manual grants.",
)

class GrantExpiryInPastException : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.GRANT_EXPIRY_IN_PAST,
    message = "Grant expiry date must be in the future.",
)

class LowerTierManualGrantException : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.LOWER_TIER_MANUAL_GRANT,
    message = "Manual grants cannot downgrade an active subscription.",
)

class CheckoutDisabledException : DomainException(
    status = HttpStatus.FORBIDDEN,
    code = ApiErrorCode.CHECKOUT_DISABLED,
    message = "Checkout is currently disabled.",
)

class ProtectedAccountException(
    message: String = "This account is protected and cannot be deleted.",
) : DomainException(
    status = HttpStatus.FORBIDDEN,
    code = ApiErrorCode.PROTECTED_ACCOUNT,
    message = message,
)

class DeletionPreviewStaleException : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.DELETION_PREVIEW_STALE,
    message = "The deletion preview is stale or invalid. Generate a new preview and retry.",
)

class DeletionInProgressException : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.DELETION_IN_PROGRESS,
    message = "A deletion operation for this user is already running.",
)

class DeletionAlreadyCompletedException(
    operationId: String,
) : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.DELETION_ALREADY_COMPLETED,
    message = "This user has already been deleted.",
    metadata = mapOf("operationId" to operationId),
)

class DeletionConfirmationMismatchException : DomainException(
    status = HttpStatus.BAD_REQUEST,
    code = ApiErrorCode.DELETION_CONFIRMATION_MISMATCH,
    message = "The typed confirmation does not match the target account identifier.",
)

class CatalogVersionConflictException(
    expectedLockVersion: Int,
    actualLockVersion: Int,
) : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.CATALOG_VERSION_CONFLICT,
    message = "The catalog record was modified by someone else. Reload and retry.",
    metadata = mapOf(
        "expectedLockVersion" to expectedLockVersion.toString(),
        "actualLockVersion" to actualLockVersion.toString(),
    ),
)

class TrialNotAvailableException : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.TRIAL_NOT_AVAILABLE,
    message = "The trial is not available for this account.",
)

class TrialAlreadyRedeemedException : DomainException(
    status = HttpStatus.CONFLICT,
    code = ApiErrorCode.TRIAL_ALREADY_REDEEMED,
    message = "The trial has already been redeemed for this account.",
)
