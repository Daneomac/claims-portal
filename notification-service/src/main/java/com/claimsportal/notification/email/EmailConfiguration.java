package com.claimsportal.notification.email;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;

/**
 * The Thymeleaf engine for email templates in {@code src/main/resources/templates/email/}.
 *
 * <p>Spring Boot's auto-configured engine only knows HTML templates. An email needs an HTML
 * part and a plain-text part, so this engine has a resolver for each: {@code *.html} in HTML
 * mode (values written with {@code th:text} are HTML-escaped) and {@code *.txt} in text mode.
 * Defining this bean makes Boot's own engine back off.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailProperties.class)
class EmailConfiguration {

    static final String TEMPLATE_FOLDER = "templates/email/";

    @Bean
    SpringTemplateEngine emailTemplateEngine() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.addTemplateResolver(templateResolver(TemplateMode.HTML, "*.html"));
        engine.addTemplateResolver(templateResolver(TemplateMode.TEXT, "*.txt"));
        return engine;
    }

    private static ITemplateResolver templateResolver(TemplateMode mode, String pattern) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(TEMPLATE_FOLDER);
        resolver.setResolvablePatterns(Set.of(pattern));
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return resolver;
    }
}
