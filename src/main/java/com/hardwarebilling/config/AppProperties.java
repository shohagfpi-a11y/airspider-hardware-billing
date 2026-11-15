package com.hardwarebilling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String uploadDir, String setupToken, Login login, Local local, Boolean seedDemoData) {
    public record Login(int maxFailures, int lockMinutes) {}
    public record Local(Boolean embeddedPostgres, Integer pgPort, String pgDataDir) {}
}
