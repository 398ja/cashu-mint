package cashu.mint.actor.abilities;

import cashu.common.annotation.Nut;
import cashu.common.error.CashuException;
import cashu.common.model.rest.PostMeltRequest;
import cashu.common.model.rest.PostMeltResponse;
import cashu.common.protocol.Ability;
import lombok.AllArgsConstructor;

@Nut(5)
@AllArgsConstructor
public class MeltTokens implements Ability<PostMeltResponse> {

    private final PostMeltRequest postMeltRequest;

    @Override
    public PostMeltResponse apply() throws CashuException {
        return null;
    }
}
