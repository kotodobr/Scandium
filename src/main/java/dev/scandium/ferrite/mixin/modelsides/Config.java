package dev.scandium.ferrite.mixin.modelsides;

import dev.scandium.ferrite.mixin.config.FerriteConfig;
import dev.scandium.ferrite.mixin.config.FerriteMixinConfig;

public class Config extends FerriteMixinConfig {
    public Config() {
        super(FerriteConfig.MODEL_SIDES);
    }
}
