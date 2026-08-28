package xyz.tcheeric.cashu.mint.proto.nut;

import org.junit.jupiter.api.Test;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.mint.proto.service.MintInfoService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintInfoService;
import xyz.tcheeric.cashu.mint.proto.util.MintCapabilityProperties;
import xyz.tcheeric.cashu.mint.proto.util.MintIdentityProperties;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Issue #390 — the drift guard for {@code /v1/info}.
 *
 * <p>{@code /v1/info} is a promise to wallets: it is how a wallet decides what it
 * may attempt. It got into a state where NUT-19 was implemented but unadvertised
 * and other entries were stale, because the advertisement lived in a
 * hand-maintained YAML file that nothing tied to the code. Asserting one
 * hand-maintained list against another would only have moved the problem.
 *
 * <p>So these tests close the loop in both directions:
 * <ol>
 *   <li>the served map is <em>derived</em> from {@link NutSupport}, so it cannot
 *       disagree with the registry;</li>
 *   <li>every {@link NutSupport} entry names a wiring witness that must resolve
 *       on the classpath, so an entry cannot outlive its implementation;</li>
 *   <li>every {@code @Nut}-annotated class on the classpath must be declared in
 *       the registry, so an implementation cannot go unadvertised.</li>
 * </ol>
 */
class NutWiringContractTest {

    private static final String NUT_PACKAGE_SCAN_PATTERN =
            "classpath*:xyz/tcheeric/cashu/mint/proto/**/*.class";

    private final MintInfoService mintInfoService =
            new DefaultMintInfoService(new MintIdentityProperties(), new MintCapabilityProperties());

    // The advertised nuts map must contain exactly the optional NUTs declared in
    // the registry — no entry the code does not claim, no claim left unadvertised.
    @Test
    void advertisedNutsMatchTheRegistry() {
        Set<String> advertised = new TreeSet<>(mintInfoService.getMintInfo().getNuts().keySet());

        Set<String> expected = java.util.Arrays.stream(NutSupport.values())
                .filter(nut -> nut.getVisibility().isAdvertised())
                .map(NutSupport::key)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(advertised)
                .as("the /v1/info nuts map is derived from NutSupport; a mismatch means "
                        + "the assembler dropped or invented an entry")
                .isEqualTo(expected);
    }

    // Every NUT the registry declares must be backed by code that is actually on
    // the classpath: deleting or renaming an implementation fails the build
    // instead of leaving a false claim on the wire.
    @Test
    void everyDeclaredNutHasItsWiringOnTheClasspath() {
        for (NutSupport nut : NutSupport.values()) {
            Class<?> witness = resolveWitnessClass(nut);
            if (nut.getWitnessMemberName() == null) {
                continue;
            }
            boolean memberPresent = java.util.Arrays.stream(witness.getDeclaredMethods())
                    .anyMatch(method -> method.getName().equals(nut.getWitnessMemberName()));
            assertThat(memberPresent)
                    .as("NUT-%s claims %s#%s as its wiring witness, but that member is gone; "
                                    + "either restore it or stop advertising the NUT",
                            nut.key(), witness.getName(), nut.getWitnessMemberName())
                    .isTrue();
        }
    }

    // Every @Nut-annotated protocol class must be declared in the registry, so an
    // implemented NUT cannot silently go unadvertised the way NUT-19 did.
    @Test
    void everyAnnotatedNutImplementationIsDeclared() throws Exception {
        Set<Integer> declared = java.util.Arrays.stream(NutSupport.values())
                .map(NutSupport::getNumber)
                .collect(Collectors.toCollection(TreeSet::new));

        for (Map.Entry<Integer, String> annotated : annotatedNutClasses().entrySet()) {
            assertThat(declared)
                    .as("%s is annotated @Nut(%d) but NUT-%d is not declared in NutSupport, "
                                    + "so /v1/info never mentions it",
                            annotated.getValue(), annotated.getKey(), annotated.getKey())
                    .contains(annotated.getKey());
        }
    }

    // NUT-19 specifically: the melt saga has cached responses since spec 002, so
    // the map must carry a ttl and at least one cached endpoint.
    @Test
    void nut19IsAdvertisedWithTtlAndCachedEndpoints() {
        MintInfo.Nut nut19 = mintInfoService.getMintInfo().getNuts()
                .get(NutSupport.CACHED_RESPONSES.key());

        assertThat(nut19).as("NUT-19 must be advertised; the mint caches melt responses").isNotNull();
        assertThat(nut19.getTtl()).as("NUT-19 ttl in seconds").isNotNull().isPositive();
        assertThat(nut19.getCachedEndpoints()).as("NUT-19 cached_endpoints").isNotEmpty();
    }

    private Class<?> resolveWitnessClass(NutSupport nut) {
        try {
            return Class.forName(nut.getWitnessClassName());
        } catch (ClassNotFoundException e) {
            return fail("NUT-%s claims %s as its wiring witness, but that class is not on the "
                            + "classpath; either restore it or stop advertising the NUT",
                    nut.key(), nut.getWitnessClassName());
        }
    }

    private Map<Integer, String> annotatedNutClasses() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(resolver);
        Map<Integer, String> annotated = new java.util.TreeMap<>();

        for (Resource resource : resolver.getResources(NUT_PACKAGE_SCAN_PATTERN)) {
            MetadataReader reader = metadataReaderFactory.getMetadataReader(resource);
            Map<String, Object> attributes = reader.getAnnotationMetadata()
                    .getAnnotationAttributes(Nut.class.getName());
            if (attributes == null) {
                continue;
            }
            annotated.put((Integer) attributes.get("value"), reader.getClassMetadata().getClassName());
        }

        assertThat(annotated)
                .as("the classpath scan must find the @Nut-annotated protocol classes; "
                        + "an empty result would make this guard vacuous")
                .isNotEmpty();
        return annotated;
    }
}
