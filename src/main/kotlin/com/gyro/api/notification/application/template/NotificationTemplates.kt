package com.gyro.api.notification.application.template

import com.gyro.api.notification.domain.*
import com.gyro.api.notification.infrastructure.persistence.NotificationTemplateEntity
import com.gyro.api.notification.infrastructure.persistence.NotificationTemplateRepository
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

data class NotificationTemplateDefinition(
    val key: String,
    val version: Int,
    val channel: NotificationChannel,
    val locale: String,
    val subject: String?,
    val plainBody: String,
    val htmlBody: String?,
    val requiredVariables: Map<String, TemplateVariableRule>,
    val activatedAt: Instant,
) {
    fun contentHash(): String {
        val canonical = buildString {
            append(key).append('\n').append(version).append('\n').append(channel).append('\n').append(locale).append('\n')
            append(subject.orEmpty()).append('\n').append(plainBody).append('\n').append(htmlBody.orEmpty()).append('\n')
            requiredVariables.toSortedMap().forEach { (name, rule) ->
                append(name).append(':').append(rule.type).append(':').append(rule.sensitivity).append(':')
                    .append(rule.persistence).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

@Component
class NotificationTemplateCatalog {
    val definitions: List<NotificationTemplateDefinition> = listOf(
        NotificationTemplateDefinition("push-test", 1, NotificationChannel.PUSH, "fa", "اعلان آزمایشی جیرو", "اعلان‌های جیرو با موفقیت فعال است.", null, emptyMap(), Instant.parse("2026-07-16T00:00:00Z")),
        NotificationTemplateDefinition("telegram-link-confirmation", 1, NotificationChannel.TELEGRAM, "fa", "اتصال تلگرام جیرو", "حساب تلگرام شما با موفقیت به جیرو متصل شد.", null, emptyMap(), Instant.parse("2026-07-16T00:00:00Z")),
        NotificationTemplateDefinition("telegram-test", 1, NotificationChannel.TELEGRAM, "fa", "پیام آزمایشی جیرو", "اتصال تلگرام شما با موفقیت کار می‌کند.", null, emptyMap(), Instant.parse("2026-07-16T00:00:00Z")),
        NotificationTemplateDefinition(
            key = "notification-core-probe",
            version = 1,
            channel = NotificationChannel.EMAIL,
            locale = "en",
            subject = "Notification core probe",
            plainBody = "Notification core probe completed.",
            htmlBody = null,
            requiredVariables = emptyMap(),
            activatedAt = Instant.parse("2026-07-14T00:00:00Z"),
        ),
        NotificationTemplateDefinition(
            key = "payment-verified",
            version = 1,
            channel = NotificationChannel.EMAIL,
            locale = "fa",
            subject = "رسید پرداخت جیرو",
            plainBody = "پرداخت شما با موفقیت ثبت شد. مبلغ پرداختی: {{amount}} {{currency}}.",
            htmlBody = "<html lang=\"fa\" dir=\"rtl\"><body><p>پرداخت شما با موفقیت ثبت شد.</p><p>مبلغ پرداختی: <strong>{{amount}} {{currency}}</strong></p></body></html>",
            requiredVariables = PAYMENT_VERIFIED_VARIABLES,
            activatedAt = Instant.parse("2026-07-15T00:00:00Z"),
        ),
        NotificationTemplateDefinition(
            key = "payment-verified",
            version = 1,
            channel = NotificationChannel.SMS,
            locale = "fa",
            subject = null,
            plainBody = "جیرو: پرداخت شما با موفقیت ثبت شد. مبلغ: {{amount}} {{currency}}.",
            htmlBody = null,
            requiredVariables = PAYMENT_VERIFIED_VARIABLES,
            activatedAt = Instant.parse("2026-07-15T00:00:00Z"),
        ),
        NotificationTemplateDefinition("recalibration-suggestion", 1, NotificationChannel.PUSH, "fa", "پیشنهاد به‌روزرسانی برنامه", "بر اساس روند وزن دو هفته اخیر، پیشنهاد تازه‌ای برای هدف روزانه‌ات آماده است. از داشبورد بررسی‌اش کن.", null, emptyMap(), Instant.parse("2026-07-18T00:00:00Z")),
        NotificationTemplateDefinition(
            key = "coach-data-nudge",
            version = 1,
            channel = NotificationChannel.PUSH,
            locale = "fa",
            subject = "یک ثبت کوتاه برای بررسی برنامه",
            plainBody = "{{body}}",
            htmlBody = null,
            requiredVariables = COACH_DATA_NUDGE_VARIABLES,
            activatedAt = Instant.parse("2026-07-31T00:00:00Z"),
        ),
        NotificationTemplateDefinition("premium-access-downgraded", 1, NotificationChannel.PUSH, "fa", "پایان دوره پیشرفته", "اطلاعات و تنظیماتت حفظ شده‌اند و فعلاً محدودیت‌های طرح رایگان اعمال می‌شوند. با تمدید، دسترسی‌های پیشرفته همان لحظه برمی‌گردند.", null, emptyMap(), Instant.parse("2026-07-18T00:00:00Z")),
        NotificationTemplateDefinition("food-meal-reminder", 1, NotificationChannel.PUSH, "fa", null, "زمان ثبت وعده غذایی است.", null, emptyMap(), Instant.parse("2026-07-15T00:00:00Z")),
        NotificationTemplateDefinition("food-incomplete-day-reminder", 1, NotificationChannel.PUSH, "fa", null, "اگر امروز غذایی ثبت کرده‌ای، آن را به دفترچه اضافه کن.", null, emptyMap(), Instant.parse("2026-07-15T00:00:00Z")),
        NotificationTemplateDefinition("weight-reminder", 1, NotificationChannel.PUSH, "fa", null, "وزن امروزت را ثبت کن تا روند و برنامه‌ات دقیق بماند.", null, emptyMap(), Instant.parse("2026-07-17T00:00:00Z")),
        // Admin free text is carried as allowlisted PUBLIC variables; the template body itself stays immutable.
        NotificationTemplateDefinition(
            key = "admin-announcement",
            version = 1,
            channel = NotificationChannel.PUSH,
            locale = "fa",
            subject = "{{title}}",
            plainBody = "{{body}}",
            htmlBody = null,
            requiredVariables = ADMIN_ANNOUNCEMENT_VARIABLES,
            activatedAt = Instant.parse("2026-07-16T00:00:00Z"),
        ),
        NotificationTemplateDefinition(
            key = "admin-announcement",
            version = 1,
            channel = NotificationChannel.TELEGRAM,
            locale = "fa",
            subject = "{{title}}",
            plainBody = "{{body}}",
            htmlBody = null,
            requiredVariables = ADMIN_ANNOUNCEMENT_VARIABLES,
            activatedAt = Instant.parse("2026-07-16T00:00:00Z"),
        ),
    )

    fun find(key: String, version: Int, channel: NotificationChannel, locale: String) = definitions.singleOrNull {
        it.key == key && it.version == version && it.channel == channel && it.locale == locale
    }

    companion object {
        private val PAYMENT_VERIFIED_VARIABLES = mapOf(
            "amount" to TemplateVariableRule(TemplateVariableType.NUMBER, TemplateVariableSensitivity.PRIVATE, TemplateVariablePersistence.ALLOWED),
            "currency" to TemplateVariableRule(TemplateVariableType.TEXT, TemplateVariableSensitivity.PUBLIC, TemplateVariablePersistence.ALLOWED),
        )
        val ADMIN_ANNOUNCEMENT_VARIABLES = mapOf(
            "title" to TemplateVariableRule(TemplateVariableType.TEXT, TemplateVariableSensitivity.PUBLIC, TemplateVariablePersistence.ALLOWED),
            "body" to TemplateVariableRule(TemplateVariableType.TEXT, TemplateVariableSensitivity.PUBLIC, TemplateVariablePersistence.ALLOWED),
        )
        val COACH_DATA_NUDGE_VARIABLES = mapOf(
            "body" to TemplateVariableRule(TemplateVariableType.TEXT, TemplateVariableSensitivity.PUBLIC, TemplateVariablePersistence.ALLOWED),
        )
    }
}

@Service
@Order(20)
@Transactional
class NotificationTemplateSynchronizer(
    private val catalog: NotificationTemplateCatalog,
    private val templates: NotificationTemplateRepository,
    private val objectMapper: ObjectMapper,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = sync()

    fun sync() {
        catalog.definitions.forEach { definition ->
            val existing = templates.findByTemplateKeyAndVersionAndChannelAndLocale(
                definition.key,
                definition.version,
                definition.channel,
                definition.locale,
            )
            if (existing == null) {
                templates.save(toEntity(definition))
            } else {
                check(existing.contentHash == definition.contentHash()) {
                    "Immutable notification template drift detected for ${definition.key} v${definition.version} ${definition.channel}/${definition.locale}"
                }
            }
        }
    }

    private fun toEntity(definition: NotificationTemplateDefinition) = NotificationTemplateEntity(
        templateKey = definition.key,
        version = definition.version,
        channel = definition.channel,
        locale = definition.locale,
        subject = definition.subject,
        plainBody = definition.plainBody,
        htmlBody = definition.htmlBody,
        requiredVariables = objectMapper.writeValueAsString(
            definition.requiredVariables.toSortedMap().map { (name, rule) ->
                mapOf(
                    "name" to name,
                    "type" to rule.type.name,
                    "sensitivity" to rule.sensitivity.name,
                    "persistence" to rule.persistence.name,
                )
            },
        ),
        contentHash = definition.contentHash(),
        activatedAt = definition.activatedAt,
    )
}

data class RenderedTemplate(
    val key: String,
    val version: Int,
    val locale: String,
    val subject: String?,
    val plainBody: String,
    val htmlBody: String?,
    val persistedVariables: Map<String, String>,
)

@Service
class NotificationTemplateService(
    private val templates: NotificationTemplateRepository,
) {
    fun render(
        policy: NotificationPolicy,
        data: Map<String, TemplateVariableValue>,
        channel: NotificationChannel,
    ): RenderedTemplate {
        val persisted = validate(policy, data)
        val template = templates.findByTemplateKeyAndVersionAndChannelAndLocale(
            policy.templateKey,
            policy.templateVersion,
            channel,
            policy.templateLocale,
        ) ?: error("Active notification template is missing for ${policy.type}")

        return RenderedTemplate(
            key = template.templateKey,
            version = template.version,
            locale = template.locale,
            subject = template.subject?.render(persisted),
            plainBody = template.plainBody.render(persisted),
            htmlBody = template.htmlBody?.render(persisted),
            persistedVariables = persisted,
        )
    }

    fun validate(policy: NotificationPolicy, data: Map<String, TemplateVariableValue>): Map<String, String> {
        require(data.keys == policy.requiredVariables.keys) {
            "Template variables must match the registered schema for ${policy.type}"
        }
        return data.mapValues { (name, value) ->
            val rule = requireNotNull(policy.requiredVariables[name])
            require(rule.persistence == TemplateVariablePersistence.ALLOWED) {
                "Template variable $name is not allowed in durable notification state"
            }
            require(matches(rule.type, value)) { "Template variable $name has the wrong type" }
            value.renderedValue()
        }
    }

    private fun matches(type: TemplateVariableType, value: TemplateVariableValue) = when (type) {
        TemplateVariableType.TEXT -> value is TemplateVariableValue.Text
        TemplateVariableType.NUMBER -> value is TemplateVariableValue.Number
        TemplateVariableType.INSTANT -> value is TemplateVariableValue.Timestamp
        TemplateVariableType.BOOLEAN -> value is TemplateVariableValue.Flag
    }

    private fun TemplateVariableValue.renderedValue() = when (this) {
        is TemplateVariableValue.Text -> value
        is TemplateVariableValue.Number -> value.toPlainString()
        is TemplateVariableValue.Timestamp -> value.toString()
        is TemplateVariableValue.Flag -> value.toString()
    }

    private fun String.render(values: Map<String, String>): String = values.entries.fold(this) { result, (key, value) ->
        result.replace("{{$key}}", value)
    }.also { rendered ->
        require(!UNRESOLVED_VARIABLE.containsMatchIn(rendered)) { "Template contains an unresolved variable" }
    }

    companion object {
        private val UNRESOLVED_VARIABLE = Regex("\\{\\{[a-zA-Z0-9_.-]+}}")
    }
}
