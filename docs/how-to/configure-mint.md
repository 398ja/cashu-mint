# Configure the mint

This guide shows how to override mint configuration properties using environment variables or Java system properties.

1. Identify the property to change from the [configuration reference](../reference/configuration.md).
2. Choose an override method:
   - **Environment variable** – convert the property name to upper case and replace dots with underscores.
   - **Java system property** – pass the property with `-D` when launching the application.
3. Start the application with the overrides applied.

### Example: change the server port

#### Using an environment variable

```bash
export CASHU_MINT_PORT=8888
java -jar cashu-mint-rest.jar
```

#### Using a system property

```bash
java -Dserver.port=8888 -jar cashu-mint-rest.jar
```

Both methods override the HTTP port for the REST API.
