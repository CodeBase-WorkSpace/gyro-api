package com.gyro.api.common.security

import com.gyro.api.common.request.USER_ID_ATTRIBUTE
import com.gyro.api.common.request.USER_ROLE_ATTRIBUTE
import org.springframework.jdbc.core.JdbcTemplate
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
    private val jdbcTemplate: JdbcTemplate,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authHeader = request.getHeader("Authorization")

        if (
            authHeader != null &&
            authHeader.startsWith("Bearer ") &&
            SecurityContextHolder.getContext().authentication == null &&
            jwtService.validateAccessToken(authHeader)
        ) {
            val userId = jwtService.getUserIdFromToken(authHeader)
            val role = jwtService.getRoleFromToken(authHeader)

            if (userId != null && role != null && isActive(userId)) {
                val authorities = listOf(SimpleGrantedAuthority("ROLE_${role.name}"))
                val authentication = UsernamePasswordAuthenticationToken(userId, null, authorities)
                authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
                SecurityContextHolder.getContext().authentication = authentication
                request.setAttribute(USER_ID_ATTRIBUTE, userId)
                request.setAttribute(USER_ROLE_ATTRIBUTE, role.name)
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun isActive(userId: String): Boolean {
        return try {
            jdbcTemplate.queryForObject(
                "select status = 'ACTIVE' from users where id = ?",
                Boolean::class.java,
                java.util.UUID.fromString(userId),
            ) == true
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
