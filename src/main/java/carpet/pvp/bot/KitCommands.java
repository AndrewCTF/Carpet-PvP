package carpet.pvp.bot;

import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitInventory;
import carpet.pvp.kit.KitStore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static carpet.pvp.bot.BotCommands.grey;
import static carpet.pvp.bot.BotCommands.mayTouch;
import static carpet.pvp.bot.BotCommands.red;
import static carpet.pvp.bot.BotCommands.say;
import static carpet.pvp.bot.BotCommands.yellow;

/**
 * {@code /bot kit}: handing out and managing the PvP loadouts from {@link KitStore}. See
 * {@link BotCommands} for how this is shared with the Paper build.
 */
public final class KitCommands
{
    private KitCommands() {}

    public static int list(CommandSourceStack source)
    {
        if (!BotCommands.canUse(source)) return 0;

        KitStore store = store(source);
        say(source, grey("Kits: ").append(yellow(String.join(", ", store.names()))));
        return problems(source, store);
    }

    public static int reload(CommandSourceStack source)
    {
        if (!BotCommands.canUse(source)) return 0;

        KitStore store = store(source);
        store.reload();
        say(source, grey("Reloaded the kits in the world's kit folder, ").append(yellow(String.valueOf(store.customNames().size())))
                .append(grey(" of them are custom")));
        return problems(source, store);
    }

    public static int give(CommandSourceStack source, Collection<? extends ServerPlayer> targets, String name)
    {
        if (!mayTouch(source, targets)) return 0;

        KitStore store = store(source);
        Kit kit = store.get(name).orElse(null);
        if (kit == null)
        {
            say(source, red("There is no kit called ").append(yellow(name)));
            say(source, grey("Kits: ").append(yellow(String.join(", ", store.names()))));
            return 0;
        }

        int given = 0;
        for (ServerPlayer player : targets)
        {
            try
            {
                KitInventory.apply(player, kit, source.getServer().registryAccess());
                say(source, grey("Gave kit ").append(yellow(name)).append(grey(" to ")).append(grey(player.getName().getString())));
                given++;
            }
            catch (IllegalArgumentException e)
            {
                say(source, red("Could not give kit ").append(yellow(name)).append(red(" to ")).append(grey(player.getName().getString()))
                        .append(red(" : ")).append(yellow(e.getMessage())));
            }
        }
        return given == targets.size() ? given : 0;
    }

    public static int save(CommandSourceStack source, String name)
    {
        if (!BotCommands.canUse(source)) return 0;
        if (!Kit.isValidName(name))
        {
            say(source, red("A kit name may only hold letters, digits, ").append(yellow("_")).append(red(" and ")).append(yellow("-")));
            return 0;
        }
        ServerPlayer sender = source.getPlayer();
        if (sender == null)
        {
            say(source, red("Only a player can save their inventory as a kit"));
            return 0;
        }

        Kit kit = KitInventory.capture(sender, name);
        try
        {
            store(source).save(kit);
        }
        catch (IOException e)
        {
            say(source, red("Could not write kit ").append(yellow(name)).append(red(" : ")).append(yellow(e.getMessage())));
            return 0;
        }
        say(source, grey("Saved kit ").append(yellow(name)).append(grey(" with "))
                .append(yellow(String.valueOf(kit.entries().size()))).append(grey(" entries from "))
                .append(grey(sender.getName().getString())));
        return 1;
    }

    public static int delete(CommandSourceStack source, String name)
    {
        if (!BotCommands.canUse(source)) return 0;
        if (!store(source).delete(name))
        {
            say(source, red("There is no custom kit called ").append(yellow(name)));
            return 0;
        }
        say(source, grey("Deleted kit ").append(yellow(name)));
        return 1;
    }

    public static int restoreSelf(CommandSourceStack source)
    {
        if (!BotCommands.canUse(source)) return 0;
        ServerPlayer sender = source.getPlayer();
        if (sender == null)
        {
            say(source, red("Name the players to restore, for example ").append(yellow("/bot kit restore @a")));
            return 0;
        }
        return restore(source, List.of(sender));
    }

    public static int restore(CommandSourceStack source, Collection<? extends ServerPlayer> targets)
    {
        if (!mayTouch(source, targets)) return 0;

        int restored = 0;
        for (ServerPlayer player : targets)
        {
            if (KitInventory.restore(player))
            {
                say(source, grey("Restored the inventory of ").append(grey(player.getName().getString())));
                restored++;
            }
            else
            {
                say(source, grey(player.getName().getString()).append(grey(" has no stored inventory to restore")));
            }
        }
        return restored;
    }

    /** The kits that could not be loaded are reported the same way wherever the reload happened. */
    private static int problems(CommandSourceStack source, KitStore store)
    {
        for (Map.Entry<String, String> problem : store.problems().entrySet())
        {
            say(source, red("Kit ").append(yellow(problem.getKey())).append(red(" cannot be used: ")).append(yellow(problem.getValue())));
        }
        return store.problems().isEmpty() ? 1 : 0;
    }

    private static KitStore store(CommandSourceStack source)
    {
        MinecraftServer server = source.getServer();
        return KitStore.of(server);
    }
}
