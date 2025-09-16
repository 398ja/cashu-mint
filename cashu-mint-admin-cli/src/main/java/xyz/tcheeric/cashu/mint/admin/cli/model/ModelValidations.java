package xyz.tcheeric.cashu.mint.admin.cli.model;

final class ModelValidations {

    private ModelValidations() {
    }

    static String requireMintId(final String mintId) {
        if (mintId == null || mintId.isBlank()) {
            throw new IllegalArgumentException("mintId must not be blank");
        }
        return mintId;
    }

    static String requireText(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    static String optionalText(final String value, final String fieldName) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
