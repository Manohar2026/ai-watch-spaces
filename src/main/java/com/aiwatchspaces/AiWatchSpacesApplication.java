package com.aiwatchspaces;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiWatchSpacesApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiWatchSpacesApplication.class, args);
    }
}
