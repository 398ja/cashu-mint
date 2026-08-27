package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Playwright fixture signs an Operator in without the server, so it carries its own
 * copy of {@link AdminRole}. A copy that drifts signs in an Operator the real deployment
 * never issues — and the suite goes on passing. This is the thing that notices.
 */
class AdminRoleWebFixtureContractTest {

    private static final Path FIXTURE = Path.of("../mint-admin-web/e2e/fixtures/auth.ts");

    private static final Pattern MAP =
        Pattern.compile("const ROLE_PERMISSIONS: Record<string, string\\[]> = \\{(.*?)^};",
            Pattern.DOTALL | Pattern.MULTILINE);
    private static final Pattern ENTRY =
        Pattern.compile("(\\w+):\\s*\\[(.*?)]", Pattern.DOTALL);

    // A role whose permissions the fixture states differently is the drift that matters:
    // the e2e suite would prove a page reachable that the server refuses, or the reverse.
    @Test
    @DisplayName("The e2e fixture's role-to-permission map still matches AdminRole")
    void fixtureMatchesTheRoles() throws IOException {
        assumeTrue(Files.exists(FIXTURE), "mint-admin-web is not checked out");

        final Map<String, Set<String>> expected = Arrays.stream(AdminRole.values())
            .collect(Collectors.toMap(AdminRole::key,
                r -> r.permissions().stream().map(AdminPermission::key)
                    .collect(Collectors.toCollection(TreeSet::new))));

        assertThat(parseFixture(Files.readString(FIXTURE)))
            .as("e2e/fixtures/auth.ts ROLE_PERMISSIONS")
            .isEqualTo(expected);
    }

    private static Map<String, Set<String>> parseFixture(final String source) {
        final Matcher map = MAP.matcher(source);
        assertThat(map.find()).as("ROLE_PERMISSIONS literal in %s", FIXTURE).isTrue();

        final Map<String, Set<String>> parsed = new LinkedHashMap<>();
        final Matcher entry = ENTRY.matcher(map.group(1));
        while (entry.find()) {
            parsed.put(entry.group(1), Arrays.stream(entry.group(2).split(","))
                .map(s -> s.replace("\"", "").trim())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new)));
        }
        return parsed;
    }
}
