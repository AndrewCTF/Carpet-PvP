package carpet.paper;

import net.minecraft.server.MinecraftServer;

import carpet.pvp.BotSettings;

/**
 * What the plugin has to set up before the self-test's first scenario. Carpet turns navigation on
 * with its {@code fakePlayerNavigation} rule; the plugin reads it from {@code config.yml}, so the
 * run turns it on for itself.
 */
final class PaperSelfTest
{
    private PaperSelfTest() {}

    static void setup(MinecraftServer server)
    {
        BotSettings.fakePlayerNavigation = true;
        var level = server.overworld();
        server.sendSystemMessage(net.minecraft.network.chat.Component.literal("[probe] before="
                + level.getBlockState(new net.minecraft.core.BlockPos(258, -51, 2))));
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "fill 256 -62 0 260 -51 4 minecraft:stone");
        server.sendSystemMessage(net.minecraft.network.chat.Component.literal("[probe] after="
                + level.getBlockState(new net.minecraft.core.BlockPos(258, -51, 2))));
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "fill 256 -60 0 260 -59 4 minecraft:stone");
        server.sendSystemMessage(net.minecraft.network.chat.Component.literal("[probe] afterThin="
                + level.getBlockState(new net.minecraft.core.BlockPos(258, -59, 2))));
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "setblock 258 -51 2 minecraft:stone");
        server.sendSystemMessage(net.minecraft.network.chat.Component.literal("[probe] afterSet="
                + level.getBlockState(new net.minecraft.core.BlockPos(258, -51, 2))));
    }
}
