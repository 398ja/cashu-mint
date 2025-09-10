package xyz.tcheeric.cashu.mint.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

@SpringBootApplication(scanBasePackages = "xyz.tcheeric")
@EnableConfigurationProperties(MintInfo.class)
public class CashuMintRestApplication {

    public static void main(String[] args) {
        SpringApplication.run(CashuMintRestApplication.class, args);
    }
}
