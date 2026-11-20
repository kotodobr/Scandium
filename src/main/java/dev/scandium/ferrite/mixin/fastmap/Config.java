package dev.scandium.ferrite.mixin.fastmap;

import dev.scandium.ferrite.mixin.config.FerriteConfig;
import dev.scandium.ferrite.mixin.config.FerriteMixinConfig;

public class Config extends FerriteMixinConfig {
    public Config() {
        super(FerriteConfig.NEIGHBOR_LOOKUP);
    }
}
