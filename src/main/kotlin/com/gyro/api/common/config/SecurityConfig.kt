package com.gyro.api.common.config

import com.gyro.api.common.error.ApiErrorCode
import com.gyro.api.common.error.ApiErrorResponseWriter
import com.gyro.api.common.observability.PrometheusMetricsAuthFilter
import com.gyro.api.common.security.JwtAuthFilter
import com.gyro.api.notification.web.TelegramWebhookAuthenticationFilter
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
class DisabledUserPasswordAuthConfig {
    @Bean
    fun userDetailsService(): UserDetailsService {
        return UserDetailsService {
            throw UsernameNotFoundException("Username/password authentication is disabled.")
        }
    }
}

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    @Value("\${app.cors.allowed-origins}")
    private val allowedOrigins: String,
    @Value("\${app.cors.allowed-methods}")
    private val allowedMethods: String,
    @Value("\${app.cors.allowed-headers}")
    private val allowedHeaders: String,
    @Value("\${app.cors.allow-credentials}")
    private val allowCredentials: Boolean,
    @Value("\${app.api.base-path:/api/v1}")
    private val apiBasePath: String,
    @Value("\${app.billing.demo.enabled:false}")
    private val demoBillingEnabled: Boolean,
    @Value("\${app.billing.payping.enabled:false}")
    private val payPingEnabled: Boolean,
    private val environment: Environment,
    private val jwtAuthFilter: JwtAuthFilter,
    private val prometheusMetricsAuthFilter: PrometheusMetricsAuthFilter,
    private val telegramWebhookAuthenticationFilter: TelegramWebhookAuthenticationFilter,
    private val apiErrorResponseWriter: ApiErrorResponseWriter,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val publicBillingPlansPath = apiPath("/billing/plans")
        val publicPayPingCallbackPath = apiPath("/billing/payping/callback")
        val publicDemoCheckoutPath = apiPath("/billing/demo/checkout/**")
        val telegramWebhookPath = apiPath("/integrations/telegram/webhook")
        val authPath = apiPath("/auth/**")
        val adminPath = apiPath("/admin/**")

        return http
            .csrf { csrf -> csrf.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .formLogin { formLogin -> formLogin.disable() }
            .httpBasic { httpBasic -> httpBasic.disable() }
            .exceptionHandling { exceptions ->
                exceptions
                    .authenticationEntryPoint { _, response, _ ->
                        apiErrorResponseWriter.write(
                            response = response,
                            status = HttpServletResponse.SC_UNAUTHORIZED,
                            code = ApiErrorCode.INVALID_CREDENTIALS,
                            message = "Authentication is required.",
                        )
                    }
                    .accessDeniedHandler { _, response, _ ->
                        apiErrorResponseWriter.write(
                            response = response,
                            status = HttpServletResponse.SC_FORBIDDEN,
                            code = ApiErrorCode.FORBIDDEN_RESOURCE,
                            message = "You do not have access to this resource.",
                        )
                    }
            }
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers(HttpMethod.GET, publicBillingPlansPath).permitAll()
                    .requestMatchers(HttpMethod.GET, publicPayPingCallbackPath).permitAll()
                    .requestMatchers(HttpMethod.POST, publicPayPingCallbackPath).permitAll()
                    .apply {
                        if (demoBillingEnabled && !payPingEnabled && environment.acceptsProfiles(Profiles.of("dev & !prod"))) {
                            requestMatchers(HttpMethod.GET, publicDemoCheckoutPath).permitAll()
                            requestMatchers(HttpMethod.POST, publicDemoCheckoutPath).permitAll()
                        }
                    }
                    .requestMatchers(HttpMethod.POST, telegramWebhookPath).permitAll()
                    .requestMatchers(
                        authPath,
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus",
                        "/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                    ).permitAll()
                    .requestMatchers(adminPath).hasRole("ADMIN")
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(telegramWebhookAuthenticationFilter, JwtAuthFilter::class.java)
            .addFilterBefore(prometheusMetricsAuthFilter, JwtAuthFilter::class.java)
            .build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = this@SecurityConfig.allowedOrigins.toCsvList()
            allowedMethods = this@SecurityConfig.allowedMethods.toCsvList()
            allowedHeaders = this@SecurityConfig.allowedHeaders.toCsvList()
            this.allowCredentials = this@SecurityConfig.allowCredentials
        }

        val publicCallbackConfiguration = CorsConfiguration().apply {
            allowedOriginPatterns = listOf("*")
            allowedMethods = listOf("GET", "POST", "OPTIONS")
            allowedHeaders = listOf("*")
            this.allowCredentials = true
        }

        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration(apiPath("/billing/payping/callback"), publicCallbackConfiguration)
            registerCorsConfiguration("/**", configuration)
        }
    }

    private fun String.toCsvList(): List<String> {
        return split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun apiPath(path: String): String {
        val base = apiBasePath.trim().trimEnd('/')
        val suffix = if (path.startsWith('/')) path else "/$path"
        return if (base.isBlank() || base == "/") suffix else "$base$suffix"
    }
}
