# Publish a Docker image

This how-to guide shows how to build and publish the `cashu-mint-rest` Docker image to `docker.398ja.xyz` using the Jib Maven plugin.

## Prerequisites

- Java 21 and Maven installed
- Credentials for `docker.398ja.xyz`
- Environment variables with registry credentials:

```bash
export JIB_TO_AUTH_USERNAME=<registry-user>
export JIB_TO_AUTH_PASSWORD=<registry-password>
```

## Steps

1. Build and push the image with Jib:

    ```bash
    mvn -pl cashu-mint-rest -am jib:build
    ```

2. The image `docker.398ja.xyz/cashu-mint-rest` is pushed and tagged with the current project version and `latest`.

## Verify

Pull the image to confirm it was published:

```bash
docker pull docker.398ja.xyz/cashu-mint-rest:latest
```
