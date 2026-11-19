package xyz.tcheeric.cashu.mint.rest.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.core.userdetails.UserDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The voucher issuance record is readable by the gateway's issuance-reader identity and by an
 * operator, and by nobody else. The reader identity can read nothing but that record.
 *
 * <p>Statuses other than 401 and 403 mean the request passed the security chain; whether the
 * controller then finds a quote is not what this test is about, and depends on whether the JPA
 * module is wired in the test profile.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "voucher.enabled=false",
        "cashu.mint.admin.username=admin",
        "cashu.mint.admin.password={noop}test-operator-password",
        "cashu.mint.issuance-reader.username=gateway-core",
        "cashu.mint.issuance-reader.password={noop}test-reader-password"
})
class IssuanceReaderSecurityTest {

    private static final String RECORD = "/v1/vouchers/quotes/6f1c2b9e-1d2a-4c3b-9f8e-7a6b5c4d3e2f/issuance";

    @Autowired
    private TestRestTemplate restTemplate;

    private HttpStatusCode get(TestRestTemplate as, String path) {
        return as.getForEntity(path, String.class).getStatusCode();
    }

    @Test
    @DisplayName("an anonymous caller is refused the issuance record")
    void anonymousIsRefused() {
        assertThat(get(restTemplate, RECORD)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a wrong password is refused the issuance record")
    void wrongPasswordIsRefused() {
        assertThat(get(restTemplate.withBasicAuth("gateway-core", "nope"), RECORD))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the issuance reader passes the security chain for the issuance record")
    void readerMayRead() {
        assertThat(get(restTemplate.withBasicAuth("gateway-core", "test-reader-password"), RECORD))
                .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an operator still passes the security chain for the issuance record")
    void adminMayRead() {
        assertThat(get(restTemplate.withBasicAuth("admin", "test-operator-password"), RECORD))
                .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the issuance reader is forbidden every other protected voucher route")
    void readerIsForbiddenOtherVoucherRoutes() {
        TestRestTemplate reader = restTemplate.withBasicAuth("gateway-core", "test-reader-password");
        assertThat(get(reader, "/v1/vouchers/6f1c2b9e-1d2a-4c3b-9f8e-7a6b5c4d3e2f"))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(reader, "/admin/voucher/forensic/customer-purchases"))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the issuance reader may not write: the record is GET only")
    void readerCannotPost() {
        TestRestTemplate reader = restTemplate.withBasicAuth("gateway-core", "test-reader-password");
        HttpStatusCode status = reader.postForEntity(RECORD, "{}", String.class).getStatusCode();
        assertThat(status).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the reader must not share the admin username")
    void readerCannotShadowAdmin() {
        assertThatThrownBy(() -> SecurityConfig.issuanceReader("admin", "x", "admin"))
                .isInstanceOf(IllegalStateException.class);
        UserDetails none = SecurityConfig.issuanceReader("gateway-core", "", "admin");
        assertThat(none).isNull();
    }
}
