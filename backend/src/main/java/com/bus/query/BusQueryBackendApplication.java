package com.bus.query;

import com.bus.query.config.TflProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(TflProperties.class)
public class BusQueryBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BusQueryBackendApplication.class, args);
    }
}
