package com.aiwatchspaces.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param saturationRatio queue fill ratio at or above which the gateway reports itself not ready
 */
@ConfigurationProperties("aiws.realtime.health")
public record RealtimeHealthProperties(@DefaultValue("0.9") double saturationRatio) {

    public RealtimeHealthProperties {
        if (saturationRatio <= 0 || saturationRatio > 1) {
            throw new IllegalArgumentException("aiws.realtime.health.saturation-ratio must be in (0, 1]");
        }
    }
}
