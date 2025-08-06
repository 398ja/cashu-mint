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

        @JsonProperty
        private Boolean supported;

        @JsonProperty
        private Boolean disabled;

        @JsonProperty("fee_reserve_percent")
        private Double feeReservePercent;

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
    }
}