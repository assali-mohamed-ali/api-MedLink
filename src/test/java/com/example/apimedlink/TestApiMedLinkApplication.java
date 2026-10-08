package com.example.apimedlink;

import org.springframework.boot.SpringApplication;

public class TestApiMedLinkApplication {

    public static void main(String[] args) {
        SpringApplication.from(ApiMedLinkApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
