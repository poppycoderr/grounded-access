package io.groundedaccess;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the control plane: identity, authorization, ingestion, retrieval and answering in one deployable.
 */
@SpringBootApplication
public class GroundedAccessApplication {

    public static void main(String[] args) {
        SpringApplication.run(GroundedAccessApplication.class, args);
    }
}
