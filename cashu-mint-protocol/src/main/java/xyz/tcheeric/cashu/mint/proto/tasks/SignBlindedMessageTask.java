package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.nut12.DLEQProof;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.mint.proto.service.DLEQProofGenerator;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultDLEQProofGenerator;
import xyz.tcheeric.cashu.mint.proto.util.VoucherKeyDerivation;


@Slf4j
public class SignBlindedMessageTask extends InstrumentedTask<BlindSignature> {

    private static final ECNamedCurveParameterSpec CURVE = ECNamedCurveTable.getParameterSpec("secp256k1");

    private final Mint mint;
    private final BlindedMessage blindedMessage;
    private final MintProtocolService mintProtocolService;
    private final SignatureVaultService signatureVaultService;
    private final DLEQProofGenerator dleqProofGenerator;
    private final boolean voucherMode;
    private final String voucherMasterSecret;

    public SignBlindedMessageTask(@NonNull Mint mint,
                                  @NonNull BlindedMessage blindedMessage,
                                  @NonNull MintProtocolService mintProtocolService,
                                  @NonNull SignatureVaultService signatureVaultService) {
        this(mint, blindedMessage, mintProtocolService, signatureVaultService, new DefaultDLEQProofGenerator(), false, null);
    }

    public SignBlindedMessageTask(@NonNull Mint mint,
                                  @NonNull BlindedMessage blindedMessage,
                                  @NonNull MintProtocolService mintProtocolService,
                                  @NonNull SignatureVaultService signatureVaultService,
                                  @NonNull DLEQProofGenerator dleqProofGenerator) {
        this(mint, blindedMessage, mintProtocolService, signatureVaultService, dleqProofGenerator, false, null);
    }

    /**
     * Constructor for voucher mode with arbitrary denomination support.
     *
     * @param mint the mint instance
     * @param blindedMessage the blinded message to sign
     * @param mintProtocolService protocol service for key lookup
     * @param signatureVaultService vault service for storing signatures
     * @param voucherMode if true, derive keys dynamically for arbitrary amounts
     * @param voucherMasterSecret the master secret for voucher key derivation (required if voucherMode is true)
     */
    public SignBlindedMessageTask(@NonNull Mint mint,
                                  @NonNull BlindedMessage blindedMessage,
                                  @NonNull MintProtocolService mintProtocolService,
                                  @NonNull SignatureVaultService signatureVaultService,
                                  boolean voucherMode,
                                  String voucherMasterSecret) {
        this(mint, blindedMessage, mintProtocolService, signatureVaultService, new DefaultDLEQProofGenerator(), voucherMode, voucherMasterSecret);
    }

    public SignBlindedMessageTask(@NonNull Mint mint,
                                  @NonNull BlindedMessage blindedMessage,
                                  @NonNull MintProtocolService mintProtocolService,
                                  @NonNull SignatureVaultService signatureVaultService,
                                  @NonNull DLEQProofGenerator dleqProofGenerator,
                                  boolean voucherMode,
                                  String voucherMasterSecret) {
        this.mint = mint;
        this.blindedMessage = blindedMessage;
        this.mintProtocolService = mintProtocolService;
        this.signatureVaultService = signatureVaultService;
        this.dleqProofGenerator = dleqProofGenerator;
        this.voucherMode = voucherMode;
        this.voucherMasterSecret = voucherMasterSecret;
    }

    @Override
    protected BlindSignature doExecute() throws CashuErrorException {
        if (log.isDebugEnabled()) {
            log.debug("Signing blinded message: amount={} keySetId={} voucherMode={}",
                    blindedMessage.getAmount(), blindedMessage.getKeySetId(), voucherMode);
        }

        PrivateKey privateKey;
        if (voucherMode) {
            // Voucher mode: derive key dynamically for arbitrary amounts
            if (voucherMasterSecret == null || voucherMasterSecret.isEmpty()) {
                ErrorResponse error = new ErrorResponse("voucher_master_secret_missing",
                        "Voucher mode requires a master secret for key derivation");
                throw new CashuErrorException(error.toJson());
            }
            privateKey = VoucherKeyDerivation.deriveKeyForAmount(voucherMasterSecret, blindedMessage.getAmount());
            if (log.isDebugEnabled()) {
                log.debug("Derived voucher key for amount={}", blindedMessage.getAmount());
            }
        } else {
            // An archived keyset must not sign new outputs, however the client asks.
            // Redemption of proofs it already signed is unaffected — ADR-0004.
            mintProtocolService.requireActiveKeySet(blindedMessage.getKeySetId().toString());
            // Regular mode: lookup key from keyset
            privateKey = getPrivateKey(blindedMessage, mint, mintProtocolService);
        }

        if (privateKey == null) {
            ErrorResponse error = new ErrorResponse("sign_private_key_not_found");
            log.warn("Private key not found for amount={} keySetId={}",
                    blindedMessage.getAmount(), blindedMessage.getKeySetId());
            throw new CashuErrorException(error.toJson());
        }

        // PrivateKey implements AutoCloseable, but keyset keys are shared/cached
        // across the mint and must not be closed here. Voucher-derived keys are
        // ephemeral but closing is handled by GC since they don't hold native resources.
        @SuppressWarnings("resource")
        BlindSignature result = signWithKey(privateKey);
        return result;
    }

    private BlindSignature signWithKey(PrivateKey privateKey) throws CashuErrorException {
        byte[] signature = BDHKEUtils.signBlindedMessage(
                blindedMessage.getBlindedMessage().getBytes(),
                privateKey.getBytes()
        );
        if (log.isDebugEnabled()) {
            int len = signature == null ? -1 : signature.length;
            int first = (signature != null && signature.length > 0) ? (signature[0] & 0xFF) : -1;
            String rawHex = signature == null ? "null" : bytesToHex(signature);
            log.debug("Raw blind signature bytes: len={} firstByte=0x{} hex={}", len,
                    (first < 0 ? "--" : String.format("%02x", first)), rawHex);
        }

        // Prefer strict forms and minimal normalization; JSON adapters will ensure serialized hex format.
        Signature sigObj;
        if (signature != null && signature.length == 64) {
            // 64-byte (x||y) form
            sigObj = Signature.fromBytes(signature);
        } else if (signature != null && signature.length == 33 && (signature[0] == 0x02 || signature[0] == 0x03)) {
            // 33-byte compressed point
            sigObj = Signature.fromString(bytesToHex(signature));
        } else if (signature != null && signature.length == 33) {
            // 33 bytes but unexpected prefix: assume first byte is not part of compressed form.
            // Treat remaining 32 bytes as x-only and prepend even prefix 0x02.
            byte[] point = new byte[33];
            point[0] = 0x02;
            System.arraycopy(signature, 1, point, 1, 32);
            sigObj = Signature.fromString(bytesToHex(point));
        } else if (signature != null && signature.length == 32) {
            // x-only: prepend even prefix 0x02
            byte[] point = new byte[33];
            point[0] = 0x02;
            System.arraycopy(signature, 0, point, 1, 32);
            sigObj = Signature.fromString(bytesToHex(point));
        } else {
            // Include details to aid debugging (length and hex preview)
            String hexPreview = signature == null ? "null" : bytesToHex(signature);
            if (hexPreview != null && hexPreview.length() > 24) {
                hexPreview = hexPreview.substring(0, 24) + "...";
            }
            int len = (signature == null ? -1 : signature.length);
            ErrorResponse error = new ErrorResponse(
                    "invalid_blind_signature",
                    String.format("invalid signature: len=%d hex=%s", len, hexPreview)
            );
            throw new CashuErrorException(error.toJson());
        }

        if (log.isDebugEnabled()) {
            String hex = sigObj.toString();
            log.debug("Normalized blind signature hex={}", hex);
        }

        DLEQProof dleqProof = null;
        try {
            ECPoint blindedMessagePoint = CURVE.getCurve()
                    .decodePoint(blindedMessage.getBlindedMessage().getBytes())
                    .normalize();
            ECPoint blindSignaturePoint = CURVE.getCurve()
                    .decodePoint(sigObj.getCompressedBytes())
                    .normalize();
            dleqProof = dleqProofGenerator.generateProof(
                    new java.math.BigInteger(1, privateKey.getBytes()),
                    blindedMessagePoint,
                    blindSignaturePoint
            );
            if (log.isDebugEnabled()) {
                log.debug("Generated DLEQ proof for amount={} keySetId={}",
                        blindedMessage.getAmount(), blindedMessage.getKeySetId());
            }
        } catch (Exception e) {
            log.warn("Failed to generate DLEQ proof for blinded message amount={} keySetId={}: {}",
                    blindedMessage.getAmount(), blindedMessage.getKeySetId(), e.getMessage());
        }

        BlindSignature blindSignature = new BlindSignature(
                blindedMessage.getAmount(),
                blindedMessage.getKeySetId(),
                sigObj,
                dleqProof
        );
        signatureVaultService.store(blindedMessage, blindSignature);
        if (log.isDebugEnabled()) {
            log.debug("Stored blind signature for amount={} keySetId={}",
                    blindedMessage.getAmount(), blindedMessage.getKeySetId());
        }
        return blindSignature;
    }

    private static PrivateKey getPrivateKey(@NonNull BlindedMessage blindedMessage, @NonNull Mint mint, @NonNull MintProtocolService svc) throws CashuErrorException {
        return svc.getPrivateKey(blindedMessage.getKeySetId().toString(), blindedMessage.getAmount(), mint);
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;
        StringBuilder hexString = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hexString.append(String.format("%02x", b & 0xFF));
        }
        return hexString.toString();
    }

}
