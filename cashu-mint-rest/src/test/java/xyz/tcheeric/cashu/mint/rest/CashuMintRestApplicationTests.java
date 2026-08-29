package xyz.tcheeric.cashu.mint.rest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Basic Spring Boot application context test.
 *
 * <p>This test verifies that the Spring application context loads successfully
 * with all required beans and configurations. Voucher functionality is disabled
 * for this test to avoid requiring external dependencies like Nostr relays.
 *
 * <p>For voucher-specific integration tests, see VoucherNostrIT.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"voucher.enabled=false"})
class CashuMintRestApplicationTests {

    @Test
    void contextLoads() {
        // Test passes if Spring context loads successfully
    }

}
