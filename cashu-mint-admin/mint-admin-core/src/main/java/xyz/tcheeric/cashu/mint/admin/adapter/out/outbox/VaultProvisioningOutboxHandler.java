package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationSetRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEventPublisher;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.VaultProvisioningPort;
import xyz.tcheeric.cashu.mint.admin.application.service.ExecuteOperationalControlsInteractor;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

/**
 * Handles outbox messages related to vault provisioning as part of the create-mint saga.
 * <p>
 * On {@code CREATED}: provisions the vault and transitions to {@code PROVISIONED} or
 * {@code PROVISION_FAILED} after exhausting retries.
 * On {@code RETIRED}: archives the vault keyset.
 */
public class VaultProvisioningOutboxHandler implements OutboxMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(VaultProvisioningOutboxHandler.class);
    private static final String DEFAULT_UNIT = "sat";
    private static final List<Integer> DEFAULT_DENOMINATIONS = List.of(1, 2, 4, 8, 16, 32, 64, 128);
    private static final String CONFIG_UNIT = "cashu.unit";
    private static final String CONFIG_DENOMINATIONS = "cashu.denominations";

    private static final String KEYS_ROTATED_EVENT = ExecuteOperationalControlsInteractor.KEYS_ROTATED_EVENT;
    private static final String CONTROL_ID_ATTRIBUTE = ExecuteOperationalControlsInteractor.CONTROL_ID_ATTRIBUTE;

    private final VaultProvisioningPort vaultPort;
    private final OperationalControlRepository operationalControlRepository;
    private final MintRepository mintRepository;
    private final ConfigurationSetRepository configurationSetRepository;
    private final MintLifecycleEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxRetries;

    public VaultProvisioningOutboxHandler(final VaultProvisioningPort vaultPort,
                                          final MintRepository mintRepository,
                                          final ConfigurationSetRepository configurationSetRepository,
                                          final OperationalControlRepository operationalControlRepository,
                                          final MintLifecycleEventPublisher eventPublisher,
                                          final ObjectMapper objectMapper,
                                          final Clock clock,
                                          final int maxRetries) {
        this.vaultPort = Objects.requireNonNull(vaultPort, "vault provisioning port must not be null");
        this.mintRepository = Objects.requireNonNull(mintRepository, "mint repository must not be null");
        this.configurationSetRepository = Objects.requireNonNull(configurationSetRepository,
            "configuration set repository must not be null");
        this.operationalControlRepository = Objects.requireNonNull(operationalControlRepository,
            "operational control repository must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "event publisher must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        if (maxRetries < 1) {
            throw new IllegalArgumentException("max retries must be at least 1");
        }
        this.maxRetries = maxRetries;
    }

    @Override
    public void handle(final OutboxMessage message) {
        Objects.requireNonNull(message, "outbox message must not be null");
        final String eventType = message.eventType();

        if ("CREATED".equals(eventType)) {
            handleCreated(message);
        } else if ("RETIRED".equals(eventType)) {
            handleRetired(message);
        } else if (KEYS_ROTATED_EVENT.equals(eventType)) {
            handleKeysRotated(message);
        }
    }

    private void handleCreated(final OutboxMessage message) {
        final MintId mintId = parseMintId(message);
        final UUID mintUuid = UUID.fromString(mintId.asString());

        final String unit = resolveUnit(mintId);
        final List<Integer> denominations = resolveDenominations(mintId);

        try {
            vaultPort.provision(mintUuid, unit, denominations);
            transitionToProvisioned(mintId, message);
        } catch (final Exception e) {
            final int attempts = message.deliveryAttempts() + 1;
            if (attempts >= maxRetries) {
                log.error("Vault provisioning permanently failed for mint {} after {} attempts",
                    mintId.asString(), attempts, e);
                compensateAndFail(mintId, mintUuid, message);
            } else {
                log.warn("Vault provisioning failed for mint {} (attempt {}/{}): {}",
                    mintId.asString(), attempts, maxRetries, e.getMessage());
                throw new OutboxMessageHandlingException(
                    "Vault provisioning failed for mint " + mintId.asString(), e);
            }
        }
    }

    private void handleKeysRotated(final OutboxMessage message) {
        final MintId mintId = parseMintId(message);
        final UUID mintUuid = UUID.fromString(mintId.asString());
        final String controlId = message.attributes().get(CONTROL_ID_ATTRIBUTE);
        if (controlId == null || controlId.isBlank()) {
            throw new OutboxMessageHandlingException(
                "Key rotation message is missing " + CONTROL_ID_ATTRIBUTE + ": " + message.eventId());
        }

        final String unit = resolveUnit(mintId);
        final List<Integer> denominations = resolveDenominations(mintId);

        try {
            // Keyed on the control id, so a redelivered message derives the same
            // keyset instead of minting a second one.
            final VaultProvisioningPort.RotationResult result =
                vaultPort.rotate(mintUuid, unit, denominations, controlId);
            recordRotationOutcome(controlId, "KEY_ROTATION_COMPLETED",
                "Keyset " + result.newKeySetId() + " replaces " + result.previousKeySetIds());
            log.info("Key rotation completed for mint {}: {} replaces {}",
                mintId.asString(), result.newKeySetId(), result.previousKeySetIds());
        } catch (final Exception e) {
            final int attempts = message.deliveryAttempts() + 1;
            if (attempts >= maxRetries) {
                log.error("Key rotation permanently failed for mint {} after {} attempts",
                    mintId.asString(), attempts, e);
                recordRotationOutcome(controlId, "KEY_ROTATION_FAILED", e.getMessage());
                return;
            }
            log.warn("Key rotation failed for mint {} (attempt {}/{}): {}",
                mintId.asString(), attempts, maxRetries, e.getMessage());
            throw new OutboxMessageHandlingException(
                "Key rotation failed for mint " + mintId.asString(), e);
        }
    }

    private void recordRotationOutcome(final String controlId, final String status, final String outcome) {
        operationalControlRepository.findById(controlId).ifPresent(control ->
            operationalControlRepository.update(new OperationalControlRepository.OperationalControlRecord(
                control.controlId(),
                control.mintId(),
                control.operatorId(),
                control.controlType(),
                status,
                control.scheduledAt(),
                control.reason(),
                control.durationMinutes(),
                outcome)));
    }

    private void handleRetired(final OutboxMessage message) {
        final MintId mintId = parseMintId(message);
        try {
            vaultPort.archive(UUID.fromString(mintId.asString()));
        } catch (final Exception e) {
            log.warn("Vault archive failed for mint {}: {}", mintId.asString(), e.getMessage());
            throw new OutboxMessageHandlingException(
                "Vault archive failed for mint " + mintId.asString(), e);
        }
    }

    private void transitionToProvisioned(final MintId mintId, final OutboxMessage message) {
        final MintAggregate aggregate = mintRepository.findById(mintId)
            .orElseThrow(() -> new OutboxMessageHandlingException(
                "Mint not found during provisioning: " + mintId.asString()));
        final AuditMetadata audit = new AuditMetadata("system", "Vault provisioned", clock.instant());
        final MintAggregate provisioned = aggregate.markProvisioned(audit);
        mintRepository.save(provisioned);

        final String versionTag = extractVersionTag(message);
        eventPublisher.publish(MintLifecycleEvent.vaultProvisioned(
            mintId,
            aggregate.lifecycleState().value(),
            provisioned.lifecycleState().value(),
            provisioned.configurationSet().revisionId(),
            versionTag,
            provisioned.auditMetadata()));
    }

    private void compensateAndFail(final MintId mintId, final UUID mintUuid, final OutboxMessage message) {
        try {
            vaultPort.compensate(mintUuid);
        } catch (final Exception ex) {
            log.warn("Vault compensation also failed for mint {}: {}", mintId.asString(), ex.getMessage());
        }

        final MintAggregate aggregate = mintRepository.findById(mintId).orElse(null);
        if (aggregate != null) {
            final AuditMetadata audit = new AuditMetadata("system", "Vault provisioning failed", clock.instant());
            final MintAggregate failed = aggregate.markProvisionFailed(audit);
            mintRepository.save(failed);

            final String versionTag = extractVersionTag(message);
            eventPublisher.publish(MintLifecycleEvent.vaultProvisionFailed(
                mintId,
                aggregate.lifecycleState().value(),
                failed.lifecycleState().value(),
                failed.configurationSet().revisionId(),
                versionTag,
                failed.auditMetadata()));
        }
    }

    private MintId parseMintId(final OutboxMessage message) {
        try {
            final JsonNode node = objectMapper.readTree(message.payload());
            final String mintIdStr = node.get("mintId").asText();
            return MintId.fromString(mintIdStr);
        } catch (final JsonProcessingException e) {
            throw new OutboxMessageHandlingException("Failed to parse mint id from outbox payload", e);
        }
    }

    private String extractVersionTag(final OutboxMessage message) {
        try {
            final JsonNode node = objectMapper.readTree(message.payload());
            final JsonNode tag = node.get("versionTag");
            return tag != null ? tag.asText("v1") : "v1";
        } catch (final JsonProcessingException e) {
            return "v1";
        }
    }

    private String resolveUnit(final MintId mintId) {
        final List<ConfigurationSet> configs = configurationSetRepository.findByMintId(mintId);
        if (!configs.isEmpty()) {
            final ConfigurationSet latest = configs.getLast();
            final String unit = latest.parameters().get(CONFIG_UNIT);
            if (unit != null && !unit.isBlank()) {
                return unit;
            }
        }
        return DEFAULT_UNIT;
    }

    private List<Integer> resolveDenominations(final MintId mintId) {
        final List<ConfigurationSet> configs = configurationSetRepository.findByMintId(mintId);
        if (!configs.isEmpty()) {
            final ConfigurationSet latest = configs.getLast();
            final String denomStr = latest.parameters().get(CONFIG_DENOMINATIONS);
            if (denomStr != null && !denomStr.isBlank()) {
                return Arrays.stream(denomStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .toList();
            }
        }
        return DEFAULT_DENOMINATIONS;
    }
}
