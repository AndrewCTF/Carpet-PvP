package carpet.client;

import carpet.CarpetSettings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * Dev check: apply every client mixin once the client is up, so a mixin that no longer matches its
 * target fails the runClientCheck task instead of the first time something calls the patched method.
 */
public final class ClientMixinAudit
{
    public static void register()
    {
        if (!Boolean.getBoolean("carpet.mixinAudit")) return;
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
        {
            try
            {
                MixinEnvironment.getCurrentEnvironment().audit();
                CarpetSettings.LOG.info("client mixin audit finished");
                Runtime.getRuntime().halt(0);
            }
            catch (Throwable t)
            {
                CarpetSettings.LOG.error("client mixin audit failed", t);
                Runtime.getRuntime().halt(1);
            }
        });
    }
}