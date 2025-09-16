package xyz.tcheeric.cashu.mint.admin.application.service;

import java.util.Objects;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Base helper with shared validation logic for admin use case interactors.
 */
abstract class AbstractUseCaseInteractor {

    protected <T> T requireRequest(final T request, final String name) {
        return Objects.requireNonNull(request, name + " must not be null");
    }

    protected MintId validateMintId(final String rawMintId) {
        return MintId.fromString(rawMintId);
    }

    protected UUID validateUuid(final String rawIdentifier, final String fieldName) {
        if (rawIdentifier == null || rawIdentifier.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        try {
            return UUID.fromString(rawIdentifier);
        } catch (final IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid UUID", ex);
        }
    }

    protected ConfigurationRevisionId validateConfigurationRevision(final String rawRevision) {
        if (rawRevision == null || rawRevision.isBlank()) {
            throw new IllegalArgumentException("configuration revision must not be blank");
        }
        try {
            final long numericRevision = Long.parseLong(rawRevision);
            return ConfigurationRevisionId.of(numericRevision);
        } catch (final NumberFormatException ex) {
            throw new IllegalArgumentException("configuration revision must be a positive integer", ex);
        }
    }

    protected String validateVersionTag(final String versionTag) {
        if (versionTag == null || versionTag.isBlank()) {
            throw new IllegalArgumentException("version tag must not be blank");
        }
        return versionTag;
    }
}
