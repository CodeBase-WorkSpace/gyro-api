package com.gyro.api.common.idempotency.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "idempotency_keys",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_idempotency_keys_scope_key",
            columnNames = ["scope", "idempotency_key"],
        )
    ],
)
class IdempotencyKey(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @Column(nullable = false, length = 128)
    var scope: String,

    /** Owner is explicit so lifecycle cleanup never depends on parsing [scope]. */
    @Column(name = "user_id")
    var userId: UUID? = null,

    @Column(name = "idempotency_key", nullable = false, length = 255)
    var idempotencyKey: String,

    @Column(name = "request_hash", nullable = false, length = 128)
    var requestHash: String,

    @Column(name = "response_status", nullable = false)
    var responseStatus: Int,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", nullable = false, columnDefinition = "jsonb")
    var responseBody: String,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
)
