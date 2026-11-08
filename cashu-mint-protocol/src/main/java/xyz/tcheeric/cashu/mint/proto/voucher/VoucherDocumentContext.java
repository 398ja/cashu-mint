package xyz.tcheeric.cashu.mint.proto.voucher;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostInputRequest;
import xyz.tcheeric.cashu.voucher.domain.VoucherDocuments;

import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The voucher documents one request carries, already validated, and the rule for reading an
 * input's voucher terms through them (cashu-voucher#64).
 *
 * <p>A by-reference ("v2") unlocked voucher secret carries {@code SHA256(D)} in {@code data}
 * instead of the document {@code D} itself. Every voucher check must run on the document, while
 * the BDHKE check and the proof state key (Y) keep using the wire secret: the mint signed the
 * wire secret, and the wire secret is what is spent. This class only answers the first question.
 * It never replaces a proof's secret.
 *
 * <p>The mint stays stateless: the documents live as long as the request.
 *
 * <p>Only {@link #of(PostInputRequest)} and {@link #none()} build one, so every instance holds a
 * map whose keys were checked against {@code SHA256(value)} by
 * {@link PostInputRequest#voucherDocuments()}. That is why resolution here is a lookup and never
 * hashes again: each document is hashed exactly once per request.
 *
 * <p>A missing document is refused with {@code voucher_document_invalid} (90032), not
 * {@code voucher_signature_invalid}: nothing is wrong with the voucher, the request failed to
 * carry what its own input references. One code for every document fault keeps a wallet's fix
 * obvious (send the document) and keeps it distinct from a forged voucher.
 */
@Slf4j
public final class VoucherDocumentContext {

    private static final VoucherDocumentContext NONE = new VoucherDocumentContext(new TreeMap<>());
    private static final HexFormat HEX = HexFormat.of();

    private final SortedMap<String, byte[]> documents;

    private VoucherDocumentContext(SortedMap<String, byte[]> documents) {
        this.documents = documents;
    }

    /** A request that carries no documents: every by-reference input is refused. */
    public static VoucherDocumentContext none() {
        return NONE;
    }

    /**
     * Validates the request's {@code vouchers} map once.
     *
     * @throws CashuErrorException 90032 when a key is malformed or does not hash its value, a
     *                             value is empty or too large, the total is too large, or there
     *                             are more documents than inputs
     */
    public static VoucherDocumentContext of(@NonNull PostInputRequest<?> request) throws CashuErrorException {
        SortedMap<String, byte[]> validated = request.voucherDocuments();
        if (validated.isEmpty()) {
            return NONE;
        }
        log.debug("voucher_documents_validated count={}", validated.size());
        return new VoucherDocumentContext(validated);
    }

    /** How many documents the request carried. Unreferenced ones are counted and then ignored. */
    public int size() {
        return documents.size();
    }

    /**
     * The secret whose voucher terms are checked for this input.
     *
     * @param wireSecret the input's secret exactly as received
     * @return the wire secret itself unless it is a by-reference voucher, otherwise an inline
     *         voucher secret built from its document (same nonce)
     * @throws CashuErrorException 90032 when the referenced document is not in the request
     */
    public Secret termsSecret(Secret wireSecret) throws CashuErrorException {
        if (!(wireSecret instanceof WellKnownSecret wks) || !VoucherDocuments.isByReference(wks)) {
            return wireSecret;
        }
        String key = HEX.formatHex(wks.getData());
        byte[] document = documents.get(key);
        if (document == null) {
            // The key is a public hash, safe to log; the document and secret never are.
            log.warn("voucher_document_missing key={}", key);
            throw new CashuErrorException(CashuErrorCode.voucher_document_invalid,
                    "Voucher document " + key + " is not in the request");
        }
        return inline(wks, document);
    }

    private static WellKnownSecret inline(WellKnownSecret reference, byte[] document) {
        VoucherSecret resolved = new VoucherSecret();
        resolved.setData(document.clone());
        resolved.setNonce(reference.getNonce());
        return resolved;
    }

    /** Read-only view, for tests and diagnostics. */
    Map<String, byte[]> documents() {
        return java.util.Collections.unmodifiableMap(documents);
    }
}
