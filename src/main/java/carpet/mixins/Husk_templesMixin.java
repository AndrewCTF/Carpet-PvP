package carpet.mixins;

import carpet.CarpetSettings;
import carpet.utils.SpawnOverrides;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Monster.class)
public class Husk_templesMixin
{
    @Redirect(
            method = "checkSurfaceMonstersSpawnRules(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)Z",
            at = @At(value = "INVOKE", target="Lnet/minecraft/world/level/ServerLevelAccessor;canSeeSky(Lnet/minecraft/core/BlockPos;)Z")
    )
    private static boolean isSkylightOrTempleVisible(ServerLevelAccessor serverWorldAccess, BlockPos pos, EntityType<? extends Mob> entityType)
    {
        if (serverWorldAccess.canSeeSky(pos)) return true;

        if (!CarpetSettings.huskSpawningInTemples) return false;
        if (entityType != EntityType.HUSK) return false;
        if (!(serverWorldAccess instanceof ServerLevel serverLevel)) return false;

        return SpawnOverrides.isStructureAtPosition(serverLevel, BuiltinStructures.DESERT_PYRAMID, pos);
    }
}
