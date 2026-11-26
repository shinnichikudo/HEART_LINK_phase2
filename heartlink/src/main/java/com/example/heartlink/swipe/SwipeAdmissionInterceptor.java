package com.example.heartlink.swipe;

import com.example.heartlink.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.Semaphore;

/** Bounds concurrent swipe requests before the transactional service borrows a database connection. */
@Component
public class SwipeAdmissionInterceptor implements HandlerInterceptor {
    private static final String PERMIT_HELD = SwipeAdmissionInterceptor.class.getName() + ".permitHeld";

    private final Semaphore permits;

    public SwipeAdmissionInterceptor(
            @Value("${heartlink.swipe.max-concurrent:6}") int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("heartlink.swipe.max-concurrent must be at least 1");
        }
        this.permits = new Semaphore(maxConcurrent);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!permits.tryAcquire()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SWIPE_CAPACITY_REACHED",
                    "Swipe capacity is temporarily full; retry shortly");
        }
        request.setAttribute(PERMIT_HELD, Boolean.TRUE);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        if (Boolean.TRUE.equals(request.getAttribute(PERMIT_HELD))) {
            permits.release();
        }
    }
}
