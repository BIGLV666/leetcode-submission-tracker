package dev.codewise.tracker.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tracker")
public record TrackerProperties(
        @NotBlank String apiKey,
        @Positive long userId,
        @NotBlank String codeKey
) {
}
