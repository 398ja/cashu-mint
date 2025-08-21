# Repo Guidelines

## Description

- When implementing features, consult the NUT specifications:
    - [NUT-00](https://github.com/cashubtc/nuts/blob/main/00.md)
    - [NUT-01](https://github.com/cashubtc/nuts/blob/main/01.md)
    - [NUT-02](https://github.com/cashubtc/nuts/blob/main/02.md)
    - [NUT-03](https://github.com/cashubtc/nuts/blob/main/03.md)
    - [NUT-04](https://github.com/cashubtc/nuts/blob/main/04.md)
    - [NUT-05](https://github.com/cashubtc/nuts/blob/main/05.md)
    - [NUT-06](https://github.com/cashubtc/nuts/blob/main/06.md)
    - [NUT-07](https://github.com/cashubtc/nuts/blob/main/07.md)
    - [NUT-08](https://github.com/cashubtc/nuts/blob/main/08.md)
    - [NUT-09](https://github.com/cashubtc/nuts/blob/main/09.md)
    - [NUT-10](https://github.com/cashubtc/nuts/blob/main/10.md)
    - [NUT-11](https://github.com/cashubtc/nuts/blob/main/11.md)
    - [NUT-12](https://github.com/cashubtc/nuts/blob/main/12.md)
    - [NUT-13](https://github.com/cashubtc/nuts/blob/main/13.md)
    - [NUT-14](https://github.com/cashubtc/nuts/blob/main/14.md)
    - [NUT-15](https://github.com/cashubtc/nuts/blob/main/15.md)
    - [NUT-16](https://github.com/cashubtc/nuts/blob/main/16.md)
    - [NUT-17](https://github.com/cashubtc/nuts/blob/main/17.md)
    - [NUT-18](https://github.com/cashubtc/nuts/blob/main/18.md)
    - [NUT-19](https://github.com/cashubtc/nuts/blob/main/19.md)
    - [NUT-20](https://github.com/cashubtc/nuts/blob/main/20.md)
    - [NUT-21](https://github.com/cashubtc/nuts/blob/main/21.md)
    - [NUT-22](https://github.com/cashubtc/nuts/blob/main/22.md)
    - [NUT-23](https://github.com/cashubtc/nuts/blob/main/23.md)
    - [NUT-24](https://github.com/cashubtc/nuts/blob/main/24.md)


## Testing

- Always run `mvn -q verify` from the repository root before committing your changes.
- Include the command's output in the PR description.
- If tests fail due to dependency or network issues, mention this in the PR.
- Update the `README.md` and/or `docs/CODEBASE_OVERVIEW.md` file if you add or modify features.
- Update the `pom.xml` file for new modules or dependencies, ensuring compatibility with Java 21.
- Add unit tests for new functionality, covering edge cases.
- Ensure modifications to existing code do not break functionality and pass all tests.
- Add integration tests for new features to verify end-to-end functionality.
- Ensure new dependencies or configurations do not introduce security vulnerabilities.
- Maintain the versions in the configuration section of the pom.xml files.
- Always make sure that the events are compliant with the Nostr protocol specifications, and that the events are valid according to the NIP specifications.
- Always remove unused imports

## Pull Requests

- Always follow the PR submission guidelines and use the [PR template](.github/pull_request_template.md) when creating pull requests.
- Summarize the changes made and describe how they were tested.
- Include any limitations or known issues in the description.
- Add a "Network Access" section summarizing blocked domains if network requests were denied.
- Ensure all new features, modules, or dependencies are properly documented in the `README.md` file.
- Ensure all new features are compliant with the API specification provided above.
