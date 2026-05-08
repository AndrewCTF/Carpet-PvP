package carpet.pvp;

import net.fabricmc.api.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class PvpClientInitializer implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("CarpetPvp");

    @Override
    public void onInitializeClient() {
        // Stub initializer to satisfy Fabric entrypoint during migration.
        LOGGER.info("Carpet PVP client initializer loaded (stub). Client PvP hooks are not yet wired for 26.1.2.");
    }
}
