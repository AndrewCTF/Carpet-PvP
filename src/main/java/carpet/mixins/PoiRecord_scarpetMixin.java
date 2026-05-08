package carpet.mixins;

import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PoiRecord.class)
public interface PoiRecord_scarpetMixin
{
    @Accessor("valid")
    boolean isValid();

    @Accessor("freeTickets")
    int getFreeTickets();

    @Accessor("acquireTicket")
    void callAcquireTicket();
}