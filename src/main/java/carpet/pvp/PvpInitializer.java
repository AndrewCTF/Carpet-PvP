package carpet.pvp;

import carpet.CarpetServer;
import carpet.logic.CarpetLogic;
import net.fabricmc.api.ModInitializer;

public final class PvpInitializer implements ModInitializer
{
    @Override
    public void onInitialize()
    {
        CarpetServer.manageExtension(CarpetLogic.INSTANCE);
    }
}
