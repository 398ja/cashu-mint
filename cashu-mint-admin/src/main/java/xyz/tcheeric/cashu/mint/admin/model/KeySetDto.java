package xyz.tcheeric.cashu.mint.admin.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.cashu.common.model.KeySet;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonPropertyOrder({"id", "unit", "keysDto"})
public class KeySetDto {

    @JsonProperty
    private String id;

    @JsonProperty
    private String unit;

    @JsonProperty
    private KeysDto keys;

    public static KeySet toKeySet(KeySetDto keySetDto) {
        return KeySet.builder()
                .id(keySetDto.getId())
                .unit(keySetDto.getUnit())
                .keys(KeysDto.toKeys(keySetDto.getKeys()))
                .build();
    }
}
