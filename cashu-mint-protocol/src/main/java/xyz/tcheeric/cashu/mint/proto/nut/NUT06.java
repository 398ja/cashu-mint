package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

@Nut(6)
@Component
public class NUT06 {

    @Getter
    private final MintInfo mintInfo;

    @Autowired
    public NUT06(MintInfo mintInfo) {
        this.mintInfo = mintInfo;
    }
}