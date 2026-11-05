package com.aiwatchspaces;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.aiwatchspaces.watchspace.WatchSpaceRateLimitProperties;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties(WatchSpaceRateLimitProperties.class)
public class AiWatchSpacesApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiWatchSpacesApplication.class, args);
    }
}
