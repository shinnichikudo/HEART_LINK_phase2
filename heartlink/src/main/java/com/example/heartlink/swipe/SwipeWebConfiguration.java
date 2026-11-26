package com.example.heartlink.swipe;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SwipeWebConfiguration implements WebMvcConfigurer {
    private final SwipeAdmissionInterceptor admissionInterceptor;

    public SwipeWebConfiguration(SwipeAdmissionInterceptor admissionInterceptor) {
        this.admissionInterceptor = admissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(admissionInterceptor).addPathPatterns("/api/v1/swipes");
    }
}
