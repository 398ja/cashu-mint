package cashu.mint.actor;

import cashu.common.model.KeySet;
import cashu.common.model.PrivateKey;
import cashu.common.protocol.Actor;
import cashu.util.Utils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

import java.util.HashSet;
import java.util.Set;

@AllArgsConstructor
@Builder
@Getter
public class Mint implements Actor {

    private final PrivateKey privateKey;
    private final Set<KeySet> keySets;

    public Mint() {
        this(PrivateKey.fromBytes(Utils.generatePrivateKey()));
    }

    public Mint(@NonNull PrivateKey privateKey) {
        this.privateKey = privateKey;
        this.keySets = new HashSet<>();
    }

    public boolean addKeySet(@NonNull KeySet keySet) {
        return keySets.add(keySet);
    }

    public boolean removeKeySet(@NonNull KeySet keySet) {
        return keySets.remove(keySet);
    }


}
