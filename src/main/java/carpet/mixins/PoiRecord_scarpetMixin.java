package carpet.mixins;

import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
//? if <26.1 {
/*import org.spongepowered.asm.mixin.gen.Invoker;
*///?}

@Mixin(PoiRecord.class)
public interface PoiRecord_scarpetMixin
{
//? if >=26.1 {
    @Accessor("valid")
    boolean isValid();
//?}
    @Accessor("freeTickets")
    int getFreeTickets();

//? if <26.1 {
/*    @Invoker
    boolean callAcquireTicket();
*///?} else {
    @Accessor("acquireTicket")
    void callAcquireTicket();
//?}
}
