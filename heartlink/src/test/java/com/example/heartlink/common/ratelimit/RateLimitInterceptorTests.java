package com.example.heartlink.common.ratelimit;

import com.example.heartlink.common.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitInterceptorTests {
    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        var policy = new RateLimitProperties.Policy(1, 1, Duration.ofMinutes(1));
        var policies = new RateLimitProperties(policy, policy, policy, policy);
        interceptor = new RateLimitInterceptor(new TokenBucketRateLimiter(Clock.systemUTC()), policies);
    }

    @Test
    void returns429WithRetryAfterWhenTheBucketIsEmpty() throws Exception {
        var first = request(7L, "POST", "/api/v1/swipes");
        assertThat(interceptor.preHandle(first.request(), first.response(), this)).isTrue();

        var second = request(7L, "POST", "/api/v1/swipes");
        assertThat(interceptor.preHandle(second.request(), second.response(), this)).isFalse();
        assertThat(second.response().getStatus()).isEqualTo(429);
        assertThat(second.response().getHeader("Retry-After")).isEqualTo("60");
        assertThat(second.response().getContentAsString())
                .contains("RATE_LIMITED", "Too many requests");
    }

    @Test
    void keepsDifferentUsersAndOperationsInSeparateBuckets() throws Exception {
        var userOneSwipe = request(7L, "POST", "/api/v1/swipes");
        var userTwoSwipe = request(8L, "POST", "/api/v1/swipes");
        var userOneRecommendations = request(7L, "GET", "/api/v1/recommendations");
        assertThat(interceptor.preHandle(userOneSwipe.request(), userOneSwipe.response(), this)).isTrue();
        assertThat(interceptor.preHandle(userTwoSwipe.request(), userTwoSwipe.response(), this)).isTrue();
        assertThat(interceptor.preHandle(userOneRecommendations.request(), userOneRecommendations.response(), this)).isTrue();

        var retry = request(7L, "POST", "/api/v1/swipes");
        assertThat(interceptor.preHandle(retry.request(), retry.response(), this)).isFalse();
    }

    private RequestAndResponse request(Long userId, String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setAttribute(CurrentUser.ID, userId);
        return new RequestAndResponse(request, new MockHttpServletResponse());
    }

    private record RequestAndResponse(MockHttpServletRequest request, MockHttpServletResponse response) { }
}