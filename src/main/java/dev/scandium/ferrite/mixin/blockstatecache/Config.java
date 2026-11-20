package dev.scandium.ferrite.mixin.blockstatecache;

import dev.scandium.ferrite.mixin.config.FerriteConfig;
import dev.scandium.ferrite.mixin.config.FerriteMixinConfig;

public class Config extends FerriteMixinConfig {
    public Config() {
        super(FerriteConfig.DEDUP_BLOCKSTATE_CACHE);
    }
}
