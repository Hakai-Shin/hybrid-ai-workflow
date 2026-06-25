package com.docubrain.enclave;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class EnclaveApplication {
    public static void main(String[] args) {
        SpringApplication.run(EnclaveApplication.class, args);
    }
}
