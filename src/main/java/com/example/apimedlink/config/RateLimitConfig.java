package com.example.apimedlink.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Per-IP limits (GitHub #51); scheduling runs the cleanup of ended windows ({@code RateLimitStore}). */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {}
