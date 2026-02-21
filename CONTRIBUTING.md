# Contributing to cashu-mint

Thank you for your interest in contributing to cashu-mint. This guide covers the process for reporting issues, submitting changes, and getting your work merged.

## Getting Started

1. Fork the repository and clone your fork.
2. Set up your development environment following the [development workflow guide](docs/how-to/development-workflow.md).
3. Create a branch from `develop` using the naming conventions below.

## Branch Naming

| Prefix | Use |
|--------|-----|
| `feature/` | New functionality (e.g. `feature/nut-10-spending-conditions`) |
| `fix/` | Bug fixes (e.g. `fix/swap-amount-validation`) |
| `chore/` | Maintenance, dependency updates, CI changes |
| `docs/` | Documentation-only changes |
| `refactor/` | Code restructuring without behavior change |

## Commit Messages

This project enforces [Conventional Commits](https://www.conventionalcommits.org/) via GitHub Actions. Every commit message must follow:

```
type(scope): description

Examples:
feat(nut): implement NUT-10 spending conditions
fix(protocol): correct signature verification in swap
chore(pom): update cashu-lib to 0.6.1
docs(how-to): add gateway configuration guide
test(protocol): add edge cases for melt validation
refactor(rest): extract mint resolution into service
```

**Types:** `feat`, `fix`, `chore`, `docs`, `test`, `refactor`, `style`, `perf`

## Pull Request Process

1. Run the full build before opening a PR:
   ```bash
   mvn -q verify
   ```
2. Push your branch and open a PR against `develop`.
3. Fill in the PR template with a summary of changes and testing performed.
4. Ensure CI checks pass (conventional commits, Google Java Format, unit tests).
5. Address review feedback and keep the branch up to date with `develop`.

## Code Standards

- Java 21 is required.
- Code is formatted with [Google Java Format](https://github.com/google/google-java-format) (enforced by CI).
- Use Lombok to reduce boilerplate.
- Follow Clean Code principles outlined in [AGENTS.md](AGENTS.md).
- Prefer virtual threads for I/O-bound concurrency; use `ReentrantLock` instead of `synchronized` around I/O.

## Testing Expectations

- Every new feature or bug fix must include unit tests.
- Unit test naming: `{ClassName}Test.java`. Integration test naming: `{Feature}IT.java`.
- Every test method must have a comment describing its purpose.
- Target 80%+ line coverage for new code.
- Run integration tests before submitting changes that affect component interactions:
  ```bash
  mvn clean verify -Pintegration-tests
  ```

See [TESTING.md](TESTING.md) for the full testing guide.

## Documentation

Documentation follows the [Diataxis framework](https://diataxis.fr/) and lives under `docs/`. When adding documentation, classify it as a tutorial, how-to, reference, or explanation and place it in the appropriate directory. See [docs/README.md](docs/README.md) for the full index.

## Reporting Issues

Open an issue on GitHub with:
- A clear title describing the problem.
- Steps to reproduce (if applicable).
- Expected vs. actual behavior.
- Environment details (Java version, OS, Docker version).

## See Also

- [Development workflow](docs/how-to/development-workflow.md)
- [Testing guide](TESTING.md)
- [Glossary](docs/reference/glossary.md)
