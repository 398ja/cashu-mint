package xyz.tcheeric.cashu.mint.proto.util;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;


@Component
@ConfigurationProperties(prefix = "mint")
@PropertySource(name = "NUT06 Mint Informtion", value = "classpath:mint.yaml", factory = YamlPropertySourceFactory.class)
@Setter
@Slf4j
public class MintInfo {

    @JsonProperty
    @Getter
    private String name;

    @JsonProperty
    @Getter
    private String pubkey;

    @JsonProperty
    @Getter
    private String version;

    @JsonProperty
    @Getter
    private String description;

    @JsonProperty
    @Getter
    private String descriptionLong;

    @JsonProperty
    @Getter
    private String motd;

    @JsonProperty
    @Getter
    private String iconUrl;

    @JsonProperty
    @Getter
    private List<String> urls;

    @JsonProperty
    @Getter
    private long time;

    @JsonProperty
    @Getter
    private String tosUrl;

    @JsonProperty
    @Getter
    private List<Contact> contact;

    private Map<String, Nut> nuts;

    @JsonIgnore
    private volatile boolean nut17Loaded = false;

    /**
     * Sets the nuts configuration map and loads NUT-17 from raw YAML.
     * Called by Spring during property binding.
     */
    public void setNuts(Map<String, Nut> nuts) {
        // Use mutable map to allow adding NUT-17 dynamically
        if (nuts != null) {
            this.nuts = new java.util.HashMap<>(nuts);
        } else {
            this.nuts = nuts;
        }

        if (this.nuts != null && !this.nuts.containsKey("17")) {
            loadNut17Configuration();
        }
    }

    /**
     * Returns the nuts configuration map with NUT-17 included.
     *
     * <p><b>Security:</b> Returns an unmodifiable view to prevent external modification
     * of internal state (per Oracle Secure Coding Guidelines MUTABLE-2).
     *
     * @return unmodifiable view of the nuts configuration map
     */
    @JsonProperty("nuts")
    public Map<String, Nut> getNuts() {
        // Ensure NUT-17 is loaded if not present
        if (nuts != null && !nuts.containsKey("17") && !nut17Loaded) {
            loadNut17Configuration();
        }
        return nuts == null ? null : java.util.Collections.unmodifiableMap(nuts);
    }

    /**
     * Loads NUT-17 configuration from raw YAML since Spring's property binding
     * cannot correctly bind complex nested list structures to Object type fields.
     */
    @SuppressWarnings("unchecked")
    private synchronized void loadNut17Configuration() {
        if (nut17Loaded) {
            return;
        }
        nut17Loaded = true;

        try {
            ClassPathResource resource = new ClassPathResource("mint.yaml");
            if (!resource.exists()) {
                log.warn("mint.yaml not found on classpath, NUT-17 configuration not loaded");
                return;
            }
            Yaml yaml = new Yaml();
            try (InputStream inputStream = resource.getInputStream()) {
                Map<String, Object> root = yaml.load(inputStream);
                Map<String, Object> mint = (Map<String, Object>) root.get("mint");
                if (mint == null) {
                    return;
                }
                Map<String, Object> nutsMap = (Map<String, Object>) mint.get("nuts");
                if (nutsMap == null) {
                    return;
                }
                Object nut17Config = nutsMap.get(17);
                if (nut17Config == null) {
                    nut17Config = nutsMap.get("17");
                }
                if (nut17Config instanceof Map) {
                    Map<String, Object> nut17 = (Map<String, Object>) nut17Config;
                    Object supported = nut17.get("supported");
                    if (supported instanceof List) {
                        if (nuts == null) {
                            nuts = new java.util.HashMap<>();
                        }
                        Nut nut = nuts.get("17");
                        if (nut == null) {
                            nut = new Nut();
                            nuts.put("17", nut);
                        }
                        nut.setSupported(supported);
                        log.debug("NUT-17 configuration loaded: {} supported configs", ((List<?>) supported).size());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load NUT-17 configuration from mint.yaml", e);
        }
    }

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
