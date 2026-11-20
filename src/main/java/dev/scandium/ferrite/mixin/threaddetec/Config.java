package dev.scandium.ferrite.mixin.threaddetec;

import dev.scandium.ferrite.mixin.config.FerriteConfig;
import dev.scandium.ferrite.mixin.config.FerriteMixinConfig;

public class Config extends FerriteMixinConfig {
    public Config() {
        super(FerriteConfig.THREADING_DETECTOR, LithiumSupportState.APPLY_IF_ROADRUNNER, true);
    }
}
