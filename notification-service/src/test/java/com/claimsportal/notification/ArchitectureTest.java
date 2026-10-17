package com.claimsportal.notification;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layer rules from PLAN section 6, checked on every build. */
@AnalyzeClasses(packages = "com.claimsportal.notification", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * The listener calls the use case; the use case uses the repository and the
     * {@code EmailSender} port, which the email package implements.
     */
    @ArchTest
    static final ArchRule layersOnlyDependInwards = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Messaging").definedBy("..messaging..")
            .layer("Application").definedBy("..application..")
            .layer("Email").definedBy("..email..")
            .layer("Persistence").definedBy("..persistence..")
            .whereLayer("Messaging").mayNotBeAccessedByAnyLayer()
            .whereLayer("Email").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Messaging", "Email")
            .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Application");

    @ArchTest
    static final ArchRule applicationDoesNotKnowAboutTheBrokerOrSmtp = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.amqp..", "org.springframework.mail..", "jakarta.mail..", "org.thymeleaf..")
            .because("RabbitMQ and SMTP details belong in the messaging and email packages");
}
