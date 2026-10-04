package carpet.mixins;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import carpet.patches.EntityPlayerMPFake;

@Mixin(PlayerList.class)
public abstract class PlayerList_fakePlayersMixin
{
    @Redirect(method = "respawn", at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/level/ServerPlayer;"))
    public ServerPlayer makePlayerForRespawn(MinecraftServer minecraftServer, ServerLevel serverLevel, GameProfile gameProfile, ClientInformation cli, ServerPlayer serverPlayer, boolean i)
    {
        if (serverPlayer instanceof EntityPlayerMPFake) {
            return EntityPlayerMPFake.respawnFake(minecraftServer, serverLevel, gameProfile, cli);
        }
        return new ServerPlayer(minecraftServer, serverLevel, gameProfile, cli);
    }
}
