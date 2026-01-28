package xyz.tcheeric.cashu.mint.proto.util;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;


@Component
@ConfigurationProperties(prefix = "mint")
@PropertySource(name = "NUT06 Mint Informtion", value = "classpath:mint.yaml", factory = YamlPropertySourceFactory.class)
@Setter
@Getter
public class MintInfo {

    @JsonProperty
    private String name;

    @JsonProperty
    private String pubkey;

    @JsonProperty
    private String version;

    @JsonProperty
    private String description;

    @JsonProperty
    private String descriptionLong;

    @JsonProperty
    private String motd;

    @JsonProperty
    private String iconUrl;

    @JsonProperty
    private List<String> urls;

    @JsonProperty
    private long time;

    @JsonProperty
    private String tosUrl;

    @JsonProperty
    private List<Contact> contact;

    @JsonProperty
    private Map<String, Nut> nuts;

    @Setter
    @Getter
    public static class Contact {

        @JsonProperty
        private String method;

        @JsonProperty
        private String info;
    }

    @Setter
    @Getter
    public static class Nut {

        @JsonProperty
        private List<Method> methods;

        /**
         * For simple NUTs (7, 8, 9, 10, 12), this is a boolean.
         * For NUT-17, this is a list of WebSocket supported configurations.
         * Use {@link #getSupportedConfigs()} for NUT-17.
         */
        @JsonProperty
        private Object supported;

        @JsonProperty
        private Boolean disabled;

        @JsonProperty("fee_reserve_percent")
        private Double feeReservePercent;

        /**
         * Returns true if this NUT has simple boolean support.
         */
        public boolean isSupportedSimple() {
            return supported instanceof Boolean && (Boolean) supported;
        }

        /**
         * Returns the supported configurations for NUT-17 style complex support.
         * Returns null for simple boolean support.
         */
        @SuppressWarnings("unchecked")
        public List<WebSocketConfig> getSupportedConfigs() {
            if (supported instanceof List) {
                return ((List<Map<String, Object>>) supported).stream()
                        .map(WebSocketConfig::fromMap)
                        .toList();
            }
            return null;
        }

        @Setter
        @Getter
        public static class Method {

            @JsonProperty
            private String method;

            @JsonProperty
            private String unit;

            @JsonProperty
            private int minAmount;

            @JsonProperty
            private int maxAmount;
        }

        /**
         * NUT-17 WebSocket configuration (method, unit, commands).
         */
        @Setter
        @Getter
        public static class WebSocketConfig {
            private String method;
            private String unit;
            private List<String> commands;

            @SuppressWarnings("unchecked")
            public static WebSocketConfig fromMap(Map<String, Object> map) {
                WebSocketConfig config = new WebSocketConfig();
                config.method = (String) map.get("method");
                config.unit = (String) map.get("unit");
                config.commands = (List<String>) map.get("commands");
                return config;
            }
        }
    }
}