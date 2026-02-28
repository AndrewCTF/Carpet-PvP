package carpet.mixins;

import carpet.CarpetSettings;
import carpet.patches.EntityPlayerMPFake;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.server.commands.data.EntityDataAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.UUID;

@Mixin(EntityDataAccessor.class)
public class EntityDataAccessor_fakePlayerDataMixin
{
    @Shadow
    @Final
    private Entity entity;

    @Shadow
    @Final
    private static SimpleCommandExceptionType ERROR_NO_PLAYERS;

    /**
     * @author Carpet PvP
     * @reason Allow /data command to modify fake player entity data
     */
    @Overwrite
    public void setData(CompoundTag tag) throws CommandSyntaxException
    {
        if (this.entity instanceof Player && !(CarpetSettings.fakePlayerDataModifiable && this.entity instanceof EntityPlayerMPFake))
        {
            throw ERROR_NO_PLAYERS.create();
        }
        UUID uuid = this.entity.getUUID();
        try (ProblemReporter.ScopedCollector collector = new ProblemReporter.ScopedCollector(
                this.entity.problemPath(),
                org.slf4j.LoggerFactory.getLogger(EntityDataAccessor.class)))
        {
            this.entity.load(TagValueInput.create(collector, this.entity.registryAccess(), tag));
        }
        this.entity.setUUID(uuid);
    }
}
