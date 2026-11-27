package com.example.heartlink.common.ratelimit;

import com.example.heartlink.common.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final Pattern CHAT_HISTORY_PATH = Pattern.compile("^/api/v1/matches/\\d+/messages$");

    private final TokenBucketRateLimiter limiter;
    private final RateLimitProperties policies;

    public RateLimitInterceptor(TokenBucketRateLimiter limiter, RateLimitProperties policies) {
        this.limiter = limiter;
        this.policies = policies;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Object attribute = request.getAttribute(CurrentUser.ID);
        if (!(attribute instanceof Long userId)) {
            return true;
        }

        var operation = operationFor(request);
        if (operation == null) {
            return true;
        }
        var retryAfter = limiter.tryAcquire(userId, operation.name(), operation.policy());
        if (retryAfter.isEmpty()) {
            return true;
        }

        writeRateLimitResponse(response, retryAfter.get());
        return false;
    }

    private Operation operationFor(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        if ("POST".equals(method) && "/api/v1/swipes".equals(path)) {
            return new Operation("swipe", policies.swipe());
        }
        if ("GET".equals(method) && "/api/v1/recommendations".equals(path)) {
            return new Operation("recommendations", policies.recommendations());
        }
        if (CHAT_HISTORY_PATH.matcher(path).matches()) {
            if ("POST".equals(method)) {
                return new Operation("chat-send", policies.chatSend());
            }
            if ("GET".equals(method)) {
                return new Operation("chat-history", policies.chatHistory());
            }
        }
        return null;
    }

    private void writeRateLimitResponse(HttpServletResponse response, Duration retryAfter) throws IOException {
        long retrySeconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        response.setStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retrySeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests. Try again later.\"}}");
    }

    private record Operation(String name, RateLimitProperties.Policy policy) { }
}