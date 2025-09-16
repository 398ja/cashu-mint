# Connect the admin CLI to the REST service

The CLI ships with stub ports so you can rehearse workflows locally, but production usage requires calling the administrative REST API. This guide replaces the stub implementations with adapters that authenticate against the `/admin` endpoints exposed by the REST module (see [`MintAdminCliApplication.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/MintAdminCliApplication.java) and the [REST API reference](../reference/rest-api.md)).

## 1. Implement HTTP-backed ports

Each CLI subcommand depends on one of four port interfaces. Create classes that implement these interfaces and translate requests into HTTP calls:

* `MintStatusPort` → `GET`/`POST` an endpoint that returns mint health snapshots (interface defined in [`MintStatusPort.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/port/MintStatusPort.java)).
* `MintConfigPort` → call `/admin/configuration/mints/{mintId}/apply` (or preview/rollback) to manage revisions (see [`MintConfigPort.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/port/MintConfigPort.java) and the [configuration endpoints](../reference/rest-api.md#administrative-endpoints)).
* `MintUsersPort` → wrap the `/admin/users` family for provisioning and listing operator accounts (interface defined in [`MintUsersPort.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/port/MintUsersPort.java); refer to the [user endpoints](../reference/rest-api.md#administrative-endpoints)).
* `MintAlertsPort` → integrate with `/admin/alerts` to acknowledge, silence, or escalate incidents (see [`MintAlertsPort.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/port/MintAlertsPort.java) and the [alert endpoints](../reference/rest-api.md#administrative-endpoints)).

The API requires the `X-Admin-Token` header, so read the token from configuration or the environment when creating your HTTP client (documented in the [REST API reference](../reference/rest-api.md#authentication)). A minimal Java 21 adapter using `HttpClient` might look like this:

```java
public final class RestMintStatusPort implements MintStatusPort {
    private final HttpClient client = HttpClient.newHttpClient();
    private final URI baseUri;
    private final String adminToken;

    public RestMintStatusPort(URI baseUri, String adminToken) {
        this.baseUri = baseUri;
        this.adminToken = adminToken;
    }

    @Override
    public MintStatusResponse fetchStatus(MintStatusRequest request) {
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(baseUri.resolve("/admin/lifecycle/mints/" + request.mintId()))
            .header("X-Admin-Token", adminToken)
            .build();
        try {
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return new ObjectMapper().readValue(response.body(), MintStatusResponse.class);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to fetch mint status", e);
        }
    }
}
```

Follow the same pattern for the other ports, reusing the CLI’s JSON mapper if you prefer (`CommandPayloadMapper.createDefault()` exposes the configured `ObjectMapper`; see [`CommandPayloadMapper.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/io/CommandPayloadMapper.java)).

## 2. Register the adapters with Picocli

Construct a new command line that injects your adapters instead of the stub classes. You can either modify `MintAdminCliApplication.defaultCommandLine()` or create a separate bootstrapper that delegates to `buildCommandLine` with your ports (see [`MintAdminCliApplication.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/MintAdminCliApplication.java)).

```java
public final class RestBackedMintAdminCli {
    public static void main(String[] args) {
        CommandPayloadMapper payloadMapper = CommandPayloadMapper.createDefault();
        ResponseRenderingService rendering = ResponseRenderingService.createDefault(payloadMapper.jsonMapper());
        MintStatusPort status = new RestMintStatusPort(baseUri(), adminToken());
        MintConfigPort config = new RestMintConfigPort(baseUri(), adminToken());
        MintUsersPort users = new RestMintUsersPort(baseUri(), adminToken());
        MintAlertsPort alerts = new RestMintAlertsPort(baseUri(), adminToken());
        new CommandLine(MintAdminCliApplication
            .buildCommandLine(payloadMapper, rendering, status, config, users, alerts))
            .execute(args);
    }
}
```

Bundle the helper methods (`baseUri()`, `adminToken()`) to read environment variables or configuration files so the CLI stays portable across environments.

## 3. Validate the integration

Run the CLI against a development REST instance and exercise each command:

1. `mint` should return the real lifecycle state returned by `/admin/lifecycle/mints/{mintId}` (see the [lifecycle endpoints](../reference/rest-api.md#administrative-endpoints)).
2. `mint config` should apply or preview revisions using the configuration endpoints (see the [configuration documentation](../reference/rest-api.md#administrative-endpoints)).
3. `mint users` and `mint alerts` should manipulate operator and alert data via their respective endpoints (see the [user and alert sections](../reference/rest-api.md#administrative-endpoints)).

If a call fails, inspect the HTTP response for authentication errors or validation messages. Once everything works, commit your adapters alongside environment-specific configuration so other operators can reuse the integration.
