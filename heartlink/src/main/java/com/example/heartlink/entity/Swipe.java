package com.example.heartlink.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Entity: swipes
 * <p>
 * id (PK), swiper_id, target_id, action (like/pass), created_at,
 * idempotency_key and the original response's optional match ID.
 */
@Entity
@Table(name = "swipes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_swipe_pair", columnNames = {"swiper_id", "target_id"}),
                @UniqueConstraint(name = "uq_swipe_idempotency_key", columnNames = {"swiper_id", "idempotency_key"})
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Swipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "swiper_id", nullable = false)
    private User swiper;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id", nullable = false)
    private User target;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 10)
    private SwipeAction action;

    @Column(name = "idempotency_key", length = 128, updatable = false)
    private String idempotencyKey;

    // Snapshot of the successful response, independent of the match's current status.
    // Null means this swipe did not create a match, even if a reciprocal swipe matched later.
    @Column(name = "response_match_id")
    private Long responseMatchId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
