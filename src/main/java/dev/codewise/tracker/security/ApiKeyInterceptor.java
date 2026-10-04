package dev.codewise.tracker.security;

import dev.codewise.tracker.config.TrackerProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private static final String API_KEY_HEADER = "X-Tracker-Key";

    private final byte[] expectedApiKey;

    public ApiKeyInterceptor(TrackerProperties properties) {
        this.expectedApiKey = properties.apiKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws Exception {
        String suppliedApiKey = request.getHeader(API_KEY_HEADER);
        boolean authenticated = suppliedApiKey != null && MessageDigest.isEqual(
                expectedApiKey,
                suppliedApiKey.getBytes(StandardCharsets.UTF_8)
        );
        if (authenticated) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"API Key 无效\"}");
        return false;
    }
}
