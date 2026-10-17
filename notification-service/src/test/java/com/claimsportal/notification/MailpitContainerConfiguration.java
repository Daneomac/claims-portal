package com.claimsportal.notification;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A Mailpit SMTP server (same image as docker-compose.yml) with the application's
 * {@code spring.mail.*} settings pointed at it. Kept apart from
 * {@link TestcontainersConfiguration} so a test can run without a working mail server.
 */
@TestConfiguration(proxyBeanMethods = false)
class MailpitContainerConfiguration {

    private static final int SMTP_PORT = 1025;
    private static final int HTTP_PORT = 8025;

    @Bean
    GenericContainer<?> mailpitContainer() {
        return new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.31"))
                .withExposedPorts(SMTP_PORT, HTTP_PORT)
                .waitingFor(Wait.forHttp("/readyz").forPort(HTTP_PORT));
    }

    @Bean
    DynamicPropertyRegistrar mailpitProperties(GenericContainer<?> mailpitContainer) {
        return registry -> {
            registry.add("spring.mail.host", mailpitContainer::getHost);
            registry.add("spring.mail.port", () -> mailpitContainer.getMappedPort(SMTP_PORT));
        };
    }

    @Bean
    Mailpit mailpit(GenericContainer<?> mailpitContainer) {
        return new Mailpit(() -> "http://" + mailpitContainer.getHost() + ":" + mailpitContainer.getMappedPort(HTTP_PORT));
    }
}
