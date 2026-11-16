package com.example.apimedlink.config;

import com.example.apimedlink.security.ReportWriteGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The checks a route needs after it is matched and before its body is read (MLink-32, review on #123). */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ReportWriteGuard reportWriteGuard;

    public WebConfig(ReportWriteGuard reportWriteGuard) {
        this.reportWriteGuard = reportWriteGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(reportWriteGuard).addPathPatterns("/api/patients/*/reports");
    }
}
