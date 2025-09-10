package xyz.tcheeric.cashu.mint.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Limit component scanning to Cashu packages to avoid picking up external gateway beans
@SpringBootApplication(scanBasePackages = "xyz.tcheeric.cashu")
public class CashuMintRestApplication {

    public static void main(String[] args) {
        SpringApplication.run(CashuMintRestApplication.class, args);
    }
}
