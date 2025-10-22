package carpet.mixins;

import carpet.fakes.FoodDataInterface;
import carpet.script.CarpetScriptServer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FoodData.class)
public class FoodData_scarpetMixin implements FoodDataInterface
{
    @Shadow private float exhaustionLevel;

    @Override
    public float getCMExhaustionLevel()
    {
        return exhaustionLevel;
    }

    @Override
    public void setExhaustion(float aFloat)
    {
        // log when exhaustion is explicitly set via Scarpet modifier
        CarpetScriptServer.LOG.info("[scarpet-debug] setExhaustion called: old={} new={}", exhaustionLevel, aFloat);
        exhaustionLevel = aFloat;
    }

    @Inject(method = "addExhaustion", at = @At("HEAD"))
    private void onAddExhaustion(float amount, CallbackInfo ci)
    {
        try
        {
            // log minimal stack frame to identify the caller
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StackTraceElement caller = st.length > 3 ? st[3] : null;
            if (caller != null)
            {
                CarpetScriptServer.LOG.info("[scarpet-debug] addExhaustion called: amount={} caller={}#{}:{}", amount, caller.getClassName(), caller.getMethodName(), caller.getLineNumber());
            }
            else
            {
                CarpetScriptServer.LOG.info("[scarpet-debug] addExhaustion called: amount={} caller=unknown", amount);
            }
        }
        catch (Throwable t)
        {
            // never fail the game due to logging
            CarpetScriptServer.LOG.warn("[scarpet-debug] failed to log addExhaustion caller", t);
        }
    }
}
