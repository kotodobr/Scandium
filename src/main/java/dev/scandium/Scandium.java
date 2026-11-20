package dev.scandium;

import dev.scandium.config.ScandiumConfig;
import dev.scandium.gc.GcScheduler;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Scandium implements ModInitializer {
    public static final String MOD_ID = "scandium";
    public static final Logger LOGGER = LoggerFactory.getLogger("Scandium");
    /** Set by the client entrypoint: reacts to /scandium enable|disable (rebuilds terrain). Null on servers. */
    public static volatile Runnable clientToggleHook;

    @Override
    public void onInitialize() {
        ScandiumConfig.load();
        GcScheduler.get().init();
    }
}
