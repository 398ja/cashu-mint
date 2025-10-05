## Summary
Bump project versions across parent and modules to 0.2.4 to prepare the next patch release.

## What changed?
- Updated parent `pom.xml:7` project version from `0.2.3` to `0.2.4`.
- Updated module versions and parent references:
  - `cashu-mint-rest/pom.xml:6` parent version to `0.2.4` and `cashu-mint-rest/pom.xml:9` project version to `0.2.4`.
  - `cashu-mint-tools/pom.xml:6` parent version to `0.2.4` and `cashu-mint-tools/pom.xml:11` project version to `0.2.4`.
  - `cashu-mint-protocol/pom.xml:6` parent version to `0.2.4` and `cashu-mint-protocol/pom.xml:11` project version to `0.2.4`.

## Breaking changes
- [ ] BREAKING: this change introduces breaking API or behavior

## Review focus
- Confirm all modules consistently reference `0.2.4` for both parent and artifact versions.
- Ensure no unrelated changes were introduced.

## Checklist
- [ ] Tests added or updated
- [ ] `mvn -q verify` passes
- [ ] Documentation updated (README, docs, etc.)
- [ ] No unused imports

## mvn -q verify output
Command: `./mvnw -q -DskipITs verify`

```
SLF4J(W): No SLF4J providers were found.
SLF4J(W): Defaulting to no-operation (NOP) logger implementation
SLF4J(W): See https://www.slf4j.org/codes.html#noProviders for further details.
[ERROR] Failed to execute goal on project cashu-mint-protocol: Could not resolve dependencies for project xyz.tcheeric:cashu-mint-protocol:jar:0.2.4
[ERROR] dependency: xyz.tcheeric:cashu-gateway-common:jar:0.3.2 (compile)
[ERROR] 	xyz.tcheeric:cashu-gateway-common:jar:0.3.2 was not found in https://maven.398ja.xyz/releases during a previous attempt. This failure was cached in the local repository and resolution is not reattempted until the update interval of reposilite-releases has elapsed or updates are forced
[ERROR] 	xyz.tcheeric:cashu-gateway-common:jar:0.3.2 was not found in https://maven.398ja.xyz/snapshots during a previous attempt. This failure was cached in the local repository and resolution is not reattempted until the update interval of reposilite-snapshots has elapsed or updates are forced
[ERROR] 	xyz.tcheeric:cashu-gateway-common:jar:0.3.2 was not found in https://repo.maven.apache.org/maven2 during a previous attempt. This failure was cached in the local repository and resolution is not reattempted until the update interval of central has elapsed or updates are forced
[ERROR] dependency: xyz.tcheeric:cashu-gateway-phoenixd:jar:0.3.2 (compile)
[ERROR] 	xyz.tcheeric:cashu-gateway-phoenixd:jar:0.3.2 was not found in https://maven.398ja.xyz/releases during a previous attempt. This failure was cached in the local repository and resolution is not reattempted until the update interval of reposilite-releases has elapsed or updates are forced
[ERROR] 	xyz.tcheeric:cashu-gateway-phoenixd:jar:0.3.2 was not found in https://maven.398ja.xyz/snapshots during a previous attempt. This failure was cached in the local repository and resolution is not reattempted until the update interval of reposilite-snapshots has elapsed or updates are forced
[ERROR] 	xyz.tcheeric:cashu-gateway-phoenixd:jar:0.3.2 was not found in https://repo.maven.apache.org/maven2 during a previous attempt. This failure was cached in the local repository and resolution is not reattempted until the update interval of central has elapsed or updates are forced
[ERROR] dependency: xyz.tcheeric:cashu-gateway-dummy:jar:0.3.2 (compile)
[ERROR] 	xyz.tcheeric:cashu-gateway-dummy:jar:0.3.2 was not found in https://maven.398ja.xyz/releases during a previous attempt. This failure was cached in the local repository and resolution is not reattempted until the update interval of reposilite-releases has elapsed or updates are forced
[ERROR] 	xyz.tcheeric:cashu-gateway-dummy:jar:0.3.2 was not found in https://maven.398ja.xyz/snapshots during a previous attempt. This failure was cached in the local repository and resolution is not reattempted until the update interval of reposilite-snapshots has elapsed or updates are forced
[ERROR] 	xyz.tcheeric:cashu-gateway-dummy:jar:0.3.2 was not found in https://repo.maven.apache.org/maven2 during a previous attempt. This failure was cached in the local repository and resolution is not reattempted until the update interval of central has elapsed or updates are forced
[ERROR] 
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/DependencyResolutionException
[ERROR] 
[ERROR] After correcting the problems, you can resume the build with the command
[ERROR]   mvn <args> -rf :cashu-mint-protocol
```

Note: The verify step failed due to dependency resolution/network restrictions in this environment. Locally or in CI with access to `https://maven.398ja.xyz/` and the required artifacts, the build should resolve correctly.
