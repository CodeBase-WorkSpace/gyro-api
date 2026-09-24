package com.gyro.api.subscription.infrastructure

import com.gyro.api.subscription.domain.EventSourceType
import com.gyro.api.subscription.domain.SubscriptionEvent
import com.gyro.api.subscription.domain.SubscriptionTransitionType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional
import java.util.UUID

interface SubscriptionEventRepository : JpaRepository<SubscriptionEvent, Long> {
    fun findByIdempotencyKey(idempotencyKey: String): Optional<SubscriptionEvent>

    @Query(
        """
        select case when count(se) > 0 then true else false end
        from SubscriptionEvent se
        where se.sourceType = :sourceType
          and se.sourceId = :sourceId
        """
    )
    fun existsBySourceTypeAndSourceId(sourceType: EventSourceType, sourceId: String): Boolean

    @Query(
        """
        select se from SubscriptionEvent se
        where se.userId = :userId
        order by se.createdAt desc
        """
    )
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): List<SubscriptionEvent>

    fun findFirstByUserIdAndTransitionTypeInOrderByCreatedAtDesc(
        userId: UUID,
        transitionTypes: Collection<SubscriptionTransitionType>,
    ): SubscriptionEvent?
}
