package xyz.tcheeric.cashu.mint.admin.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the administrative REST service.
 */
@SpringBootApplication(scanBasePackages = "xyz.tcheeric.cashu.mint.admin.rest")
public class CashuMintAdminRestApplication {

    public static void main(String[] args) {
        SpringApplication.run(CashuMintAdminRestApplication.class, args);
    }
}
