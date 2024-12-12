package xyz.tcheeric.cashu.mint.admin.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import xyz.tcheeric.cashu.common.model.Mint;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@AllArgsConstructor
@Builder
@Getter
public class MintDto {

    @JsonProperty
    private final String id;

    @JsonProperty
    private final Set<KeySetDto> keySets;

    public MintDto() {
        this(UUID.randomUUID().toString());
    }

    public MintDto(@NonNull String id) {
        this.id = id;
        this.keySets = new HashSet<>();
    }

    public boolean addKeySet(@NonNull KeySetDto keySetDto) {
        return keySets.add(keySetDto);
    }

    public boolean removeKeySet(@NonNull KeySetDto keySetDto) {
        return keySets.remove(keySetDto);
    }

    public static Mint toMint(MintDto mintDto) {
        Mint mint = new Mint(mintDto.getId());
        mintDto.getKeySets().forEach(keySetDto -> mint.addKeySet(KeySetDto.toKeySet(keySetDto)));
        return mint;
    }

}
