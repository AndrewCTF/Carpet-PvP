package carpet.pvp;

import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class PvpInitializer implements ModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("CarpetPvp");

    @Override
    public void onInitialize() {
        // Stub initializer to satisfy Fabric entrypoint during migration.
        LOGGER.info("Carpet PVP main initializer loaded (stub). PvP hooks are not yet wired for 26.1.2.");
    }
}
