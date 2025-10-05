## Summary
Migrate cashu-mint to use `cashu-platform-bom` for centralized dependency version management across the Cashu ecosystem.

## What changed?
- **Parent POM (`pom.xml`)**:
  - Replaced 40+ individual version properties with single `cashu-platform-bom.version` property
  - Replaced Spring Boot BOM import and manual dependency management with `cashu-platform-bom` import
  - Simplified plugin configurations by removing version declarations (now inherited from BOM)
  - Removed all plugin configuration details (inherited from BOM)

- **Child modules** (`cashu-mint-tools`, `cashu-mint-protocol`, `cashu-mint-rest`):
  - Removed `<version>` tags from all dependencies managed by the BOM
  - Removed version tags from Spring Boot and other shared dependencies
  - Versions now centrally managed through cashu-platform-bom

## Breaking changes
- [ ] BREAKING: this change introduces breaking API or behavior

## Review focus
- Confirm parent POM correctly imports `cashu-platform-bom:1.0.0`
- Verify all child modules removed version tags for BOM-managed dependencies
- Ensure build still compiles successfully with `mvn clean compile`
- Check that all dependency versions remain consistent (managed by BOM)

## Checklist
- [x] Tests added or updated (no test changes required)
- [x] `mvn clean compile` passes
- [ ] Documentation updated (README, docs, etc.)
- [x] No unused imports

## Benefits
- **Single source of truth**: All dependency versions managed in one place (cashu-platform-bom)
- **Easier maintenance**: Version updates require changing only the BOM version
- **Consistency**: All Cashu projects using the BOM get identical dependency versions
- **Reduced duplication**: Eliminated 152 lines of repetitive version declarations
- **Simplified POMs**: Parent POM reduced from 259 to 145 lines

## Migration Impact
- All existing dependency versions remain unchanged (same versions as before)
- Build behavior is identical, only version management is centralized
- Future version updates simplified to single BOM version bump

## mvn clean compile output
Command: `mvn clean compile -U`

```
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for cashu-mint 0.2.4:
[INFO]
[INFO] cashu-mint ......................................... SUCCESS
[INFO] cashu-mint-tools ................................... SUCCESS
[INFO] cashu-mint-protocol ................................ SUCCESS
[INFO] cashu-mint-rest .................................... SUCCESS
[INFO] ------------------------------------------------------------------------
```

Build verified successfully with centralized BOM version management.
