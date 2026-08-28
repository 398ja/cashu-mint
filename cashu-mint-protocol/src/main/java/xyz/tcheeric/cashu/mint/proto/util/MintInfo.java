package xyz.tcheeric.cashu.mint.proto.util;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * The NUT-06 {@code GetInfoResponse} body served from {@code /v1/info}.
 *
 * <p>A pure response object: it is assembled by {@code DefaultMintInfoService}
 * from deployment configuration ({@link MintIdentityProperties},
 * {@link MintCapabilityProperties}), the build ({@link MintVersion}) and the
 * wired capability registry. It deliberately reads no configuration of its own,
 * so there is exactly one place where the mint decides what to say about itself.
 *
 * <p>Unset fields are omitted rather than serialised as null, so a deployment
 * that has not declared, say, a {@code motd} simply does not advertise one.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/06.md">NUT-06</a>
 */
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MintInfo {

    @JsonProperty("name")
    private String name;

    @JsonProperty("pubkey")
    private String pubkey;

    @JsonProperty("version")
    private String version;

    @JsonProperty("description")
    private String description;

    @JsonProperty("description_long")
    private String descriptionLong;

    @JsonProperty("motd")
    private String motd;

    @JsonProperty("icon_url")
    private String iconUrl;

    @JsonProperty("urls")
    private List<String> urls;

    @JsonProperty("time")
    private long time;

    @JsonProperty("tos_url")
    private String tosUrl;

    @JsonProperty("contact")
    private List<Contact> contact;

    @JsonProperty("nuts")
    private Map<String, Nut> nuts;

    /**
     * A way to reach the mint operator, per NUT-06 {@code contact}.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Contact {

        @JsonProperty("method")
        private String method;

        @JsonProperty("info")
        private String info;
    }

    /**
     * One entry in the {@code nuts} map. NUT-06 gives each optional NUT its own
     * entry shape, so the unused fields stay null and are omitted.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Nut {

        /** NUT-04 / NUT-05: the payment methods and their amount limits. */
        @JsonProperty("methods")
        private List<Method> methods;

        /**
         * A boolean for simple NUTs (7, 8, 9, 10, 11, 12); a list of
         * {@link WebSocketConfig} for NUT-17.
         */
        @JsonProperty("supported")
        private Object supported;

        @JsonProperty("disabled")
        private Boolean disabled;

        @JsonProperty("fee_reserve_percent")
        private Double feeReservePercent;

        /** NUT-19: how many seconds a cached response stays replayable. */
        @JsonProperty("ttl")
        private Long ttl;

        /** NUT-19: the routes on which responses are cached. */
        @JsonProperty("cached_endpoints")
        private List<CachedEndpoint> cachedEndpoints;

        /**
         * Answers whether this entry advertises plain boolean support.
         *
         * @return true when {@code supported} is boolean true
         */
        @JsonIgnore
        public boolean isSupportedSimple() {
            return Boolean.TRUE.equals(supported);
        }

        /**
         * Returns the NUT-17 WebSocket configurations carried by this entry.
         *
         * @return the configurations, or null when support is a plain boolean
         */
        @SuppressWarnings("unchecked")
        @JsonIgnore
        public List<WebSocketConfig> getSupportedConfigs() {
            if (supported instanceof List<?> configs) {
                return (List<WebSocketConfig>) configs;
            }
            return null;
        }

        /**
         * A payment method the mint serves, with the amounts it accepts on it.
         */
        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class Method {

            @JsonProperty("method")
            private String method;

            @JsonProperty("unit")
            private String unit;

            @JsonProperty("min_amount")
            private int minAmount;

            @JsonProperty("max_amount")
            private int maxAmount;
        }

        /**
         * A NUT-17 WebSocket configuration: the commands served for a
         * method/unit pair.
         */
        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class WebSocketConfig {

            @JsonProperty("method")
            private String method;

            @JsonProperty("unit")
            private String unit;

            @JsonProperty("commands")
            private List<String> commands;
        }

        /**
         * A NUT-19 cached route.
         */
        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class CachedEndpoint {

            @JsonProperty("method")
            private String method;

            @JsonProperty("path")
            private String path;
        }
    }
}
