package com.example.heartlink.repository;

import com.example.heartlink.entity.Swipe;
import com.example.heartlink.entity.SwipeAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SwipeRepository extends JpaRepository<Swipe, Long> {
    boolean existsBySwiperIdAndTargetId(Long swiperId, Long targetId);

    Optional<Swipe> findBySwiperIdAndTargetId(Long swiperId, Long targetId);

    Optional<Swipe> findBySwiperIdAndIdempotencyKey(Long swiperId, String idempotencyKey);

    boolean existsBySwiperIdAndTargetIdAndAction(Long swiperId, Long targetId, SwipeAction action);
}
