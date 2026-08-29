package xyz.tcheeric.cashu.mint.proto.error;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

/**
 * The NUT-00 error body: a human-readable {@code detail} and a numeric {@code code}.
 *
 * <p>This replaces the mint's own {@code code}/{@code message} string pair. The {@code code} key
 * survives the change but its type does not: it is now the integer registered in
 * <a href="https://github.com/cashubtc/nuts/blob/main/error_codes.md">error_codes.md</a>, or a
 * code from the extension range cashu-lib reserves for failures the registry does not name.
 *
 * <p>A body is only ever derived from a {@link CashuErrorCode}, never from a free-form string, so
 * an unrecognised code cannot reach a client. Failures carrying no code at all are genuine
 * internal faults and are reported as {@link CashuErrorCode#internal_error}.
 */
@JsonPropertyOrder({"detail", "code"})
public record ErrorResponse(String detail, int code) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The body for a code, using the code's own default detail. */
    public static ErrorResponse of(@NonNull CashuErrorCode errorCode) {
        return new ErrorResponse(errorCode.getDefaultDetail(), errorCode.getCode());
    }

    /** The body for a code, with a detail naming the specific failure. */
    public static ErrorResponse of(@NonNull CashuErrorCode errorCode, @NonNull String detail) {
        return new ErrorResponse(detail, errorCode.getCode());
    }

    /**
     * The body for a protocol failure.
     *
     * <p>An exception raised without a code predates typed codes or wraps a fault of ours; either
     * way it is not a statement about the client's request, so it becomes an internal error rather
     * than leaking an arbitrary message under a protocol code.
     */
    public static ErrorResponse from(@NonNull CashuErrorException ex) {
        if (!ex.hasErrorCode()) {
            return of(CashuErrorCode.internal_error);
        }
        String detail = ex.getMessage();
        return detail == null || detail.isBlank()
                ? of(ex.getErrorCode())
                : of(ex.getErrorCode(), detail);
    }

    /**
     * Serialises the payload through Jackson rather than string concatenation. A detail
     * containing a quote or a backslash produced invalid JSON when this was built with
     * {@code String.format}, which turned a reportable error into an unparseable body.
     */
    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("error response could not be serialised: " + code, e);
        }
    }
}
