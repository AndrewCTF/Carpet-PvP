//? if >=26.1 {
package carpet.mixins;

import carpet.CarpetSettings;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SummonCommand.class)
public class SummonCommand_lightningMixin
{
    // [CM] SummonNaturalLightning - if statement around. This runs after every summon, not from the
    // blockPosition() call the rest of the branch makes, which vanilla only reaches for a mob.
    @Inject(method = "createEntity", at = @At("RETURN"))
    private static void addRiders(CommandSourceStack source, Holder.Reference<EntityType<?>> type, Vec3 pos,
                                  CompoundTag tag, boolean load, CallbackInfoReturnable<Entity> cir)
    {
        Entity entity = cir.getReturnValue();
        if (CarpetSettings.summonNaturalLightning && entity instanceof LightningBolt && !entity.level().isClientSide())
        {
            ServerLevel world = (ServerLevel) entity.level();
            BlockPos at = entity.blockPosition();
            DifficultyInstance localDifficulty_1 =  world.getCurrentDifficultyAt(at);
            boolean boolean_2 = world.getGameRules().get(GameRules.SPAWN_MOBS) && world.getRandom().nextDouble() < (double)localDifficulty_1.getEffectiveDifficulty() * 0.01D;
            if (boolean_2) {
                SkeletonHorse skeletonHorseEntity_1 = EntityTypes.SKELETON_HORSE.create(world, EntitySpawnReason.EVENT);
                skeletonHorseEntity_1.setTrap(true);
                skeletonHorseEntity_1.setAge(0);
                skeletonHorseEntity_1.setPos(entity.getX(), entity.getY(), entity.getZ());
                world.addFreshEntity(skeletonHorseEntity_1);
            }
        }
    }
}
//? } else {
/*package carpet.mixins;

import carpet.CarpetSettings;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SummonCommand.class)
public class SummonCommand_lightningMixin
{
    // [CM] SummonNaturalLightning - if statement around. This runs after every summon, not from the
    // blockPosition() call vanilla only makes for a mob, which a lightning bolt never is.
    @Inject(method = "createEntity", at = @At("RETURN"))
    private static void addRiders(CommandSourceStack source, Holder.Reference<EntityType<?>> type, Vec3 pos,
                                  CompoundTag tag, boolean load, CallbackInfoReturnable<Entity> cir)
    {
        Entity entity = cir.getReturnValue();
        if (CarpetSettings.summonNaturalLightning && entity instanceof LightningBolt && !entity.level().isClientSide())
        {
            ServerLevel world = (ServerLevel) entity.level();
            BlockPos at = entity.blockPosition();
            DifficultyInstance localDifficulty_1 =  world.getCurrentDifficultyAt(at);
            boolean boolean_2 = world.getGameRules().get(GameRules.SPAWN_MOBS) && world.random.nextDouble() < (double)localDifficulty_1.getEffectiveDifficulty() * 0.01D;
            if (boolean_2) {
                SkeletonHorse skeletonHorseEntity_1 = EntityType.SKELETON_HORSE.create(world, EntitySpawnReason.EVENT);
                skeletonHorseEntity_1.setTrap(true);
                skeletonHorseEntity_1.setAge(0);
                skeletonHorseEntity_1.setPos(entity.getX(), entity.getY(), entity.getZ());
                world.addFreshEntity(skeletonHorseEntity_1);
            }
        }
    }
}
*///?}