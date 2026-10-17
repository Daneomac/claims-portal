package com.claimsportal.api;

import org.springframework.boot.SpringApplication;

public class TestClaimsApiApplication {

    public static void main(String[] args) {
        SpringApplication.from(ClaimsApiApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
