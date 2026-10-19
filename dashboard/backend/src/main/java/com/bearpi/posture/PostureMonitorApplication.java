package com.bearpi.posture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PostureMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PostureMonitorApplication.class, args);
    }
}
