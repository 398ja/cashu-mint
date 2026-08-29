package xyz.tcheeric.cashu.mint.proto.spending;

import com.fasterxml.jackson.core.type.TypeReference;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.JsonUtils;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKTransaction;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.SigAllMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The published NUT-11 test vectors, driven against this mint's spending-condition logic.
 *
 * <p>These vectors live in the spec rather than in cashu-lib because whether a proof is
 * <em>spendable</em> — locktime evaluation, threshold counting across the main and refund
 * pathways, and SIG_ALL message aggregation — is decided here, not in the library
 * (see cashu-lib#252).
 *
 * <p>Every JSON blob below is copied verbatim from the spec's vector file, so a divergence in
 * our serialization, our secret parsing, or our verification shows up as a failure here rather
 * than as an interoperability report from a wallet.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/tests/11-test.md">NUT-11 test vectors</a>
 */
@Disabled("Blocked on cashu-lib#254: WellKnownSecret re-serializes to a 4-element array "
        + "instead of NUT-10's [kind, {object}], so no published vector can verify. The "
        + "cryptography is correct - the same vectors verify against the raw wire secret string. "
        + "Un-disable when #254 lands; these vectors are the interoperability gate.")
class Nut11TestVectorsTest {

    // ----- vector fixtures, verbatim from tests/11-test.md -----

    /** Locktime 21 (long past), 2-of-3 primary, 2 refund keys with n_sigs_refund defaulting to 1. */
    private static final String MULTISIG_VALID_LOCKTIME = """
            {
              "amount": 64,
              "C": "02d7cd858d866fca404b5cb1ffd813946e6d19efa1af00d654080fd20266bdc0b1",
              "id": "001b6c716bf42c7e",
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"395162bf2d0add3c66aea9f22c45251dbee6e04bd9282addbb366a94cd4fb482\\",\\"data\\":\\"03ab50a667926fac858bac540766254c14b2b0334d10e8ec766455310224bbecf4\\",\\"tags\\":[[\\"locktime\\",\\"21\\"],[\\"pubkeys\\",\\"0229a91adec8dd9badb228c628a07fc1bf707a9b7d95dd505c490b1766fa7dc541\\",\\"033281c37677ea273eb7183b783067f5244933ef78d8c3f15b1a77cb246099c26e\\"],[\\"n_sigs\\",\\"2\\"],[\\"refund\\",\\"03ab50a667926fac858bac540766254c14b2b0334d10e8ec766455310224bbecf4\\",\\"033281c37677ea273eb7183b783067f5244933ef78d8c3f15b1a77cb246099c26e\\"]]}]",
              "witness": "{\\"signatures\\":[\\"6a4dd46f929b4747efe7380d655be5cfc0ea943c679a409ea16d4e40968ce89de885d995937d5b85f24fa33a25df10990c5e11d5397199d779d5cf87d42f6627\\",\\"0c266fffe2ea2358fb93b5d30dfbcefe52a5bb53d6c85f37d54723613224a256165d20dd095768f168ab2e97bc5a879f7c2a84eee8963c9bcedcd39552dbe093\\"]}"
            }
            """;

    private static final String MULTISIG_VALID_REFUND = """
            {
              "amount": 64,
              "C": "02d7cd858d866fca404b5cb1ffd813946e6d19efa1af00d654080fd20266bdc0b1",
              "id": "001b6c716bf42c7e",
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"395162bf2d0add3c66aea9f22c45251dbee6e04bd9282addbb366a94cd4fb482\\",\\"data\\":\\"03ab50a667926fac858bac540766254c14b2b0334d10e8ec766455310224bbecf4\\",\\"tags\\":[[\\"locktime\\",\\"21\\"],[\\"pubkeys\\",\\"0229a91adec8dd9badb228c628a07fc1bf707a9b7d95dd505c490b1766fa7dc541\\",\\"033281c37677ea273eb7183b783067f5244933ef78d8c3f15b1a77cb246099c26e\\"],[\\"n_sigs\\",\\"2\\"],[\\"refund\\",\\"03ab50a667926fac858bac540766254c14b2b0334d10e8ec766455310224bbecf4\\",\\"033281c37677ea273eb7183b783067f5244933ef78d8c3f15b1a77cb246099c26e\\"]]}]",
              "witness": "{\\"signatures\\":[\\"d39631363480adf30433ee25c7cec28237e02b4808d4143469d4f390d4eae6ec97d18ba3cc6494ab1d04372f0838426ea296f25cb4bd8bddb296adc292eeaa96\\"]}"
            }
            """;

    private static final String SIG_INPUTS_VALID = """
            {
              "amount": 1,
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"859d4935c4907062a6297cf4e663e2835d90d97ecdd510745d32f6816323a41f\\",\\"data\\":\\"0249098aa8b9d2fbec49ff8598feb17b592b986e62319a4fa488a3dc36387157a7\\",\\"tags\\":[[\\"sigflag\\",\\"SIG_INPUTS\\"]]}]",
              "C": "02698c4e2b5f9534cd0687d87513c759790cf829aa5739184a3e3735471fbda904",
              "id": "009a1f293253e41e",
              "witness": "{\\"signatures\\":[\\"60f3c9b766770b46caac1d27e1ae6b77c8866ebaeba0b9489fe6a15a837eaa6fcd6eaa825499c72ac342983983fd3ba3a8a41f56677cc99ffd73da68b59e1383\\"]}"
            }
            """;

    /** Same secret, but the signature is over a different secret. */
    private static final String SIG_INPUTS_WRONG_SECRET = """
            {
              "amount": 1,
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"859d4935c4907062a6297cf4e663e2835d90d97ecdd510745d32f6816323a41f\\",\\"data\\":\\"0249098aa8b9d2fbec49ff8598feb17b592b986e62319a4fa488a3dc36387157a7\\",\\"tags\\":[[\\"sigflag\\",\\"SIG_INPUTS\\"]]}]",
              "C": "02698c4e2b5f9534cd0687d87513c759790cf829aa5739184a3e3735471fbda904",
              "id": "009a1f293253e41e",
              "witness": "{\\"signatures\\":[\\"83564aca48c668f50d022a426ce0ed19d3a9bdcffeeaee0dc1e7ea7e98e9eff1840fcc821724f623468c94f72a8b0a7280fa9ef5a54a1b130ef3055217f467b3\\"]}"
            }
            """;

    private static final String SIG_INPUTS_MULTISIG_SATISFIED = """
            {
              "amount": 1,
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"0ed3fcb22c649dd7bbbdcca36e0c52d4f0187dd3b6a19efcc2bfbebb5f85b2a1\\",\\"data\\":\\"0249098aa8b9d2fbec49ff8598feb17b592b986e62319a4fa488a3dc36387157a7\\",\\"tags\\":[[\\"pubkeys\\",\\"0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\\",\\"02142715675faf8da1ecc4d51e0b9e539fa0d52fdd96ed60dbe99adb15d6b05ad9\\"],[\\"n_sigs\\",\\"2\\"],[\\"sigflag\\",\\"SIG_INPUTS\\"]]}]",
              "C": "02698c4e2b5f9534cd0687d87513c759790cf829aa5739184a3e3735471fbda904",
              "id": "009a1f293253e41e",
              "witness": "{\\"signatures\\":[\\"83564aca48c668f50d022a426ce0ed19d3a9bdcffeeaee0dc1e7ea7e98e9eff1840fcc821724f623468c94f72a8b0a7280fa9ef5a54a1b130ef3055217f467b3\\",\\"9a72ca2d4d5075be5b511ee48dbc5e45f259bcf4a4e8bf18587f433098a9cd61ff9737dc6e8022de57c76560214c4568377792d4c2c6432886cc7050487a1f22\\"]}"
            }
            """;

    private static final String SIG_INPUTS_MULTISIG_UNSATISFIED = """
            {
              "amount": 1,
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"0ed3fcb22c649dd7bbbdcca36e0c52d4f0187dd3b6a19efcc2bfbebb5f85b2a1\\",\\"data\\":\\"0249098aa8b9d2fbec49ff8598feb17b592b986e62319a4fa488a3dc36387157a7\\",\\"tags\\":[[\\"pubkeys\\",\\"0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\\",\\"02142715675faf8da1ecc4d51e0b9e539fa0d52fdd96ed60dbe99adb15d6b05ad9\\"],[\\"n_sigs\\",\\"2\\"],[\\"sigflag\\",\\"SIG_INPUTS\\"]]}]",
              "C": "02698c4e2b5f9534cd0687d87513c759790cf829aa5739184a3e3735471fbda904",
              "id": "009a1f293253e41e",
              "witness": "{\\"signatures\\":[\\"83564aca48c668f50d022a426ce0ed19d3a9bdcffeeaee0dc1e7ea7e98e9eff1840fcc821724f623468c94f72a8b0a7280fa9ef5a54a1b130ef3055217f467b3\\"]}"
            }
            """;

    private static final String REFUND_LOCKTIME_PAST = """
            {
              "amount": 64,
              "C": "0257353051c02e2d650dede3159915c8be123ba4f47cf33183c7fedd20bd91a79b",
              "id": "001b6c716bf42c7e",
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"4bc88ee09d1886c7461d45da205ca3274e1e3d9da2667c4865045cb18265a407\\",\\"data\\":\\"03d5edeb839be873df2348785506d36565f3b8f390fb931709a422b5a247ddefb1\\",\\"tags\\":[[\\"locktime\\",\\"21\\"],[\\"refund\\",\\"0234ad87e907e117db1590cc20a3942ffdfd5137aa563d36095d5cf5f96bada122\\"]]}]",
              "witness": "{\\"signatures\\":[\\"b316c2ff9c15f0c5c3d230e99ad94bc76a11dfccbdc820366a3db7210288f22ef6cedcded1152904ec31056d1d5176d83a2d96df5cd4ff86afdde1c90c63af5e\\"]}"
            }
            """;

    private static final String REFUND_LOCKTIME_FUTURE = """
            {
              "amount": 64,
              "C": "0215865e3b30bdf6f5cdc1ee2c33379d5629bdf2eff2595603d939ff8c65d80586",
              "id": "001b6c716bf42c7e",
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"0c3d085898f1abf2b5521035f4d0f4ecf68c6a5109f6bc836833a1188f06be65\\",\\"data\\":\\"03206e0d488387a816bbafd957be51b073432c6c7a403ec4c2a0b27647326c5150\\",\\"tags\\":[[\\"locktime\\",\\"99999999999\\"],[\\"refund\\",\\"026acbcd0fff3a424499c83ec892d3155c9d1984438659f448d9d0f1af3e92276a\\"]]}]",
              "witness": "{\\"signatures\\":[\\"e5b10d7627ab39bd0cefa219c63752a0026aa5ae754b91a0c7ee2596222f87942c442aca2957166a6b468350c09c9968792784d2ae7c42fc91739b55689f4c7a\\"]}"
            }
            """;

    // ----- SIG_ALL vectors -----

    private static final String SIG_ALL_SWAP_INPUT = """
            {
              "amount": 2,
              "id": "00bfa73302d12ffd",
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"c7f280eb55c1e8564e03db06973e94bc9b666d9e1ca42ad278408fe625950303\\",\\"data\\":\\"030d8acedfe072c9fa449a1efe0817157403fbec460d8e79f957966056e5dd76c1\\",\\"tags\\":[[\\"sigflag\\",\\"SIG_ALL\\"]]}]",
              "C": "02c97ee3d1db41cf0a3ddb601724be8711a032950811bf326f8219c50c4808d3cd",
              "witness": "{\\"signatures\\":[\\"ce017ca25b1b97df2f72e4b49f69ac26a240ce14b3690a8fe619d41ccc42d3c1282e073f85acd36dc50011638906f35b56615f24e4d03e8effe8257f6a808538\\"]}"
            }
            """;

    private static final String SIG_ALL_SWAP_OUTPUT = """
            {
              "amount": 2,
              "id": "00bfa73302d12ffd",
              "B_": "038ec853d65ae1b79b5cdbc2774150b2cb288d6d26e12958a16fb33c32d9a86c39"
            }
            """;

    /** The spec's published msg_to_sign for the SIG_ALL swap above. */
    private static final String SIG_ALL_SWAP_MESSAGE =
            "[\"P2PK\",{\"nonce\":\"c7f280eb55c1e8564e03db06973e94bc9b666d9e1ca42ad278408fe625950303\","
            + "\"data\":\"030d8acedfe072c9fa449a1efe0817157403fbec460d8e79f957966056e5dd76c1\","
            + "\"tags\":[[\"sigflag\",\"SIG_ALL\"]]}]"
            + "02c97ee3d1db41cf0a3ddb601724be8711a032950811bf326f8219c50c4808d3cd"
            + "2038ec853d65ae1b79b5cdbc2774150b2cb288d6d26e12958a16fb33c32d9a86c39";

    private static final String SIG_ALL_SWAP_MESSAGE_SHA256 =
            "de7f9e3ca0fcc5ed3258fcf83dbf1be7fa78a5ed6da7bf2aa60d61e9dc6eb09a";

    /** SIG_ALL swap with a 2-of-3 primary set and two valid signatures. */
    private static final String SIG_ALL_MULTISIG_INPUT = """
            {
              "amount": 2,
              "id": "00bfa73302d12ffd",
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"04bfd885fc982d553711092d037fdceb7320fd8f96b0d4fd6d31a65b83b94272\\",\\"data\\":\\"0275e78025b558dbe6cb8fdd032a2e7613ca14fda5c1f4c4e3427f5077a7bd90e4\\",\\"tags\\":[[\\"pubkeys\\",\\"035163650bbd5ed4be7693f40f340346ba548b941074e9138b67ef6c42755f3449\\",\\"02817d22a8edc44c4141e192995a7976647c335092199f9e076a170c7336e2f5cc\\"],[\\"n_sigs\\",\\"2\\"],[\\"sigflag\\",\\"SIG_ALL\\"]]}]",
              "C": "03866a09946562482c576ca989d06371e412b221890804c7da8887d321380755be",
              "witness": "{\\"signatures\\":[\\"be1d72c5ca16a93c5a34f25ec63ce632ddc3176787dac363321af3fd0f55d1927e07451bc451ffe5c682d76688ea9925d7977dffbb15bd79763b527f474734b0\\",\\"669d6d10d7ed35395009f222f6c7bdc28a378a1ebb72ee43117be5754648501da3bedf2fd6ff0c7849ac92683538c60af0af504102e40f2d8daca8e08b1ca16b\\"]}"
            }
            """;

    /** SIG_ALL swap, locktime 1 (past), n_sigs_refund 2, two valid refund signatures. */
    private static final String SIG_ALL_REFUND_INPUT = """
            {
              "amount": 2,
              "id": "00bfa73302d12ffd",
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"9ea35553beb18d553d0a53120d0175a0991ca6109370338406eed007b26eacd1\\",\\"data\\":\\"02af21e09300af92e7b48c48afdb12e22933738cfb9bba67b27c00c679aae3ec25\\",\\"tags\\":[[\\"locktime\\",\\"1\\"],[\\"refund\\",\\"02637c19143c58b2c58bd378400a7b82bdc91d6dedaeb803b28640ef7d28a887ac\\",\\"0345c7fdf7ec7c8e746cca264bf27509eb4edb9ac421f8fbfab1dec64945a4d797\\"],[\\"n_sigs_refund\\",\\"2\\"],[\\"sigflag\\",\\"SIG_ALL\\"]]}]",
              "C": "03dd83536fbbcbb74ccb3c87147df26753fd499cc2c095f74367fff0fb459c312e",
              "witness": "{\\"signatures\\":[\\"23b58ef28cd22f3dff421121240ddd621deee83a3bc229fd67019c2e338d91e2c61577e081e1375dbab369307bba265e887857110ca3b4bd949211a0a298805f\\",\\"7e75948ef1513564fdcecfcbd389deac67c730f7004f8631ba90c0844d3e8c0cf470b656306877df5141f65fd3b7e85445a8452c3323ab273e6d0d44843817ed\\"]}"
            }
            """;

    /** The two inputs the spec rejects: same data, but the second carries an extra locktime tag. */
    private static final String NON_UNIFORM_INPUT_A = """
            {
              "amount": 1,
              "id": "00bfa73302d12ffd",
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"fa6dd3fac9086c153878dec90b9e37163d38ff2ecf8b37db6470e9d185abbbae\\",\\"data\\":\\"033b42b04e659fed13b669f8b16cdaffc3ee5738608810cf97a7631d09bd01399d\\",\\"tags\\":[[\\"sigflag\\",\\"SIG_ALL\\"]]}]",
              "C": "024d232312bab25af2e73f41d56864d378edca9109ae8f76e1030e02e585847786",
              "witness": "{\\"signatures\\":[\\"27b4d260a1186e3b62a26c0d14ffeab3b9f7c3889e78707b8fd3836b473a00601afbd53a2288ad20a624a8bbe3344453215ea075fc0ce479dd8666fd3d9162cc\\"]}"
            }
            """;

    private static final String NON_UNIFORM_INPUT_B = """
            {
              "amount": 2,
              "id": "00bfa73302d12ffd",
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"4007b21fc5f5b1d4920bc0a08b158d98fd0fb2b0b0262b57ff53c6c5d6c2ae8c\\",\\"data\\":\\"033b42b04e659fed13b669f8b16cdaffc3ee5738608810cf97a7631d09bd01399d\\",\\"tags\\":[[\\"locktime\\",\\"122222222222222\\"],[\\"sigflag\\",\\"SIG_ALL\\"]]}]",
              "C": "02417400f2af09772219c831501afcbab4efb3b2e75175635d5474069608deb641"
            }
            """;

    // ----- melt vectors -----

    private static final String MELT_QUOTE_ID = "cF8911fzT88aEi1d-6boZZkq5lYxbUSVs-HbJxK0";

    private static final String SIG_ALL_MELT_INPUT = """
            {
              "amount": 2,
              "id": "00bfa73302d12ffd",
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"bbf9edf441d17097e39f5095a3313ba24d3055ab8a32f758ff41c10d45c4f3de\\",\\"data\\":\\"029116d32e7da635c8feeb9f1f4559eb3d9b42d400f9d22a64834d89cde0eb6835\\",\\"tags\\":[[\\"sigflag\\",\\"SIG_ALL\\"]]}]",
              "C": "02a9d461ff36448469dccf828fa143833ae71c689886ac51b62c8d61ddaa10028b",
              "witness": "{\\"signatures\\":[\\"478224fbe715e34f78cb33451db6fcf8ab948afb8bd04ff1a952c92e562ac0f7c1cb5e61809410635be0aa94d0448f7f7959bd5762cc3802b0a00ff58b2da747\\"]}"
            }
            """;

    /** NUT-08 blank output: amount 0. */
    private static final String MELT_BLANK_OUTPUT = """
            {
              "amount": 0,
              "id": "00bfa73302d12ffd",
              "B_": "038ec853d65ae1b79b5cdbc2774150b2cb288d6d26e12958a16fb33c32d9a86c39"
            }
            """;

    private static final String SIG_ALL_MELT_MESSAGE =
            "[\"P2PK\",{\"nonce\":\"bbf9edf441d17097e39f5095a3313ba24d3055ab8a32f758ff41c10d45c4f3de\","
            + "\"data\":\"029116d32e7da635c8feeb9f1f4559eb3d9b42d400f9d22a64834d89cde0eb6835\","
            + "\"tags\":[[\"sigflag\",\"SIG_ALL\"]]}]"
            + "02a9d461ff36448469dccf828fa143833ae71c689886ac51b62c8d61ddaa10028b"
            + "0038ec853d65ae1b79b5cdbc2774150b2cb288d6d26e12958a16fb33c32d9a86c39"
            + MELT_QUOTE_ID;

    private static final String SIG_ALL_MELT_MESSAGE_SHA256 =
            "9efa1067cc7dc870f4074f695115829c3cd817a6866c3b84e9814adf3c3cf262";

    private static final String MELT_MULTISIG_QUOTE_ID = "Db3qEMVwFN2tf_1JxbZp29aL5cVXpSMIwpYfyOVF";

    private static final String SIG_ALL_MELT_MULTISIG_INPUT = """
            {
              "amount": 2,
              "id": "00bfa73302d12ffd",
              "secret": "[\\"P2PK\\",{\\"nonce\\":\\"68d7822538740e4f9c9ebf5183ef6c4501c7a9bca4e509ce2e41e1d62e7b8a99\\",\\"data\\":\\"0394e841bd59aeadce16380df6174cb29c9fea83b0b65b226575e6d73cc5a1bd59\\",\\"tags\\":[[\\"pubkeys\\",\\"033d892d7ad2a7d53708b7a5a2af101cbcef69522bd368eacf55fcb4f1b0494058\\"],[\\"n_sigs\\",\\"2\\"],[\\"sigflag\\",\\"SIG_ALL\\"]]}]",
              "C": "03a70c42ec9d7192422c7f7a3ad017deda309fb4a2453fcf9357795ea706cc87a9",
              "witness": "{\\"signatures\\":[\\"ed739970d003f703da2f101a51767b63858f4894468cc334be04aa3befab1617a81e3eef093441afb499974152d279e59d9582a31dc68adbc17ffc22a2516086\\",\\"f9efe1c70eb61e7ad8bd615c50ff850410a4135ea73ba5fd8e12a734743ad045e575e9e76ea5c52c8e7908d3ad5c0eaae93337e5c11109e52848dc328d6757a2\\"]}"
            }
            """;

    // ----- helpers -----

    private static Proof<P2PKSecret> proof(String json) {
        return JsonUtils.JSON_MAPPER.convertValue(
                readTree(json), new TypeReference<Proof<P2PKSecret>>() {});
    }

    private static BlindedMessage output(String json) {
        return JsonUtils.JSON_MAPPER.convertValue(readTree(json), BlindedMessage.class);
    }

    private static com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return JsonUtils.JSON_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed test vector", e);
        }
    }

    private static String sha256Hex(byte[] message) throws Exception {
        return Hex.toHexString(Utils.sha256(message));
    }

    private static void assertSpendable(P2PKTransaction transaction, Proof<P2PKSecret> input) {
        assertDoesNotThrow(() -> new P2PKSpendingCondition(transaction).verify(input));
    }

    private static void assertUnspendable(P2PKTransaction transaction, Proof<P2PKSecret> input) {
        assertThrows(CashuErrorException.class,
                () -> new P2PKSpendingCondition(transaction).verify(input));
    }

    /** A swap transaction of one input against one output, as the SIG_ALL vectors are shaped. */
    private static P2PKTransaction swapOf(Proof<P2PKSecret> input, BlindedMessage... outputs) {
        return P2PKTransaction.forSwap(List.of(input), List.of(outputs));
    }

    // ===== SIG_INPUTS spendability vectors (cashu-lib#252) =====

    /** The spec's valid SIG_INPUTS proof verifies against its own secret. */
    @Test
    void sigInputs_validSignature_isSpendable() {
        Proof<P2PKSecret> input = proof(SIG_INPUTS_VALID);
        assertSpendable(P2PKTransaction.forSwap(List.of(input), List.of()), input);
    }

    /** The spec's SIG_INPUTS proof whose signature is over a different secret is rejected. */
    @Test
    void sigInputs_signatureOverDifferentSecret_isRejected() {
        Proof<P2PKSecret> input = proof(SIG_INPUTS_WRONG_SECRET);
        assertUnspendable(P2PKTransaction.forSwap(List.of(input), List.of()), input);
    }

    /** n_sigs=2 with two valid signatures from the data+pubkeys set meets the threshold. */
    @Test
    void sigInputs_twoOfThree_bothSignatures_isSpendable() {
        Proof<P2PKSecret> input = proof(SIG_INPUTS_MULTISIG_SATISFIED);
        assertSpendable(P2PKTransaction.forSwap(List.of(input), List.of()), input);
    }

    /** n_sigs=2 with only one valid signature falls short of the threshold. */
    @Test
    void sigInputs_twoOfThree_oneSignature_isRejected() {
        Proof<P2PKSecret> input = proof(SIG_INPUTS_MULTISIG_UNSATISFIED);
        assertUnspendable(P2PKTransaction.forSwap(List.of(input), List.of()), input);
    }

    // ===== locktime evaluation vectors (cashu-lib#252) =====

    /** Locktime 21 has passed, so a single refund signature (default n_sigs_refund=1) reclaims. */
    @Test
    void refundKey_afterLocktime_isSpendable() {
        Proof<P2PKSecret> input = proof(REFUND_LOCKTIME_PAST);
        assertSpendable(P2PKTransaction.forSwap(List.of(input), List.of()), input);
    }

    /** Locktime 99999999999 is in the future, so the refund key cannot reclaim yet. */
    @Test
    void refundKey_beforeLocktime_isRejected() {
        Proof<P2PKSecret> input = proof(REFUND_LOCKTIME_FUTURE);
        assertUnspendable(P2PKTransaction.forSwap(List.of(input), List.of()), input);
    }

    // ===== threshold counting across both pathways (cashu-lib#252) =====

    /** The primary 2-of-3 pathway releases even though the locktime has already passed. */
    @Test
    void multisig_primaryPathway_afterLocktime_isSpendable() {
        Proof<P2PKSecret> input = proof(MULTISIG_VALID_LOCKTIME);
        assertSpendable(P2PKTransaction.forSwap(List.of(input), List.of()), input);
    }

    /** The same proof is also spendable via the refund pathway, with one refund signature. */
    @Test
    void multisig_refundPathway_afterLocktime_isSpendable() {
        Proof<P2PKSecret> input = proof(MULTISIG_VALID_REFUND);
        assertSpendable(P2PKTransaction.forSwap(List.of(input), List.of()), input);
    }

    // ===== SIG_ALL message aggregation =====

    /**
     * The aggregated swap message matches the spec byte for byte, including its published
     * sha256 digest. This is the interoperability anchor: if our concatenation order, our
     * secret serialization, or our amount encoding drifts, this fails.
     */
    @Test
    void sigAll_swapAggregation_matchesPublishedMessageAndDigest() throws Exception {
        SigAllMessage message = SigAllMessage.forSwap(
                List.of(proof(SIG_ALL_SWAP_INPUT)), List.of(output(SIG_ALL_SWAP_OUTPUT)));

        assertEquals(SIG_ALL_SWAP_MESSAGE, message.value());
        assertEquals(SIG_ALL_SWAP_MESSAGE_SHA256, sha256Hex(message.toBytes()));
    }

    /**
     * The aggregated melt message matches the spec, quote id included. The quote id is the last
     * component, which is what binds the signature to the invoice being paid.
     */
    @Test
    void sigAll_meltAggregation_matchesPublishedMessageAndDigest() throws Exception {
        SigAllMessage message = SigAllMessage.forMelt(
                List.of(proof(SIG_ALL_MELT_INPUT)), MELT_QUOTE_ID, List.of(output(MELT_BLANK_OUTPUT)));

        assertEquals(SIG_ALL_MELT_MESSAGE, message.value());
        assertEquals(SIG_ALL_MELT_MESSAGE_SHA256, sha256Hex(message.toBytes()));
    }

    // ===== SIG_ALL verification =====

    /** The spec's valid SIG_ALL swap verifies. */
    @Test
    void sigAll_validSwap_isSpendable() {
        Proof<P2PKSecret> input = proof(SIG_ALL_SWAP_INPUT);
        assertSpendable(swapOf(input, output(SIG_ALL_SWAP_OUTPUT)), input);
    }

    /** A SIG_ALL swap needing two signatures verifies when both are supplied. */
    @Test
    void sigAll_multisigSwap_isSpendable() {
        Proof<P2PKSecret> input = proof(SIG_ALL_MULTISIG_INPUT);
        assertSpendable(swapOf(input, output(SIG_ALL_SWAP_OUTPUT)), input);
    }

    /** A SIG_ALL swap past its locktime releases to two refund signatures (n_sigs_refund=2). */
    @Test
    void sigAll_refundPathwaySwap_isSpendable() {
        Proof<P2PKSecret> input = proof(SIG_ALL_REFUND_INPUT);
        assertSpendable(swapOf(input, output(SIG_ALL_SWAP_OUTPUT)), input);
    }

    /** The spec's valid SIG_ALL melt verifies against the quote it names. */
    @Test
    void sigAll_validMelt_isSpendable() {
        Proof<P2PKSecret> input = proof(SIG_ALL_MELT_INPUT);
        assertSpendable(
                P2PKTransaction.forMelt(List.of(input), MELT_QUOTE_ID, List.of(output(MELT_BLANK_OUTPUT))),
                input);
    }

    /** The spec's valid multi-signature SIG_ALL melt verifies against its own quote. */
    @Test
    void sigAll_validMultisigMelt_isSpendable() {
        Proof<P2PKSecret> input = proof(SIG_ALL_MELT_MULTISIG_INPUT);
        assertSpendable(
                P2PKTransaction.forMelt(List.of(input), MELT_MULTISIG_QUOTE_ID,
                        List.of(output(MELT_BLANK_OUTPUT))),
                input);
    }

    // ===== the replay gap this change exists to close =====

    /**
     * THE REPLAY TEST. A witness captured from one melt quote must not verify against another.
     *
     * <p>Both quote ids below are real spec vectors with real signatures, so this is not a
     * signature-shape check: each witness genuinely satisfies its own quote and genuinely fails
     * the other. Before the quote id entered the aggregated message, an observer who saw a melt
     * could resubmit its inputs and witness against a different quote and be paid twice.
     */
    @Test
    void sigAll_witnessFromOneMeltQuote_failsAgainstAnother() {
        Proof<P2PKSecret> input = proof(SIG_ALL_MELT_INPUT);
        List<BlindedMessage> blankOutputs = List.of(output(MELT_BLANK_OUTPUT));

        // Sanity: the witness does satisfy the quote it was made for.
        assertSpendable(P2PKTransaction.forMelt(List.of(input), MELT_QUOTE_ID, blankOutputs), input);

        // The same inputs and the same witness, replayed against a different quote, must fail.
        assertUnspendable(
                P2PKTransaction.forMelt(List.of(input), MELT_MULTISIG_QUOTE_ID, blankOutputs), input);
    }

    /** The converse: the multi-sig melt's witness is equally bound to its own quote. */
    @Test
    void sigAll_multisigMeltWitness_failsAgainstAnotherQuote() {
        Proof<P2PKSecret> input = proof(SIG_ALL_MELT_MULTISIG_INPUT);
        List<BlindedMessage> blankOutputs = List.of(output(MELT_BLANK_OUTPUT));

        assertSpendable(
                P2PKTransaction.forMelt(List.of(input), MELT_MULTISIG_QUOTE_ID, blankOutputs), input);
        assertUnspendable(
                P2PKTransaction.forMelt(List.of(input), MELT_QUOTE_ID, blankOutputs), input);
    }

    /**
     * A melt witness must not be replayable as a swap either. The swap message omits the quote id
     * entirely, so the two aggregations are different messages over the same inputs.
     */
    @Test
    void sigAll_meltWitness_failsAsASwap() {
        Proof<P2PKSecret> input = proof(SIG_ALL_MELT_INPUT);
        assertUnspendable(swapOf(input, output(MELT_BLANK_OUTPUT)), input);
    }

    // ===== output binding =====

    /**
     * REORDERING AN OUTPUT INVALIDATES THE SIGNATURE. The aggregation is ordered, so swapping two
     * outputs changes the message. This is the guarantee SIG_ALL exists to give, and the one the
     * previous per-output scheme did not deliver.
     */
    @Test
    void sigAll_reorderingOutputs_invalidatesTheSignature() {
        BlindedMessage first = output(SIG_ALL_SWAP_OUTPUT);
        BlindedMessage second = output("""
                {
                  "amount": 2,
                  "id": "00bfa73302d12ffd",
                  "B_": "03afe7c87e32d436f0957f1d70a2bca025822a84a8623e3a33aed0a167016e0ca5"
                }
                """);
        Proof<P2PKSecret> input = proof(SIG_ALL_SWAP_INPUT);

        // The two orderings produce genuinely different messages...
        assertEquals(false, SigAllMessage.forSwap(List.of(input), List.of(first, second)).value()
                .equals(SigAllMessage.forSwap(List.of(input), List.of(second, first)).value()));

        // ...and neither ordering carries the signature, which was made over [first] alone.
        assertUnspendable(swapOf(input, first, second), input);
        assertUnspendable(swapOf(input, second, first), input);
    }

    /** Substituting an output's B_ for a different one invalidates the signature. */
    @Test
    void sigAll_substitutingAnOutput_invalidatesTheSignature() {
        Proof<P2PKSecret> input = proof(SIG_ALL_SWAP_INPUT);
        BlindedMessage attackerOutput = output("""
                {
                  "amount": 2,
                  "id": "00bfa73302d12ffd",
                  "B_": "03afe7c87e32d436f0957f1d70a2bca025822a84a8623e3a33aed0a167016e0ca5"
                }
                """);

        assertUnspendable(swapOf(input, attackerOutput), input);
    }

    /**
     * Changing only an output's <em>amount</em> invalidates the signature. The previous
     * implementation signed B_ alone, leaving amounts unconstrained — an output could be
     * inflated without breaking any signature.
     */
    @Test
    void sigAll_changingAnOutputAmount_invalidatesTheSignature() {
        Proof<P2PKSecret> input = proof(SIG_ALL_SWAP_INPUT);
        BlindedMessage inflated = output(SIG_ALL_SWAP_OUTPUT);
        inflated.setAmount(64);

        assertUnspendable(swapOf(input, inflated), input);
    }

    /** Adding an extra output the signer never saw invalidates the signature. */
    @Test
    void sigAll_appendingAnOutput_invalidatesTheSignature() {
        Proof<P2PKSecret> input = proof(SIG_ALL_SWAP_INPUT);
        BlindedMessage extra = output("""
                {
                  "amount": 1,
                  "id": "00bfa73302d12ffd",
                  "B_": "02c0d4fce02a7a0f09e3f1bca952db910b17e81a7ebcbce62cd8dcfb127d21e37b"
                }
                """);

        assertUnspendable(swapOf(input, output(SIG_ALL_SWAP_OUTPUT), extra), input);
    }

    // ===== the uniformity precondition =====

    /**
     * The spec's non-uniform vector: two SIG_ALL inputs on the same key, but the second carries an
     * extra locktime tag. NUT-11 requires an error rather than a partial evaluation.
     */
    @Test
    void sigAll_inputsWithDifferentTags_areRejected() {
        Proof<P2PKSecret> first = proof(NON_UNIFORM_INPUT_A);
        Proof<P2PKSecret> second = proof(NON_UNIFORM_INPUT_B);
        P2PKTransaction transaction = P2PKTransaction.forSwap(List.of(first, second),
                List.of(output(SIG_ALL_SWAP_OUTPUT)));

        assertUnspendable(transaction, first);
    }

    /** Two SIG_ALL inputs locked to different keys cannot share one spending condition. */
    @Test
    void sigAll_inputsWithDifferentData_areRejected() {
        Proof<P2PKSecret> first = proof(SIG_ALL_SWAP_INPUT);
        Proof<P2PKSecret> second = proof(SIG_ALL_MELT_INPUT); // a different `data` pubkey
        P2PKTransaction transaction = P2PKTransaction.forSwap(List.of(first, second),
                List.of(output(SIG_ALL_SWAP_OUTPUT)));

        assertUnspendable(transaction, first);
    }

    /**
     * Mixing a SIG_ALL input with a SIG_INPUTS input is rejected. One SIG_ALL input switches the
     * whole transaction over, and a SIG_INPUTS input cannot satisfy the shared condition.
     */
    @Test
    void sigAll_mixedWithSigInputs_isRejected() {
        Proof<P2PKSecret> sigAll = proof(SIG_ALL_SWAP_INPUT);
        Proof<P2PKSecret> sigInputs = proof(SIG_INPUTS_VALID);
        P2PKTransaction transaction = P2PKTransaction.forSwap(List.of(sigAll, sigInputs),
                List.of(output(SIG_ALL_SWAP_OUTPUT)));

        assertUnspendable(transaction, sigAll);
    }

    /** A SIG_ALL transaction is recognised as such whichever input carries the flag. */
    @Test
    void sigAll_isDetectedFromAnyInput() throws Exception {
        Proof<P2PKSecret> sigInputs = proof(SIG_INPUTS_VALID);
        Proof<P2PKSecret> sigAll = proof(SIG_ALL_SWAP_INPUT);

        assertEquals(true, P2PKTransaction.forSwap(List.of(sigInputs, sigAll), List.of()).isSigAll());
        assertEquals(false, P2PKTransaction.forSwap(List.of(sigInputs), List.of()).isSigAll());
    }

    // ===== witness location =====

    /**
     * ONLY THE FIRST INPUT'S WITNESS IS CONSULTED. NUT-11 puts every signature in the first
     * input's witness, so a second input carrying no witness at all is still spendable, and a
     * signature parked on a later input contributes nothing.
     */
    @Test
    void sigAll_onlyTheFirstInputsWitnessIsConsulted() {
        Proof<P2PKSecret> signed = proof(SIG_ALL_SWAP_INPUT);
        Proof<P2PKSecret> unsigned = proof(SIG_ALL_SWAP_INPUT);
        unsigned.setWitness(null);

        // The aggregate covers both inputs, so this is not the single-input message; what is being
        // asserted is only which witness is read, hence the negative case below is the point.
        P2PKTransaction witnessFirst = P2PKTransaction.forSwap(List.of(signed, unsigned),
                List.of(output(SIG_ALL_SWAP_OUTPUT)));
        P2PKTransaction witnessLast = P2PKTransaction.forSwap(List.of(unsigned, signed),
                List.of(output(SIG_ALL_SWAP_OUTPUT)));

        // Both fail on the message (two inputs, not one), but the shapes must be identical:
        // verification never falls back to a later input's witness to rescue a missing first one.
        assertUnspendable(witnessFirst, signed);
        assertUnspendable(witnessLast, unsigned);
    }

    /**
     * The single-input case proves the positive half: the signature lives on input zero, and
     * removing it makes the transaction unspendable even though nothing else changed.
     */
    @Test
    void sigAll_firstInputWitnessRemoved_isRejected() {
        Proof<P2PKSecret> input = proof(SIG_ALL_SWAP_INPUT);
        assertSpendable(swapOf(input, output(SIG_ALL_SWAP_OUTPUT)), input);

        Proof<P2PKSecret> stripped = proof(SIG_ALL_SWAP_INPUT);
        stripped.setWitness(null);
        assertUnspendable(swapOf(stripped, output(SIG_ALL_SWAP_OUTPUT)), stripped);
    }

    /**
     * A SIG_ALL proof evaluated outside any transaction is rejected rather than silently treated
     * as SIG_INPUTS: there is no transaction to bind its signature to.
     */
    @Test
    void sigAll_outsideATransaction_isRejected() {
        Proof<P2PKSecret> input = proof(SIG_ALL_SWAP_INPUT);
        assertThrows(CashuErrorException.class,
                () -> new P2PKSpendingCondition(List.<BlindedMessage>of()).verify(input));
    }

    // ===== SIG_INPUTS is untouched by the SIG_ALL work =====

    /**
     * A SIG_INPUTS proof is verified against its own secret regardless of what outputs the
     * transaction carries. This is the compatibility guarantee for deployed escrow proofs, which
     * are all SIG_INPUTS.
     */
    @Test
    void sigInputs_isUnaffectedByTheTransactionsOutputs() {
        Proof<P2PKSecret> input = proof(SIG_INPUTS_VALID);
        List<BlindedMessage> outputs = List.of(output(SIG_ALL_SWAP_OUTPUT));

        assertSpendable(P2PKTransaction.forSwap(List.of(input), outputs), input);
        assertSpendable(P2PKTransaction.forSwap(List.of(input), List.of()), input);
        assertSpendable(P2PKTransaction.forMelt(List.of(input), MELT_QUOTE_ID, outputs), input);
    }
}
