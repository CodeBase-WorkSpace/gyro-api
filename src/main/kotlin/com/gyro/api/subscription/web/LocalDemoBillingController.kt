package com.gyro.api.subscription.web

import com.gyro.api.subscription.billing.LocalDemoBillingProvider
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.util.HtmlUtils
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.util.UUID

@Controller
@Hidden
@Profile("dev & !prod")
@ConditionalOnProperty(prefix = "app.billing.demo", name = ["enabled"], havingValue = "true")
@ConditionalOnProperty(prefix = "app.billing.payping", name = ["enabled"], havingValue = "false", matchIfMissing = true)
@RequestMapping("\${app.api.base-path:/api/v1}/billing/demo")
class LocalDemoBillingController(
    private val provider: LocalDemoBillingProvider,
    @Value("\${server.port:8080}") private val serverPort: Int,
    @Value("\${app.api.base-path:/api/v1}") private val apiBasePath: String,
) {
    @GetMapping("/checkout/{attemptId}", produces = [MediaType.TEXT_HTML_VALUE])
    fun checkoutPage(
        @PathVariable attemptId: UUID,
        @RequestParam token: String,
    ): ResponseEntity<String> {
        val checkout = provider.findCheckout(attemptId, token) ?: return ResponseEntity.notFound().build()
        val action = HtmlUtils.htmlEscape("$apiBasePath/billing/demo/checkout/$attemptId")
        val safeToken = HtmlUtils.htmlEscape(checkout.token)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .header("Content-Security-Policy", "default-src 'none'; form-action 'self'; base-uri 'none'")
            .contentType(MediaType.TEXT_HTML)
            .body(
                """<!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Local demo checkout</title></head>
                <body><main><h1>Local demo checkout</h1>
                <p>No card or payment provider is used. This page exists only in the development profile.</p>
                <form method="post" action="$action">
                <input type="hidden" name="token" value="$safeToken">
                <button type="submit" name="outcome" value="SUCCESS">Simulate success</button>
                <button type="submit" name="outcome" value="FAILED">Simulate failure</button>
                <button type="submit" name="outcome" value="PENDING">Simulate pending</button>
                </form></main></body></html>""".trimIndent(),
            )
    }

    @PostMapping("/checkout/{attemptId}")
    fun selectOutcome(
        @PathVariable attemptId: UUID,
        @RequestParam token: String,
        @RequestParam outcome: String,
    ): ResponseEntity<Void> {
        val selected = runCatching { LocalDemoBillingProvider.Outcome.valueOf(outcome) }.getOrNull()
            ?: return ResponseEntity.badRequest().build()
        val checkout = provider.chooseOutcome(attemptId, token, selected) ?: return ResponseEntity.notFound().build()
        val callback = UriComponentsBuilder.fromUriString(
            "http://localhost:$serverPort${apiBasePath.trimEnd('/')}/billing/payping/callback",
        )
            .queryParam("code", checkout.providerCode)
            .queryParam("refid", checkout.providerRefId)
            .queryParam("clientrefid", checkout.request.clientRefId)
            .build()
            .toUriString()
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(callback)).build()
    }
}
