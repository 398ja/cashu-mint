package xyz.tcheeric.cashu.mint.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.InputStream;
import java.util.Properties;

// Limit component scanning to the mint packages to avoid picking up vault JPA controllers/repos
@Slf4j
@SpringBootApplication(scanBasePackages = "xyz.tcheeric.cashu.mint")
@EnableScheduling
public class CashuMintRestApplication {

    public static void main(String[] args) {
        SpringApplication.run(CashuMintRestApplication.class, args);
    }

    @Bean
    ApplicationRunner diagnostics() {
        return args -> {
            try {
                try (InputStream in = CashuMintRestApplication.class.getClassLoader().getResourceAsStream("app.properties")) {
                    if (in != null) {
                        Properties p = new Properties();
                        p.load(in);
                        log.info("Diagnostics: classpath app.properties gateway.bolt11={} gateway.bolt11.sat={}",
                                p.getProperty("gateway.bolt11"), p.getProperty("gateway.bolt11.sat"));
                    } else {
                        log.warn("Diagnostics: classpath app.properties not found by application classloader");
                    }
                }

                try {
                    Class<?> clazz = Class.forName("xyz.tcheeric.payment.adapter.ln.phoenixd.PhoenixdGateway");
                    Package pkg = clazz.getPackage();
                    log.info("Diagnostics: PhoenixdGateway present. package={} version={}",
                            (pkg != null ? pkg.getName() : "n/a"), (pkg != null ? pkg.getImplementationVersion() : "n/a"));
                } catch (Throwable t) {
                    log.warn("Diagnostics: PhoenixdGateway class not found on classpath");
                }
            } catch (Exception e) {
                log.warn("Diagnostics: failed to collect startup diagnostics", e);
            }
        };
    }
}
