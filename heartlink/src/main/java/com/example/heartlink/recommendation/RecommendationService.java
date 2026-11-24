package com.example.heartlink.recommendation;

import com.example.heartlink.common.error.ApiException;
import com.example.heartlink.recommendation.dto.RecommendationResponse;
import com.example.heartlink.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationService {
    private final UserRepository userRepository;

    public RecommendationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public RecommendationResponse list(Long userId, int limit) {
        return list(userId, limit, null);
    }

    @Transactional(readOnly = true)
    public RecommendationResponse list(Long userId, int limit, Long cursor) {
        if (limit < 1 || limit > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "limit must be between 1 and 50");
        }
        if (cursor != null && cursor < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "cursor must be a positive profile ID");
        }
        if (!userRepository.existsById(userId)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "User does not exist");
        }

        var page = PageRequest.of(0, limit + 1);
        var candidates = cursor == null
                ? userRepository.findRecommendations(userId, page)
                : userRepository.findRecommendationsAfterId(userId, cursor, page);
        boolean hasMore = candidates.size() > limit;
        var profiles = candidates.stream().limit(limit)
                .map(profile -> new RecommendationResponse.Profile(profile.getId(), profile.getName(), profile.getBio()))
                .toList();
        Long nextCursor = hasMore ? profiles.getLast().id() : null;
        return new RecommendationResponse(profiles, nextCursor);
    }
}
