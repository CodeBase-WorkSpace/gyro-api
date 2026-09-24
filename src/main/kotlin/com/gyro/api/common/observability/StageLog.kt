package com.gyro.api.common.observability

import com.gyro.api.common.error.DomainException
import org.slf4j.Logger
import org.slf4j.spi.LoggingEventBuilder

object StageLog {
    fun info(
        logger: Logger,
        event: String,
        stage: String,
        outcome: String,
        fields: Map<String, Any?> = emptyMap(),
        message: String = "Stage completed.",
    ) {
        logger.atInfo()
            .withStageFields(event, stage, outcome, fields)
            .log(message)
    }

    fun warn(
        logger: Logger,
        event: String,
        stage: String,
        outcome: String,
        fields: Map<String, Any?> = emptyMap(),
        message: String = "Stage completed with a domain error.",
    ) {
        logger.atWarn()
            .withStageFields(event, stage, outcome, fields)
            .log(message)
    }

    fun error(
        logger: Logger,
        event: String,
        stage: String,
        outcome: String,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable,
        message: String = "Stage failed unexpectedly.",
    ) {
        logger.atError()
            .withStageFields(
                event = event,
                stage = stage,
                outcome = outcome,
                fields = fields + ("exception" to error::class.simpleName),
            )
            .log(message, error)
    }

    fun <T> around(
        logger: Logger,
        event: String,
        stage: String,
        fields: Map<String, Any?> = emptyMap(),
        block: () -> T,
    ): T {
        info(
            logger = logger,
            event = event,
            stage = stage,
            outcome = "started",
            fields = fields,
            message = "Stage started.",
        )

        return try {
            val result = block()
            info(
                logger = logger,
                event = event,
                stage = stage,
                outcome = "succeeded",
                fields = fields,
            )
            result
        } catch (ex: DomainException) {
            warn(
                logger = logger,
                event = event,
                stage = stage,
                outcome = "rejected",
                fields = fields + ("errorCode" to ex.code.name),
            )
            throw ex
        } catch (ex: Exception) {
            error(
                logger = logger,
                event = event,
                stage = stage,
                outcome = "failed",
                fields = fields + ("errorCode" to "INTERNAL_ERROR"),
                error = ex,
            )
            throw ex
        }
    }

    private fun LoggingEventBuilder.withStageFields(
        event: String,
        stage: String,
        outcome: String,
        fields: Map<String, Any?>,
    ): LoggingEventBuilder {
        addKeyValue("event", event)
        addKeyValue("stage", stage)
        addKeyValue("outcome", outcome)
        fields.forEach { (key, value) ->
            if (value != null) {
                addKeyValue(key, value)
            }
        }
        return this
    }
}
