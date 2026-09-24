package com.gyro.api.subscription.web

import com.gyro.api.subscription.application.*
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.*

@RestController
@RequestMapping("\${app.api.base-path}/billing/payping")
class PayPingController(
    private val paymentVerificationService: PaymentVerificationService,
    @Value("\${app.billing.frontend-return-url:https://app.gyrohealth.ir/profile/billing/verify}")
    private val frontendReturnUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/callback")
    fun callbackRedirect(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) paymentCode: String?,
        @RequestParam(required = false, name = "refid") refIdLower: String?,
        @RequestParam(required = false, name = "refId") refIdCamel: String?,
        @RequestParam(required = false) paymentRefId: String?,
        @RequestParam(required = false, name = "clientrefid") clientRefIdLower: String?,
        @RequestParam(required = false, name = "clientRefId") clientRefIdCamel: String?,
        @RequestParam(required = false, name = "cardnumber") cardNumber: String?,
        @RequestParam(required = false, name = "cardhashpan") cardHashPan: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val command = PayPingCallbackCommand(
            code = paymentCode ?: code,
            refId = paymentRefId ?: refIdLower ?: refIdCamel,
            clientRefId = clientRefIdLower ?: clientRefIdCamel,
            cardNumber = cardNumber,
            cardHashPan = cardHashPan,
        )
        val status = paymentVerificationService.ingestPayPingCallback(command)
        log.info(
            "event=payping_callback_redirect outcome={} has_ref_id={} has_client_ref_id={} request_id={}",
            status.state,
            !command.refId.isNullOrBlank(),
            !command.clientRefId.isNullOrBlank(),
            request.getHeader("X-Request-Id"),
        )
        return redirectToFrontend(status)
    }

    @PostMapping("/callback")
    fun callback(
        @ModelAttribute form: PayPingCallbackForm,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val command = form.toCommand(objectMapper)
        val status = paymentVerificationService.ingestPayPingCallback(command)
        log.info(
            "event=payping_callback outcome={} has_ref_id={} has_client_ref_id={} request_id={}",
            status.state,
            !command.refId.isNullOrBlank(),
            !command.clientRefId.isNullOrBlank(),
            request.getHeader("X-Request-Id"),
        )
        return redirectToFrontend(status)
    }

    @GetMapping("/return-status")
    fun returnStatus(
        @AuthenticationPrincipal userId: String,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) paymentCode: String?,
        @RequestParam(required = false, name = "refid") refIdLower: String?,
        @RequestParam(required = false, name = "refId") refIdCamel: String?,
        @RequestParam(required = false) paymentRefId: String?,
        @RequestParam(required = false, name = "clientrefid") clientRefIdLower: String?,
        @RequestParam(required = false, name = "clientRefId") clientRefIdCamel: String?,
        @RequestParam(required = false, name = "cardnumber") cardNumber: String?,
        @RequestParam(required = false, name = "cardhashpan") cardHashPan: String?,
    ): ResponseEntity<PaymentReturnStatusDto> {
        val authenticatedUserId = UUID.fromString(userId)
        val status = paymentVerificationService.handlePayPingReturn(
            authenticatedUserId = authenticatedUserId,
            command = PayPingCallbackCommand(
                code = paymentCode ?: code,
                refId = paymentRefId ?: refIdLower ?: refIdCamel,
                clientRefId = clientRefIdLower ?: clientRefIdCamel,
                cardNumber = cardNumber,
                cardHashPan = cardHashPan,
            ),
        )
        return ResponseEntity.ok(status.toDto())
    }

    @GetMapping("/attempt-status")
    fun attemptStatus(
        @AuthenticationPrincipal userId: String,
        @RequestParam paymentAttemptId: UUID,
    ): ResponseEntity<PaymentReturnStatusDto> {
        val status = paymentVerificationService.statusForAttempt(UUID.fromString(userId), paymentAttemptId)
        return ResponseEntity.ok(status.toDto())
    }

    @PostMapping("/admin/reconcile")
    @PreAuthorize("hasRole('ADMIN')")
    fun reconcile(
        @RequestBody body: PayPingReconciliationRequest,
    ): ResponseEntity<PaymentReconciliationSummary> {
        return ResponseEntity.ok(
            paymentVerificationService.reconcile(
                PaymentReconciliationCommand(
                    userId = body.userId,
                    invoiceId = body.invoiceId,
                    paymentAttemptId = body.paymentAttemptId,
                ),
            ),
        )
    }

    private fun redirectToFrontend(status: PaymentReturnStatus): ResponseEntity<Void> {
        val builder = UriComponentsBuilder.fromUriString(frontendReturnUrl)
        builder.queryParam("state", status.state.name)
        status.paymentAttemptId?.let { builder.queryParam("paymentAttemptId", it) }
        status.invoiceId?.let { builder.queryParam("invoiceId", it) }
        status.clientRefId?.let { builder.queryParam("clientRefId", it) }
        status.providerRefId?.let { builder.queryParam("providerRefId", it) }
        status.requestId?.let { builder.queryParam("requestId", it) }
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(builder.build().toUriString()))
            .build()
    }
}

data class PayPingCallbackForm(
    val code: String? = null,
    val paymentCode: String? = null,
    val refid: String? = null,
    val paymentRefId: String? = null,
    val clientrefid: String? = null,
    val cardnumber: String? = null,
    val cardhashpan: String? = null,
    val status: String? = null,
    val errorCode: String? = null,
    val data: String? = null,
) {
    fun toCommand(objectMapper: ObjectMapper): PayPingCallbackCommand {
        return v3Command(objectMapper) ?: v2Command()
    }

    private fun v3Command(objectMapper: ObjectMapper): PayPingCallbackCommand? {
        if (data.isNullOrBlank()) return null
        val node = runCatching { objectMapper.readTree(data) }.getOrNull() ?: return null
        val clientRefId = node.get("clientRefId")?.asText()?.takeIf { it.isNotBlank() }
        val resolvedPaymentCode = node.get("paymentCode")?.asText()?.takeIf { it.isNotBlank() }
        val paymentRefId = node.get("paymentRefId")?.asText()?.takeIf { it.isNotBlank() }
        val cardNumber = node.get("cardNumber")?.asText()?.takeIf { it.isNotBlank() }
        val cardHashPan = node.get("cardHashPan")?.asText()?.takeIf { it.isNotBlank() }
        if (clientRefId.isNullOrBlank() && paymentRefId.isNullOrBlank()) return null
        return PayPingCallbackCommand(
            code = resolvedPaymentCode ?: paymentCode ?: code,
            refId = paymentRefId,
            clientRefId = clientRefId,
            cardNumber = cardNumber,
            cardHashPan = cardHashPan,
        )
    }

    private fun v2Command(): PayPingCallbackCommand {
        return PayPingCallbackCommand(
            code = paymentCode ?: code,
            refId = effectiveRefId(),
            clientRefId = clientrefid,
            cardNumber = cardnumber,
            cardHashPan = cardhashpan,
        )
    }

    fun effectiveRefId(): String? = paymentRefId ?: refid
}

data class PayPingReconciliationRequest(
    val userId: UUID? = null,
    val invoiceId: UUID? = null,
    val paymentAttemptId: UUID? = null,
)

data class PaymentReturnStatusDto(
    val state: String,
    val paymentAttemptId: String?,
    val invoiceId: String?,
    val providerCode: String?,
    val providerRefId: String?,
    val clientRefId: String?,
    val requestId: String?,
    val message: String,
)

fun PaymentReturnStatus.toDto(): PaymentReturnStatusDto {
    return PaymentReturnStatusDto(
        state = state.name,
        paymentAttemptId = paymentAttemptId?.toString(),
        invoiceId = invoiceId?.toString(),
        providerCode = providerCode,
        providerRefId = providerRefId,
        clientRefId = clientRefId,
        requestId = requestId,
        message = message,
    )
}
