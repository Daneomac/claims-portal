package com.claimsportal.notification;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }

    /** One clock for the whole app, so tests can replace it with a fixed one. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
