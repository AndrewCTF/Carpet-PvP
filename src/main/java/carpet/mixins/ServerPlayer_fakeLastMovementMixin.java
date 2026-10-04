package carpet.mixins;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Debug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.authlib.GameProfile;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A real player's known movement is the distance its client last reported it had moved, which is where the game
 * reads a player's speed from whenever it needs one the server did not move itself: a spear reads the closing
 * speed of two fighters out of it. A fake player has no client to report anything, so the field stays at zero for
 * as long as it is online and every one of those speeds reads as none at all.
 *
 * <p>A fake player therefore reports the distance the server itself moved it by, which the game keeps for every
 * entity in {@code Entity.computeSpeed} and which is what a client's position delta carries. Not its velocity:
 * an entity is moved along its velocity and then has the friction of the tick taken off it, so a run reads as a
 * little over half of the speed of it, and anything the game puts a threshold on - the closing speed a spear
 * thrust is behind - could then never be reached at all.</p>
 */
@Mixin(ServerPlayer.class)
@Debug(export = true)
public abstract class ServerPlayer_fakeLastMovementMixin extends Player {
    public ServerPlayer_fakeLastMovementMixin(Level level, GameProfile gameProfile) {
        super(level, gameProfile);
        throw new AssertionError();
    }

    @ModifyExpressionValue(
        method = {"getKnownMovement", "getKnownSpeed"}, // both because ServerPlayer overrides both to the same "movement" field
        at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerPlayer;lastKnownClientMovement:Lnet/minecraft/world/phys/Vec3;", opcode = Opcodes.GETFIELD),
        require = 2
    )
    private Vec3 bypassClientMovementInfo(Vec3 original) {
        return ((Player)this) instanceof EntityPlayerMPFake ? super.getKnownSpeed() : original;
    }
}
