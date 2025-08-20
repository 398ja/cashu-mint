# GitHub Copilot Instructions

This repository implements the [Nostr protocol](https://github.com/nostr-protocol/nips). When using GitHub Copilot,
keep the following guidelines in mind:

- Adhere to the [NIP specifications](https://github.com/nostr-protocol/nips/blob/master/01.md) and other relevant NIPs.
- Run `mvn -q verify` before committing code.
- Document new features in the README or related docs.
- Ensure events remain compliant with the NIP guidelines and remove unused imports.
- Maintain Java 21 compatibility and update `pom.xml` for new dependencies.

These instructions help Copilot produce code that respects the repository's conventions and protocol requirements.
