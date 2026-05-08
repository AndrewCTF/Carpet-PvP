package carpet.fakes;

import carpet.script.CarpetScriptServer;
import net.minecraft.server.MinecraftServer;

import java.util.function.BooleanSupplier;

public interface MinecraftServerInterface
{
    void forceTick(BooleanSupplier sup);
    CarpetScriptServer getScriptServer();
    Object getCMSession();
    void addScriptServer(CarpetScriptServer scriptServer);
}