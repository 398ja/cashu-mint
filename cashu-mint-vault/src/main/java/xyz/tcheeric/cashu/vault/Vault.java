package xyz.tcheeric.cashu.vault;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.config.EntityConfiguration;

public interface Vault<T extends EntityConfiguration> {

    void store() throws CashuErrorException;

    String retrieve(String key, boolean archive) throws CashuErrorException;

    void archive(String key) throws CashuErrorException;

    void delete() throws CashuErrorException;
}
