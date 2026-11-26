package com.example.heartlink.recommendation;

import com.example.heartlink.common.error.ApiException;
import com.example.heartlink.repository.RecommendationProfileProjection;
import com.example.heartlink.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationServiceTests {
    final UserRepository users = mock(UserRepository.class);
    final RecommendationService service = new RecommendationService(users);

    @Test
    void rejectsOutOfRangeLimitsBeforeQuerying() {
        for (int limit : new int[]{-1, 0, 51}) {
            assertThatThrownBy(() -> service.list(1L, limit)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus().value()).isEqualTo(400));
        }
        verifyNoInteractions(users);
    }

    @Test
    void deletedUserCannotReadRecommendations() {
        assertThatThrownBy(() -> service.list(1L, 20)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus().value()).isEqualTo(401));
    }

    @Test
    void mapsPublicProfileAndSupportsEmptyList() {
        when(users.existsById(1L)).thenReturn(true);
        when(users.findRecommendations(1L, PageRequest.of(0, 2))).thenReturn(List.of(
                profile(2L, "Binh", "Hello")));
        var result = service.list(1L, 1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().id()).isEqualTo(2L);
        when(users.findRecommendations(1L, PageRequest.of(0, 2))).thenReturn(List.of());
        assertThat(service.list(1L, 1).items()).isEmpty();
    }

    private RecommendationProfileProjection profile(Long id, String name, String bio) {
        return new RecommendationProfileProjection() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getBio() {
                return bio;
            }
        };
    }
}
