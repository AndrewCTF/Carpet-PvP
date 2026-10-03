package carpet.commands;

import carpet.CarpetSettings;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitInventory;
import carpet.pvp.kit.KitStore;
import carpet.utils.CommandHelper;
import carpet.utils.Messenger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

/**
 * {@code /bot kit} hands out the PvP loadouts from {@link KitStore}.
 */
public class BotCommand
{
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext)
    {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("bot")
                .then(literal("kit")
                        .then(literal("list").executes(BotCommand::list))
                        .then(literal("give")
                                .then(argument("players", EntityArgument.players())
                                        .then(argument("kit", StringArgumentType.word())
                                                .suggests((c, b) -> suggest(store(c).names(), b))
                                                .executes(BotCommand::give))))
                        .then(literal("save")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(BotCommand::save)))
                        .then(literal("delete")
                                .then(argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> suggest(store(c).customNames(), b))
                                        .executes(BotCommand::delete)))
                        .then(literal("restore")
                                .executes(BotCommand::restoreSelf)
                                .then(argument("players", EntityArgument.players())
                                        .executes(BotCommand::restore)))
                );
        dispatcher.register(command);
    }

    private static int list(CommandContext<CommandSourceStack> context)
    {
        if (cantUse(context)) return 0;

        KitStore store = store(context);
        Messenger.m(context.getSource(), "g Kits: ", "y ", String.join(", ", store.names()));
        for (Map.Entry<String, String> problem : store.problems().entrySet())
        {
            Messenger.m(context.getSource(), "r Kit ", "rb " + problem.getKey(), "r  cannot be used: ", "y ", problem.getValue());
        }
        return store.problems().isEmpty() ? 1 : 0;
    }

    private static int give(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        Collection<? extends ServerPlayer> targets = EntityArgument.getPlayers(context, "players");
        if (cantUse(context, targets)) return 0;

        String name = StringArgumentType.getString(context, "kit");
        KitStore store = store(context);
        Kit kit = store.get(name).orElse(null);
        if (kit == null)
        {
            Messenger.m(context.getSource(), "r There is no kit called ", "rb " + name);
            Messenger.m(context.getSource(), "r Kits: ", "y ", String.join(", ", store.names()));
            return 0;
        }

        int given = 0;
        for (ServerPlayer player : targets)
        {
            try
            {
                KitInventory.apply(player, kit, context.getSource().getServer().registryAccess());
                Messenger.m(context.getSource(), "g Gave kit ", "y " + name, "g  to ", player.getName());
                given++;
            }
            catch (IllegalArgumentException e)
            {
                Messenger.m(context.getSource(), "r Could not give kit ", "rb " + name, "r  to ", player.getName(), "r : ", "y " + e.getMessage());
            }
        }
        return given == targets.size() ? given : 0;
    }

    private static int save(CommandContext<CommandSourceStack> context)
    {
        if (cantUse(context)) return 0;

        String name = StringArgumentType.getString(context, "name");
        if (!Kit.isValidName(name))
        {
            Messenger.m(context.getSource(), "r A kit name may only hold letters, digits, ", "y _", "r  and ", "y -");
            return 0;
        }
        ServerPlayer sender = context.getSource().getPlayer();
        if (sender == null)
        {
            Messenger.m(context.getSource(), "r Only a player can save their inventory as a kit");
            return 0;
        }

        Kit kit = KitInventory.capture(sender, name);
        try
        {
            store(context).save(kit);
        }
        catch (IOException e)
        {
            Messenger.m(context.getSource(), "r Could not write kit ", "rb " + name, "r : ", "y " + e.getMessage());
            return 0;
        }
        Messenger.m(context.getSource(), "g Saved kit ", "y " + name, "g  with ", "y " + kit.entries().size(), "g  entries from ", sender.getName());
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> context)
    {
        if (cantUse(context)) return 0;

        String name = StringArgumentType.getString(context, "name");
        if (!store(context).delete(name))
        {
            Messenger.m(context.getSource(), "r There is no custom kit called ", "rb " + name);
            return 0;
        }
        Messenger.m(context.getSource(), "g Deleted kit ", "y " + name);
        return 1;
    }

    private static int restoreSelf(CommandContext<CommandSourceStack> context)
    {
        if (cantUse(context)) return 0;
        ServerPlayer sender = context.getSource().getPlayer();
        if (sender == null)
        {
            Messenger.m(context.getSource(), "r Name the players to restore, for example ", "y /bot kit restore @a");
            return 0;
        }
        return restore(context, List.of(sender));
    }

    private static int restore(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return restore(context, EntityArgument.getPlayers(context, "players"));
    }

    private static int restore(CommandContext<CommandSourceStack> context, Collection<? extends ServerPlayer> targets)
    {
        if (cantUse(context, targets)) return 0;

        int restored = 0;
        for (ServerPlayer player : targets)
        {
            if (KitInventory.restore(player))
            {
                Messenger.m(context.getSource(), "g Restored the inventory of ", player.getName());
                restored++;
            }
            else
            {
                Messenger.m(context.getSource(), "y ", player.getName(), "y  has no stored inventory to restore");
            }
        }
        return restored;
    }

    private static boolean cantUse(CommandContext<CommandSourceStack> context)
    {
        return cantUse(context, List.of());
    }

    /** Checks the rule, and that an un-opped player is not reaching into someone else's inventory. */
    private static boolean cantUse(CommandContext<CommandSourceStack> context, Collection<? extends ServerPlayer> targets)
    {
        CommandSourceStack source = context.getSource();
        if (!CommandHelper.canUseCommand(source, CarpetSettings.commandBot))
        {
            Messenger.m(source, "r You don't have permission to use /bot commands");
            return true;
        }
        Player sender = source.getPlayer();
        if (sender == null || CommandHelper.hasPermissionLevel(source, 2)) return false;
        for (ServerPlayer player : targets)
        {
            if (player != sender && !(player instanceof EntityPlayerMPFake))
            {
                Messenger.m(source, "r Non OP players can't control other real players");
                return true;
            }
        }
        return false;
    }

    private static KitStore store(CommandContext<CommandSourceStack> context)
    {
        return KitStore.of(context.getSource().getServer());
    }
}