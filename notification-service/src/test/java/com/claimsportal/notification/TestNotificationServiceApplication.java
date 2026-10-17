package com.claimsportal.notification;

import org.springframework.boot.SpringApplication;

/** Runs the service locally against throwaway Postgres, RabbitMQ and Mailpit containers. */
public class TestNotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(NotificationServiceApplication::main)
                .with(TestcontainersConfiguration.class, MailpitContainerConfiguration.class)
                .run(args);
    }

}
