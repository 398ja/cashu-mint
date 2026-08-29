package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.nut17.SubscriptionKind;
import xyz.tcheeric.cashu.mint.proto.nut.CachedEndpoint;
import xyz.tcheeric.cashu.mint.proto.nut.NutSupport;
import xyz.tcheeric.cashu.mint.proto.service.MintInfoService;
import xyz.tcheeric.cashu.mint.proto.util.AmountLimitContext;
import xyz.tcheeric.cashu.mint.proto.util.AmountLimitPolicy;
import xyz.tcheeric.cashu.mint.proto.util.FeeConfig;
import xyz.tcheeric.cashu.mint.proto.util.MintCapabilityProperties;
import xyz.tcheeric.cashu.mint.proto.util.MintIdentityProperties;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;
import xyz.tcheeric.cashu.mint.proto.util.MintVersion;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the NUT-06 response from deployment configuration, the build and the
 * wired capability registry.
 *
 * <p>The {@code nuts} map is <em>derived</em> from {@link NutSupport} rather than
 * read from a file. Advertising a NUT therefore requires declaring it next to the
 * code that implements it, which is what keeps {@code /v1/info} and the mint from
 * drifting apart.
 *
 * <p>The advertised amount limits are taken from the very {@link AmountLimitPolicy}
 * this service installs for the quote paths to enforce, so the number a wallet
 * reads from {@code /v1/info} is the number the mint rejects against.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/06.md">NUT-06</a>
 */
@Service
public class DefaultMintInfoService implements MintInfoService {

    private static final long MILLIS_PER_SECOND = 1000L;

    private final MintIdentityProperties identity;
    private final AmountLimitPolicy amountLimits;
    private final MintCapabilityProperties capabilities;
    private final Clock clock;

    @Autowired
    public DefaultMintInfoService(@NonNull MintIdentityProperties identity,
                                  @NonNull MintCapabilityProperties capabilities) {
        this(identity, capabilities, Clock.systemUTC());
    }

    DefaultMintInfoService(@NonNull MintIdentityProperties identity,
                           @NonNull MintCapabilityProperties capabilities,
                           @NonNull Clock clock) {
        this.identity = identity;
        this.capabilities = capabilities;
        this.clock = clock;
        this.amountLimits = new AmountLimitPolicy(capabilities);
        AmountLimitContext.install(amountLimits);
    }

    @Override
    public MintInfo getMintInfo() {
        MintInfo info = new MintInfo();
        applyIdentity(info);
        info.setVersion(MintVersion.current());
        info.setTime(clock.millis() / MILLIS_PER_SECOND);
        info.setNuts(advertisedNuts());
        return info;
    }

    private void applyIdentity(MintInfo info) {
        info.setName(identity.getName());
        info.setPubkey(identity.getPubkey());
        info.setDescription(identity.getDescription());
        info.setDescriptionLong(identity.getDescriptionLong());
        info.setMotd(identity.getMotd());
        info.setIconUrl(identity.getIconUrl());
        info.setTosUrl(identity.getTosUrl());
        info.setUrls(identity.toUrls());
        info.setContact(identity.toContacts());
    }

    private Map<String, MintInfo.Nut> advertisedNuts() {
        Map<String, MintInfo.Nut> nuts = new LinkedHashMap<>();
        for (NutSupport nut : NutSupport.values()) {
            if (!nut.getVisibility().isAdvertised()) {
                continue;
            }
            nuts.put(nut.key(), entryFor(nut));
        }
        return nuts;
    }

    private MintInfo.Nut entryFor(NutSupport nut) {
        return switch (nut.getVisibility()) {
            case SIMPLE -> simpleEntry();
            case PAYMENT_METHODS -> paymentMethodEntry(nut);
            case WEBSOCKET -> webSocketEntry();
            case CACHED_RESPONSES -> cachedResponseEntry();
            case MANDATORY -> throw new IllegalStateException(
                    "Mandatory NUT " + nut.key() + " is not advertised in the nuts map");
        };
    }

    private MintInfo.Nut simpleEntry() {
        MintInfo.Nut entry = new MintInfo.Nut();
        entry.setSupported(Boolean.TRUE);
        return entry;
    }

    private MintInfo.Nut paymentMethodEntry(NutSupport nut) {
        boolean melt = nut == NutSupport.MELT;
        MintInfo.Nut entry = new MintInfo.Nut();
        entry.setMethods(toAdvertisedMethods(
                melt ? amountLimits.meltMethods() : amountLimits.mintMethods()));
        entry.setDisabled(melt ? capabilities.isMeltDisabled() : capabilities.isMintDisabled());
        if (melt) {
            entry.setFeeReservePercent(FeeConfig.getFeeReservePercent());
        }
        return entry;
    }

    private List<MintInfo.Nut.Method> toAdvertisedMethods(
            List<MintCapabilityProperties.PaymentMethodLimits> configured) {
        List<MintInfo.Nut.Method> methods = new ArrayList<>();
        for (MintCapabilityProperties.PaymentMethodLimits limits : configured) {
            methods.add(new MintInfo.Nut.Method(limits.getMethod(), limits.getUnit(),
                    limits.getMinAmount(), limits.getMaxAmount()));
        }
        return methods;
    }

    /**
     * NUT-17 is served over one WebSocket endpoint for every advertised mint
     * method, and the commands are exactly the subscription kinds the handler
     * accepts, so both come from the wiring rather than a list.
     */
    private MintInfo.Nut webSocketEntry() {
        List<String> commands = Arrays.stream(SubscriptionKind.values())
                .map(Enum::name)
                .toList();
        List<MintInfo.Nut.WebSocketConfig> configs = new ArrayList<>();
        for (MintCapabilityProperties.PaymentMethodLimits limits : capabilities.getMintMethods()) {
            configs.add(new MintInfo.Nut.WebSocketConfig(limits.getMethod(), limits.getUnit(), commands));
        }
        MintInfo.Nut entry = new MintInfo.Nut();
        entry.setSupported(configs);
        return entry;
    }

    /**
     * NUT-19 lists exactly the routes that have a response cache behind them,
     * because each {@link CachedEndpoint} names the store it replays from.
     */
    private MintInfo.Nut cachedResponseEntry() {
        List<MintInfo.Nut.CachedEndpoint> endpoints = Arrays.stream(CachedEndpoint.values())
                .map(endpoint -> new MintInfo.Nut.CachedEndpoint(
                        endpoint.getHttpMethod(), endpoint.getPath()))
                .toList();
        MintInfo.Nut entry = new MintInfo.Nut();
        entry.setTtl(capabilities.cachedResponseTtlSeconds());
        entry.setCachedEndpoints(endpoints);
        return entry;
    }
}
