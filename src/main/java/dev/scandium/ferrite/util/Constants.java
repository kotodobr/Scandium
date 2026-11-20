package dev.scandium.ferrite.util;

import dev.scandium.ferrite.IPlatformHooks;

public class Constants {
    public static final String MODID = "scandium-ferrite";
    public static final IPlatformHooks PLATFORM_HOOKS;

    static {
        try {
            Class<?> hooks = Class.forName("dev.scandium.ferrite.PlatformHooks");
            PLATFORM_HOOKS = (IPlatformHooks) hooks.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
