package com.hardwarebilling;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HardwareBillingApplication {
    public static void main(String[] args) {
        SpringApplication.run(HardwareBillingApplication.class, args);
    }
}
