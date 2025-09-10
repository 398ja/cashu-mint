package xyz.tcheeric.cashu.mint.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "xyz.tcheeric")
public class CashuMintRestApplication {

    public static void main(String[] args) {
        SpringApplication.run(CashuMintRestApplication.class, args);
    }
}
