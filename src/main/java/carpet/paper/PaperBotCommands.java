package carpet.paper;

import carpet.helpers.EntityPlayerActionPack;
import carpet.helpers.EntityPlayerActionPack.Action;
import carpet.helpers.EntityPlayerActionPack.ActionType;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.CombatTraces;
import carpet.pvp.FactionManager;
import carpet.pvp.MatchManager;
import carpet.pvp.autosetup.AutoMode;
import carpet.pvp.bot.AutoSetupCommands;
import carpet.pvp.bot.BotCommands;
import carpet.pvp.bot.CombatCommands;
import carpet.pvp.bot.KitCommands;
import carpet.pvp.bot.MenuCommands;
import carpet.pvp.bot.PracticeCommands;
import carpet.pvp.drill.Drills;
import carpet.pvp.kit.KitStore;
import carpet.pvp.nav.BotNavMode;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.PaperCommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.FinePositionResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.RotationResolver;
import io.papermc.paper.math.FinePosition;
import io.papermc.paper.math.Rotation;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * {@code /bot} on Paper. The kit and combat-bot commands are the shared bodies in {@link KitCommands}
 * and {@link CombatCommands}, so the two platforms cannot drift apart; everything below them are the
 * fake-player controls Fabric's {@code /player} gives, which a Paper server has no other way in.
 *
 * <p>Paper's Brigadier commands carry a source stack of their own, which unwraps to the vanilla one
 * every piece of shared code takes.</p>
 */
public final class PaperBotCommands
{
    static final String PERMISSION = "carpetpvp.bot";
    static final String AUTO_SETUP_PERMISSION = "carpetpvp.autosetup";

    private PaperBotCommands() {}

    public static void register(io.papermc.paper.command.brigadier.Commands commands)
    {
        // /bot is registered without Bukkit's own permission filter, so a sender who may not run it gets
        // the sentence the shared bodies print on Fabric rather than Brigadier's "unknown command". What
        // decides it is the plugin's permission, checked by {@link #mayCommandBots} below, which the
        // shared bodies already ask; /auto-setup keeps Bukkit's filter, because nothing wraps it.
        commands.register(root().build(), (String) null);
        commands.register(autoSetupTree().build(), AUTO_SETUP_PERMISSION);
        BotCommands.mayCommandBots = PaperBotCommands::mayCommandBots;
    }

    /**
     * Whether the sender may run the {@code /bot} commands: the console may, so may a bot of this
     * server, and a real player may when it holds {@code carpetpvp.bot}, which {@code paper-plugin.yml}
     * gives to operators by default.
     */
    static boolean mayCommandBots(net.minecraft.commands.CommandSourceStack source)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null) return true;
        // A bot of this server may always drive bots: Fabric's commandBot rule ships as "true", so a
        // fake player can spawn and fight there, and `/bot match` and the drills are run as one.
        if (player instanceof EntityPlayerMPFake) return true;
        return player.getBukkitEntity() instanceof org.bukkit.entity.Player bukkit
                && bukkit.hasPermission(PERMISSION);
    }



    private static LiteralArgumentBuilder<CommandSourceStack> literal(String name)
    {
        return io.papermc.paper.command.brigadier.Commands.literal(name);
    }

    private static <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(String name,
            com.mojang.brigadier.arguments.ArgumentType<T> type)
    {
        return io.papermc.paper.command.brigadier.Commands.argument(name, type);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root()
    {
        return literal("bot")
                .executes(c -> MenuCommands.open(vanilla(c)))
                // The shared /bot: the kits, then the combat bots.
                .then(kitTree())
                .then(combatSpawnTree())
                .then(optionTree())
                .then(duelTree())
                .then(namedTree("stop", (c, name) -> CombatCommands.stop(vanilla(c), name)))
                .then(namedTree("stats", (c, name) -> CombatCommands.stats(vanilla(c), name)))
                .then(drillTree())
                .then(matchTree())
                .then(spectateTree())
                .then(skinTree())
                .then(traceTree())
                .then(guiTree())
                // Everything below acts on one bot, named the way /player names it.
                .then(namedBot());
    }

    /** {@code /auto-setup}, the same command Fabric registers beside {@code /bot}. */
    private static LiteralArgumentBuilder<CommandSourceStack> autoSetupTree()
    {
        return literal("auto-setup")
                .executes(c -> AutoSetupCommands.menu(vanilla(c)))
                .then(literal("stop").executes(c -> AutoSetupCommands.stop(vanilla(c))))
                .then(argument("mode", StringArgumentType.word())
                        .suggests(suggestions(List.of(AutoMode.names())))
                        .executes(c -> AutoSetupCommands.start(vanilla(c), StringArgumentType.getString(c, "mode"), null))
                        .then(argument("difficulty", StringArgumentType.word())
                                .suggests(suggestions(List.of(BotPvpConfig.difficulties())))
                                .executes(c -> AutoSetupCommands.start(vanilla(c),
                                        StringArgumentType.getString(c, "mode"),
                                        StringArgumentType.getString(c, "difficulty")))));
    }

    /** {@code /bot drill}, the drills a player runs against a bot. */
    private static LiteralArgumentBuilder<CommandSourceStack> drillTree()
    {
        return literal("drill")
                .then(literal("list").executes(c -> PracticeCommands.drillList(vanilla(c))))
                .then(literal("stop").executes(c -> PracticeCommands.drillStop(vanilla(c))))
                .then(argument("name", StringArgumentType.word())
                        .suggests(suggestions(List.of(Drills.names())))
                        .executes(c -> PracticeCommands.drill(vanilla(c), StringArgumentType.getString(c, "name"))));
    }

    /** {@code /bot match}, a fight between bots. */
    private static LiteralArgumentBuilder<CommandSourceStack> matchTree()
    {
        return literal("match")
                .then(literal("stop").executes(c -> PracticeCommands.matchStop(vanilla(c))))
                .then(literal("join")
                        .then(argument("team", StringArgumentType.word())
                                .executes(c -> PracticeCommands.matchJoin(vanilla(c),
                                        StringArgumentType.getString(c, "team")))))
                .then(literal("ffa").then(matchMode("count", MatchManager.Mode.FFA)))
                .then(literal("teams").then(matchMode("size", MatchManager.Mode.TEAMS)));
    }

    /** {@code <mode> [difficulty]}, the tail both match forms take. */
    private static RequiredArgumentBuilder<CommandSourceStack, Integer> matchMode(String size, MatchManager.Mode mode)
    {
        return argument(size, IntegerArgumentType.integer(1, 16))
                .then(argument("mode", StringArgumentType.word())
                        .suggests(suggestions(List.of(BotCommands.styles())))
                        .executes(c -> match(c, mode, size))
                        .then(argument("difficulty", StringArgumentType.word())
                                .suggests(suggestions(List.of(BotPvpConfig.difficulties())))
                                .executes(c -> match(c, mode, size))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> spectateTree()
    {
        return literal("spectate")
                .then(literal("stop").executes(c -> PracticeCommands.spectateStop(vanilla(c))))
                .then(argument("name", StringArgumentType.word())
                        .suggests(botSuggestions())
                        .executes(c -> PracticeCommands.spectate(vanilla(c), StringArgumentType.getString(c, "name"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> skinTree()
    {
        return literal("skin")
                .then(argument("bot", StringArgumentType.word())
                        .suggests(botSuggestions())
                        .then(argument("player", StringArgumentType.word())
                                .suggests(onlineNames())
                                .executes(c -> PracticeCommands.skin(vanilla(c),
                                        StringArgumentType.getString(c, "bot"),
                                        StringArgumentType.getString(c, "player")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> traceTree()
    {
        return literal("trace")
                .then(argument("name", StringArgumentType.word())
                        .suggests(suggestions(CombatTraces.names()))
                        .executes(c -> PracticeCommands.trace(vanilla(c), StringArgumentType.getString(c, "name"))));
    }

    /** {@code /bot gui}, which opens the menus. */
    private static LiteralArgumentBuilder<CommandSourceStack> guiTree()
    {
        return literal("gui")
                .executes(c -> MenuCommands.open(vanilla(c)))
                .then(literal("saveas")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        MenuCommands.suggestions(vanilla(c)), b))
                                .executes(c -> MenuCommands.saveAs(vanilla(c), StringArgumentType.getString(c, "name")))))
                .then(literal("discard").executes(c -> MenuCommands.discard(vanilla(c))));
    }

    private static int match(CommandContext<CommandSourceStack> context, MatchManager.Mode mode, String size)
    {
        String difficulty;
        try
        {
            difficulty = StringArgumentType.getString(context, "difficulty");
        }
        catch (IllegalArgumentException notPresent)
        {
            difficulty = PracticeCommands.defaultDifficulty();
        }
        return PracticeCommands.match(vanilla(context), mode, IntegerArgumentType.getInteger(context, size),
                StringArgumentType.getString(context, "mode"), difficulty);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> kitTree()
    {
        return literal("kit")
                .then(literal("list").executes(c -> KitCommands.list(vanilla(c))))
                .then(literal("reload").executes(c -> KitCommands.reload(vanilla(c))))
                .then(literal("give")
                        .then(argument("players", ArgumentTypes.players())
                                .then(argument("kit", StringArgumentType.word())
                                        .suggests(kitSuggestions())
                                        .executes(PaperBotCommands::kitGive)))
                .then(literal("save")
                        .then(argument("name", StringArgumentType.word())
                                .executes(c -> KitCommands.save(vanilla(c), StringArgumentType.getString(c, "name")))))
                .then(literal("delete")
                        .then(argument("name", StringArgumentType.word())
                                .suggests(kitSuggestions())
                                .executes(c -> KitCommands.delete(vanilla(c), StringArgumentType.getString(c, "name")))))
                .then(literal("restore")
                        .executes(c -> KitCommands.restoreSelf(vanilla(c)))
                        .then(argument("players", ArgumentTypes.players())
                                .executes(PaperBotCommands::kitRestore))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> combatSpawnTree()
    {
        return literal("spawn")
                .executes(c -> CombatCommands.spawn(vanilla(c)))
                .then(argument("name", StringArgumentType.word())
                        .suggests(botSuggestions())
                        .executes(PaperBotCommands::combatSpawn)
                        .then(argument("mode", StringArgumentType.word())
                                .suggests(suggestions(List.of(BotCommands.styles())))
                                .then(argument("difficulty", StringArgumentType.word())
                                        .suggests(suggestions(List.of(BotPvpConfig.difficulties())))
                                        .executes(PaperBotCommands::combatSpawn)
                                        .then(literal("at")
                                                .then(argument("position", ArgumentTypes.finePosition())
                                                        .executes(PaperBotCommands::combatSpawn))))
                                .executes(PaperBotCommands::combatSpawn)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> optionTree()
    {
        return literal("option")
                .then(argument("name", StringArgumentType.word())
                        .suggests(botSuggestions())
                        .executes(c -> CombatCommands.showOption(vanilla(c), StringArgumentType.getString(c, "name")))
                        .then(argument("key", StringArgumentType.word())
                                .suggests(suggestions(List.of(BotPvpConfig.keys())))
                                .then(argument("value", StringArgumentType.greedyString())
                                        .executes(c -> CombatCommands.setOption(vanilla(c),
                                                StringArgumentType.getString(c, "name"),
                                                StringArgumentType.getString(c, "key"),
                                                StringArgumentType.getString(c, "value"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> duelTree()
    {
        return literal("duel")
                .then(argument("a", StringArgumentType.word())
                        .suggests(botSuggestions())
                        .then(argument("b", StringArgumentType.word())
                                .suggests(botSuggestions())
                                .executes(c -> CombatCommands.duel(vanilla(c),
                                        StringArgumentType.getString(c, "a"), StringArgumentType.getString(c, "b")))));
    }

    /** {@code /bot <literal> <bot>}, the shape the combat commands take. */
    private static LiteralArgumentBuilder<CommandSourceStack> namedTree(String literal, NamedCommand command)
    {
        return literal(literal)
                .then(argument("name", StringArgumentType.word())
                        .suggests(botSuggestions())
                        .executes(c -> command.run(c, StringArgumentType.getString(c, "name"))));
    }

    @FunctionalInterface
    private interface NamedCommand
    {
        int run(CommandContext<CommandSourceStack> context, String name);
    }

    /** The fake-player controls, the shape Fabric's {@code /player} gives them under. */
    /** {@code /bot <name> ...}: the fake-player controls, named the way {@code /player} names them. */
    private static RequiredArgumentBuilder<CommandSourceStack, String> namedBot()
    {
        return argument("name", StringArgumentType.word())
                .suggests(botSuggestions())
                .then(spawnTree())
                .then(literal("disconnect").executes(PaperBotCommands::disconnect))
                .then(literal("kill").executes(PaperBotCommands::kill))
                .then(literal("equipment").executes(PaperBotCommands::equipment))
                .then(literal("itemCd").executes(PaperBotCommands::itemCd))
                .then(simple("stop", EntityPlayerActionPack::stopAll))
                .then(simple("stopmovement", EntityPlayerActionPack::stopMovement))
                .then(simple("dismount", EntityPlayerActionPack::dismount))
                .then(simple("unsneak", pack -> pack.setSneaking(false)))
                .then(simple("unsprint", pack -> pack.setSprinting(false)))
                .then(timedToggle("sneak", true))
                .then(timedToggle("sprint", true))
                .then(mountTree())
                .then(lookTree())
                .then(turnTree())
                .then(moveTree())
                .then(hotbarTree())
                .then(equipTree())
                .then(unequipTree())
                .then(animateTree())
                .then(action("use", ActionType.USE))
                .then(action("jump", ActionType.JUMP))
                .then(action("swing", ActionType.SWING))
                .then(action("drop", ActionType.DROP_ITEM))
                .then(action("dropStack", ActionType.DROP_STACK))
                .then(action("swapHands", ActionType.SWAP_HANDS))
                .then(attackTree())
                .then(glideTree())
                .then(nav())
                .then(ai())
                .then(faction());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> spawnTree()
    {
        return literal("spawn")
                .executes(PaperBotCommands::spawn)
                .then(literal("in")
                        .then(argument("gamemode", ArgumentTypes.gameMode()).executes(PaperBotCommands::spawn)))
                .then(spawnAt());
    }

    /**
     * {@code at <x> <y> <z> facing <yaw> <pitch> in <dimension> in <gamemode>}: the syntax
     * Fabric's spawn takes, as a chain of one child per level. Brigadier offers a node either a
     * literal or an argument, not both at once, so the position is three plain numbers rather than
     * one position argument with siblings beside it.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> spawnAt()
    {
        return literal("at")
                .then(argument("x", DoubleArgumentType.doubleArg()).then(argument("y", DoubleArgumentType.doubleArg())
                        .then(argument("z", DoubleArgumentType.doubleArg())
                                .executes(PaperBotCommands::spawn)
                                .then(literal("facing")
                                        .then(argument("yaw", DoubleArgumentType.doubleArg())
                                                .then(argument("pitch", DoubleArgumentType.doubleArg())
                                                        .executes(PaperBotCommands::spawn)
                                                        .then(spawnWhere())))))));
    }

    /** {@code in <dimension> in <gamemode>}, the tail the facing ends on. */
    private static LiteralArgumentBuilder<CommandSourceStack> spawnWhere()
    {
        return literal("in")
                .then(argument("dimension", IdentifierArgument.id())
                        .executes(PaperBotCommands::spawn)
                        .then(literal("in")
                                .then(argument("gamemode", ArgumentTypes.gameMode())
                                        .executes(PaperBotCommands::spawn))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mountTree()
    {
        return literal("mount")
                .then(simple("anything", pack -> pack.mount(false)))
                .executes(manipulating(pack -> pack.mount(true)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> lookTree()
    {
        return literal("look")
                .then(simple("north", pack -> pack.look(Direction.NORTH)))
                .then(simple("south", pack -> pack.look(Direction.SOUTH)))
                .then(simple("east", pack -> pack.look(Direction.EAST)))
                .then(simple("west", pack -> pack.look(Direction.WEST)))
                .then(simple("up", pack -> pack.look(Direction.UP)))
                .then(simple("down", pack -> pack.look(Direction.DOWN)))
                .then(literal("at")
                        .then(argument("target", ArgumentTypes.finePosition()).executes(PaperBotCommands::lookAt)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> turnTree()
    {
        return literal("turn")
                .then(simple("left", pack -> pack.turn(-90, 0)))
                .then(simple("right", pack -> pack.turn(90, 0)))
                .then(simple("back", pack -> pack.turn(180, 0)))
                .then(literal("around")
                        .then(argument("degrees", DoubleArgumentType.doubleArg(-360.0D, 360.0D))
                                .executes(PaperBotCommands::turnAround)))
                .then(argument("rotation", ArgumentTypes.rotation())
                        .executes(PaperBotCommands::turnRotation));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> moveTree()
    {
        return literal("move")
                .then(move("forward", 1.0F, 0.0F))
                .then(move("backward", -1.0F, 0.0F))
                .then(move("left", 0.0F, 1.0F))
                .then(move("right", 0.0F, -1.0F))
                .executes(manipulating(EntityPlayerActionPack::stopMovement));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> hotbarTree()
    {
        return literal("hotbar")
                .then(argument("slot", IntegerArgumentType.integer(1, 9)).executes(PaperBotCommands::hotbar));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> equipTree()
    {
        return literal("equip")
                .then(argument("slot", StringArgumentType.word())
                        .suggests(suggestions(EQUIP_SLOTS))
                        .then(argument("item", IdentifierArgument.id())
                                .suggests(itemSuggestions())
                                .executes(PaperBotCommands::equip)));
    }

    /** The slots {@code equip} takes, in Fabric's spelling. */
    private static final List<String> EQUIP_SLOTS = List.of(
            "head", "helmet", "chest", "chestplate", "legs", "leggings", "feet", "boots",
            "mainhand", "weapon", "offhand", "shield");

    private static LiteralArgumentBuilder<CommandSourceStack> unequipTree()
    {
        return literal("unequip")
                .then(argument("slot", StringArgumentType.word())
                        .suggests(suggestions(List.of("head", "chest", "legs", "feet", "mainhand", "offhand")))
                        .executes(PaperBotCommands::unequip));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> animateTree()
    {
        return literal("animate")
                .then(simple("attack", pack -> pack.start(ActionType.SWING, Action.once())))
                .then(simple("use", pack -> pack.start(ActionType.SWING_OFF_HAND, Action.once())))
                .then(simple("continuous", pack -> pack.start(ActionType.SWING, Action.continuous())))
                .then(literal("interval").then(ticks(c -> c.pack().start(ActionType.SWING,
                        Action.interval(c.ticks())))));
    }

    /** One subcommand that only acts on the named bot. */
    private static LiteralArgumentBuilder<CommandSourceStack> simple(String name, Consumer<EntityPlayerActionPack> action)
    {
        return literal(name).executes(manipulating(action));
    }

    /** {@code sneak} and {@code sprint}, each with the {@code for <ticks>} form Fabric gives them. */
    private static LiteralArgumentBuilder<CommandSourceStack> timedToggle(String name, boolean on)
    {
        return literal(name)
                .executes(manipulating(pack -> onOff(pack, on)))
                .then(literal("for")
                        .then(argument("ticks", IntegerArgumentType.integer(1))
                                .executes(manipulating((c, pack) -> {
                                    onOff(pack, on);
                                    pack.setMoveDuration(IntegerArgumentType.getInteger(c, "ticks"));
                                }))));
    }

    private static void onOff(EntityPlayerActionPack pack, boolean on)
    {
        if (on) pack.setSprinting(on);
        else pack.setSneaking(on);
    }

    // --- spawning ---

    private static int spawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        net.minecraft.commands.CommandSourceStack source = vanilla(context);
        String name = named(context);
        Vec3 pos = source.getPosition();
        Double x = resolve(context, "x", Double.class);
        if (x != null)
        {
            pos = new Vec3(x, context.getArgument("y", Double.class), context.getArgument("z", Double.class));
        }
        Vec2 facing = source.getRotation();
        Double yaw = resolve(context, "yaw", Double.class);
        if (yaw != null) facing = new Vec2(yaw.floatValue(), context.getArgument("pitch", Double.class).floatValue());
        ResourceKey<Level> dimension = source.getLevel().dimension();
        Identifier id = resolve(context, "dimension", Identifier.class);
        if (id != null)
        {
            if (source.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id)) == null)
            {
                say(context, "Unknown dimension: " + id, ChatFormatting.RED);
                return 0;
            }
            dimension = ResourceKey.create(Registries.DIMENSION, id);
        }
        GameType mode = gamemode(resolve(context, "gamemode", org.bukkit.GameMode.class));
        if (!EntityPlayerMPFake.createFake(name, source.getServer(), source, pos, facing.y, facing.x,
                dimension, mode, !mode.isSurvival()))
        {
            say(context, "Could not spawn " + name
                    + ": either the name does not exist and fakePlayer.allowSpawningOfflinePlayers is off,"
                    + " or the player is banned, whitelisted or already online", ChatFormatting.RED);
            return 0;
        }
        say(context, "Spawned " + name, ChatFormatting.GREEN);
        return 1;
    }

    private static int combatSpawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        String difficulty;
        try
        {
            difficulty = StringArgumentType.getString(context, "difficulty");
        }
        catch (IllegalArgumentException notPresent)
        {
            difficulty = CombatCommands.defaultDifficulty();
        }
        Vec3 pos;
        try
        {
            pos = position(context, "position");
        }
        catch (IllegalArgumentException notPresent)
        {
            pos = vanilla(context).getPosition();
        }
        String mode;
        try
        {
            mode = StringArgumentType.getString(context, "mode");
        }
        catch (IllegalArgumentException notPresent)
        {
            mode = CombatCommands.DEFAULT_MODE;
        }
        return CombatCommands.spawn(vanilla(context), StringArgumentType.getString(context, "name"), mode, difficulty, pos);
    }

    // --- kits ---

    private static int kitGive(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return KitCommands.give(vanilla(context), players(context, "players"),
                StringArgumentType.getString(context, "kit"));
    }

    private static int kitRestore(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return KitCommands.restore(vanilla(context), players(context, "players"));
    }

    // --- actions ---

    private static LiteralArgumentBuilder<CommandSourceStack> action(String name, ActionType type)
    {
        return literal(name)
                .executes(manipulating(ap -> ap.start(type, Action.once())))
                .then(literal("once").executes(manipulating(ap -> ap.start(type, Action.once()))))
                .then(literal("continuous").executes(manipulating(ap -> ap.start(type, Action.continuous()))))
                .then(literal("interval").then(ticks(c -> c.pack().start(type,
                        Action.interval(c.ticks())))));
    }

    /** The {@code <ticks>} argument every {@code interval} form takes, which starts the action. */
    private static RequiredArgumentBuilder<CommandSourceStack, Integer> ticks(Consumer<Starting> start)
    {
        return argument("ticks", IntegerArgumentType.integer(1))
                .executes(manipulating((c, ap) -> start.accept(new Starting(c, ap))));
    }

    /** The tick count of an {@code interval} form together with the pack it drives. */
    private record Starting(CommandContext<CommandSourceStack> context, EntityPlayerActionPack pack)
    {
        int ticks()
        {
            return IntegerArgumentType.getInteger(context, "ticks");
        }
    }

    /** {@code attack} has its own critical-hit flag, so it cannot be the plain action helper. */
    private static LiteralArgumentBuilder<CommandSourceStack> attackTree()
    {
        return literal("attack")
                .executes(manipulating(ap -> startAttack(ap, false)))
                .then(literal("once").executes(manipulating(ap -> startAttack(ap, false))))
                .then(literal("continuous").executes(manipulating(ap -> startAttack(ap, false, Action.continuous()))))
                .then(literal("interval").then(ticks(c -> startAttack(c.pack(), false))));
    }

    private static void startAttack(EntityPlayerActionPack pack, boolean crit)
    {
        startAttack(pack, crit, Action.once());
    }

    private static void startAttack(EntityPlayerActionPack pack, boolean crit, Action action)
    {
        pack.setAttackCritical(crit);
        pack.start(ActionType.ATTACK, action);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> move(String name, float forward, float strafe)
    {
        return literal(name)
                .executes(manipulating(ap -> {
                    if (forward != 0.0F) ap.setForward(forward);
                    if (strafe != 0.0F) ap.setStrafing(strafe);
                }))
                .then(literal("for").then(argument("ticks", IntegerArgumentType.integer(1))
                        .executes(manipulating((c, ap) -> {
                            if (forward != 0.0F) ap.setForward(forward);
                            if (strafe != 0.0F) ap.setStrafing(strafe);
                            ap.setMoveDuration(IntegerArgumentType.getInteger(c, "ticks"));
                        }))));
    }

    private static int equip(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        ItemStack stack = item(context, "item");
        String slot = StringArgumentType.getString(context, "slot");
        return manipulating(pack -> putOn(pack, slot, stack)).run(context);
    }

    private static void putOn(EntityPlayerActionPack pack, String name, ItemStack stack)
    {
        if (name.equals("mainhand") || name.equals("weapon"))
        {
            pack.getPlayer().setItemInHand(InteractionHand.MAIN_HAND, stack);
            return;
        }
        if (name.equals("offhand") || name.equals("shield"))
        {
            pack.getPlayer().setItemInHand(InteractionHand.OFF_HAND, stack);
            return;
        }
        pack.getPlayer().setItemSlot(slot(name), stack);
    }

    private static int unequip(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        EquipmentSlot slot = slot(StringArgumentType.getString(context, "slot"));
        return manipulating(pack -> {
            if (!pack.getPlayer().getItemBySlot(slot).isEmpty()) pack.getPlayer().setItemSlot(slot, ItemStack.EMPTY);
        }).run(context);
    }

    private static int equipment(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return manipulating(pack -> {
            for (EquipmentSlot slot : EquipmentSlot.values())
            {
                ItemStack stack = pack.getPlayer().getItemBySlot(slot);
                if (!stack.isEmpty()) say(context, slot.getName() + ": " + stack.getHoverName().getString(), ChatFormatting.GRAY);
            }
        }).run(context);
    }

    /** The bare form clears every cooldown the player is carrying, as Fabric's mixin does. */
    private static int itemCd(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return manipulating(pack -> {
            ItemCooldowns cooldowns = pack.getPlayer().getCooldowns();
            Map<Identifier, ?> groups = cooldownGroups(cooldowns);
            // ItemCooldowns has no removeAll, so the groups it holds are collected and then each dropped.
            for (Identifier group : List.copyOf(groups.keySet())) cooldowns.removeCooldown(group);
            say(context, "Cleared " + groups.size() + " item cooldown(s)", ChatFormatting.GRAY);
        }).run(context);
    }

    private static final Field COOLDOWNS = cooldownMap();

    /** Vanilla keeps the active cooldowns in a map and offers no way to list them. */
    private static Field cooldownMap()
    {
        try
        {
            Field field = ItemCooldowns.class.getDeclaredField("cooldowns");
            field.setAccessible(true);
            return field;
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("Vanilla's ItemCooldowns no longer holds its cooldowns in a map", e);
        }
    }

    private static Map<Identifier, ?> cooldownGroups(ItemCooldowns cooldowns)
    {
        try
        {
            @SuppressWarnings("unchecked")
            Map<Identifier, ?> groups = (Map<Identifier, ?>) COOLDOWNS.get(cooldowns);
            return groups;
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("could not read the item cooldowns", e);
        }
    }

    private static int lookAt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        Vec3 target = position(context, "target");
        return manipulating(ap -> ap.lookAt(target)).run(context);
    }

    private static int turnAround(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        float degrees = (float) DoubleArgumentType.getDouble(context, "degrees");
        return manipulating(ap -> ap.turn(degrees, 0)).run(context);
    }

    /** {@code turn <yaw> <pitch>}, the spelling Fabric's {@code /player} takes. */
    private static int turnRotation(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        RotationResolver resolver = resolve(context, "rotation", RotationResolver.class);
        if (resolver == null) throw new IllegalArgumentException("rotation must be a yaw and a pitch");
        Rotation rotation = resolver.resolve(context.getSource());
        return manipulating(ap -> ap.turn(rotation.yaw(), rotation.pitch())).run(context);
    }

    private static int hotbar(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        int slot = IntegerArgumentType.getInteger(context, "slot");
        return manipulating(ap -> ap.setSlot(slot)).run(context);
    }

    /**
     * {@code glide}, the elytra subtree of Fabric's {@code /player}. Every leaf sets the same field on the
     * bot's action pack that Fabric's {@code PlayerCommand} sets, so the two cannot drift apart.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> glideTree()
    {
        return literal("glide")
                .then(simple("start", pack -> pack.setGlideEnabled(true)))
                .then(simple("stop", pack -> pack.setGlideEnabled(false)))
                .then(literal("freeze")
                        .executes(gliding(manipulating(ap -> ap.setGlideFrozen(!ap.isGlideFrozen()))))
                        .then(argument("value", BoolArgumentType.bool())
                                .executes(gliding(manipulating((c, ap) -> ap.setGlideFrozen(BoolArgumentType.getBool(c, "value")))))))
                .then(literal("arrival")
                        .then(simple("stop", pack -> arrival(pack, EntityPlayerActionPack.GlideArrivalAction.STOP)))
                        .then(simple("freeze", pack -> arrival(pack, EntityPlayerActionPack.GlideArrivalAction.FREEZE)))
                        .then(simple("descend", pack -> arrival(pack, EntityPlayerActionPack.GlideArrivalAction.DESCEND)))
                        .then(simple("land", pack -> arrival(pack, EntityPlayerActionPack.GlideArrivalAction.LAND)))
                        .then(simple("circle", pack -> arrival(pack, EntityPlayerActionPack.GlideArrivalAction.CIRCLE))))
                .then(literal("launch")
                        .then(literal("assist")
                                .then(argument("value", BoolArgumentType.bool())
                                        .executes(gliding(manipulating((c, ap) -> ap.setGlideLaunchAssistEnabled(BoolArgumentType.getBool(c, "value")))))))
                        .then(literal("pitch")
                                .then(argument("deg", DoubleArgumentType.doubleArg(-45.0D, 45.0D))
                                        .executes(gliding(manipulating((c, ap) -> ap.setGlideLaunchPitch(
                                                (float) DoubleArgumentType.getDouble(c, "deg")))))))
                        .then(literal("speed")
                                .then(argument("blocksPerTick", DoubleArgumentType.doubleArg(0.0D))
                                        .executes(gliding(manipulating((c, ap) -> ap.setGlideLaunchSpeed(
                                                DoubleArgumentType.getDouble(c, "blocksPerTick")))))))
                        .then(literal("forwardTicks")
                                .then(argument("ticks", IntegerArgumentType.integer(0, 20))
                                        .executes(gliding(manipulating((c, ap) -> ap.setGlideLaunchForwardTicks(
                                                IntegerArgumentType.getInteger(c, "ticks"))))))))
                .then(literal("freezeAtTarget")
                        .then(argument("value", BoolArgumentType.bool())
                                .executes(gliding(manipulating((c, ap) -> ap.setGlideFreezeAtTarget(BoolArgumentType.getBool(c, "value")))))))
                .then(literal("speed")
                        .then(argument("blocksPerTick", DoubleArgumentType.doubleArg(0.0D))
                                .executes(gliding(manipulating((c, ap) -> ap.setGlideSpeed(
                                        DoubleArgumentType.getDouble(c, "blocksPerTick")))))))
                .then(literal("rates")
                        .then(argument("yawDegPerTick", DoubleArgumentType.doubleArg(0.0D))
                                .then(argument("pitchDegPerTick", DoubleArgumentType.doubleArg(0.0D))
                                        .executes(gliding(manipulating((c, ap) -> ap.setGlideRates(
                                                (float) DoubleArgumentType.getDouble(c, "yawDegPerTick"),
                                                (float) DoubleArgumentType.getDouble(c, "pitchDegPerTick"))))))))
                .then(literal("usePitch")
                        .then(argument("value", BoolArgumentType.bool())
                                .executes(gliding(manipulating((c, ap) -> ap.setGlideUsePitchForForward(BoolArgumentType.getBool(c, "value")))))))
                .then(literal("input")
                        .then(argument("forward", DoubleArgumentType.doubleArg(-1.0D, 1.0D))
                                .then(argument("strafe", DoubleArgumentType.doubleArg(-1.0D, 1.0D))
                                        .then(argument("up", DoubleArgumentType.doubleArg(-1.0D, 1.0D))
                                                .executes(gliding(gliding(PaperBotCommands::glideInput)))))))
                .then(literal("heading")
                        .then(argument("yaw", DoubleArgumentType.doubleArg(-360.0D, 360.0D))
                                .then(argument("pitch", DoubleArgumentType.doubleArg(-90.0D, 90.0D))
                                        .executes(gliding(gliding(PaperBotCommands::glideHeading))))))
                .then(literal("goto")
                        .then(literal("smart")
                                .then(argument("pos", ArgumentTypes.finePosition())
                                        .executes(gliding(gliding(PaperBotCommands::glideGotoSmart)))
                                        .then(argument("arrivalRadius", DoubleArgumentType.doubleArg(0.0D))
                                                .executes(gliding(gliding(PaperBotCommands::glideGotoSmart))))))
                        .then(argument("pos", ArgumentTypes.finePosition())
                                .executes(gliding(gliding(PaperBotCommands::glideGoto)))
                                .then(argument("arrivalRadius", DoubleArgumentType.doubleArg(0.0D))
                                        .executes(gliding(gliding(PaperBotCommands::glideGoto))))))
                .then(literal("status").executes(gliding(gliding(PaperBotCommands::glideStatus))));
    }

    /** A leaf of {@code glide}, which the elytra setting refuses outright while it is off. */
    private static Command<CommandSourceStack> gliding(Command<CommandSourceStack> leaf)
    {
        return context -> canGlide(context) ? leaf.run(context) : 0;
    }

    private static void arrival(EntityPlayerActionPack pack, EntityPlayerActionPack.GlideArrivalAction action)
    {
        pack.setGlideArrivalAction(action);
    }

    private static int glideInput(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return manipulating(ap -> {
            ap.setGlideEnabled(true);
            ap.setGlideInput((float) DoubleArgumentType.getDouble(context, "forward"),
                    (float) DoubleArgumentType.getDouble(context, "strafe"),
                    (float) DoubleArgumentType.getDouble(context, "up"));
        }).run(context);
    }

    private static int glideHeading(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return manipulating(ap -> {
            ap.setGlideEnabled(true);
            ap.setGlideHeading((float) DoubleArgumentType.getDouble(context, "yaw"),
                    (float) DoubleArgumentType.getDouble(context, "pitch"));
        }).run(context);
    }

    private static int glideGoto(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        Vec3 pos = position(context, "pos");
        double radius = arrivalRadius(context);
        return manipulating(ap -> {
            ap.setGlideEnabled(true);
            ap.setGlideGoto(pos, radius);
        }).run(context);
    }

    /** {@code glide goto smart}: an elytra path across the world, planned the way Fabric plans it. */
    private static int glideGotoSmart(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        Vec3 goal = position(context, "pos");
        double radius = arrivalRadius(context);
        ServerPlayer player = pack(context).getPlayer();
        if (!(player.level() instanceof ServerLevel level))
        {
            say(context, player.getName().getString() + " is not in a world", ChatFormatting.RED);
            return 0;
        }
        carpet.pvp.nav.ElytraAStarPathfinder.Settings settings =
                carpet.pvp.nav.ElytraAStarPathfinder.Settings.defaults();
        List<net.minecraft.core.BlockPos> raw = new carpet.pvp.nav.ElytraAStarPathfinder().findPath(level,
                net.minecraft.core.BlockPos.containing(player.position()), net.minecraft.core.BlockPos.containing(goal), settings);
        if (raw == null || raw.isEmpty())
        {
            say(context, "No smart path found (range/terrain/chunks). Try a higher goal Y or move closer.", ChatFormatting.RED);
            return 0;
        }
        List<Vec3> waypoints = new ArrayList<>();
        for (net.minecraft.core.BlockPos pos : carpet.pvp.nav.ElytraAStarPathfinder.compressWaypoints(raw, settings.waypointStride()))
        {
            waypoints.add(new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D));
        }
        return manipulating(ap -> {
            ap.setGlideEnabled(true);
            ap.setGlideArrivalAction(EntityPlayerActionPack.GlideArrivalAction.LAND);
            ap.setGlideGotoWaypoints(waypoints, goal, radius);
            say(context, "smart glide path set with " + waypoints.size() + " waypoints for " + ap.getPlayer().getName(),
                    ChatFormatting.GREEN);
        }).run(context);
    }

    private static int glideStatus(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return manipulating(ap -> say(context, "glide: enabled=" + ap.isGlideEnabled()
                + ", frozen=" + ap.isGlideFrozen()
                + ", speed=" + String.format("%.3f", ap.getGlideSpeed())
                + ", arrival=" + ap.getGlideArrivalAction().name().toLowerCase(), ChatFormatting.GREEN)).run(context);
    }

    // --- navigation ---

    private static LiteralArgumentBuilder<CommandSourceStack> nav()
    {
        return literal("nav")
                .then(literal("stop").executes(manipulating(EntityPlayerActionPack::stopNavigation)))
                .then(literal("status").executes(PaperBotCommands::navStatus))
                .then(literal("options")
                        .then(literal("reset").executes(manipulating(EntityPlayerActionPack::resetNavOptions)))
                        .then(argument("option", StringArgumentType.word())
                                .then(argument("value", BoolArgumentType.bool())
                                        .executes(PaperBotCommands::navOption))))
                .then(navGoto())
                .then(literal("come")
                        .executes(c -> navCome(c, 1.0D))
                        .then(argument("radius", DoubleArgumentType.doubleArg(0.1D, 16.0D))
                                .executes(c -> navCome(c, DoubleArgumentType.getDouble(c, "radius")))))
                .then(literal("follow")
                        .then(argument("target", ArgumentTypes.player())
                                .executes(PaperBotCommands::navFollow)
                                .then(argument("radius", DoubleArgumentType.doubleArg(0.1D, 16.0D))
                                        .executes(PaperBotCommands::navFollow))))
                .then(navPatrol())
                .then(navChase());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> navGoto()
    {
        return literal("goto")
                .then(argument("pos", ArgumentTypes.finePosition())
                        .executes(c -> navGoto(c, BotNavMode.AUTO))
                        .then(argument("arrivalRadius", DoubleArgumentType.doubleArg(0.0D, 16.0D))
                                .executes(c -> navGoto(c, BotNavMode.AUTO))))
                .then(literal("land").then(argument("pos", ArgumentTypes.finePosition())
                        .executes(c -> navGoto(c, BotNavMode.LAND))
                        .then(argument("arrivalRadius", DoubleArgumentType.doubleArg(0.0D, 16.0D))
                                .executes(c -> navGoto(c, BotNavMode.LAND)))))
                .then(literal("water").then(argument("pos", ArgumentTypes.finePosition())
                        .executes(c -> navGoto(c, BotNavMode.WATER))
                        .then(argument("arrivalRadius", DoubleArgumentType.doubleArg(0.0D, 16.0D))
                                .executes(c -> navGoto(c, BotNavMode.WATER)))));
    }

    /**
     * {@code nav patrol}, with as many waypoints as Fabric takes. Every form ends in the same three
     * literals, so a fourth waypoint is only a case of the same tail rather than a command of its own.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> navPatrol()
    {
        return literal("patrol")
                .then(argument("pos1", ArgumentTypes.finePosition())
                        .then(argument("pos2", ArgumentTypes.finePosition())
                                .executes(c -> navPatrol(c, true))
                                .then(literal("loop").executes(c -> navPatrol(c, true)))
                                .then(literal("once").executes(c -> navPatrol(c, false)))
                                .then(patrolWaypoint("pos3").then(patrolWaypoint("pos4")))));
    }

    /** One more waypoint of {@code nav patrol}, with the same {@code loop} and {@code once} tail; the
     *  next waypoint hangs off this one, so the chain is a list rather than a set of siblings. */
    private static RequiredArgumentBuilder<CommandSourceStack, FinePositionResolver> patrolWaypoint(String name)
    {
        return argument(name, ArgumentTypes.finePosition())
                .executes(c -> navPatrol(c, true))
                .then(literal("loop").executes(c -> navPatrol(c, true)))
                .then(literal("once").executes(c -> navPatrol(c, false)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> navChase()
    {
        return literal("chase")
                .then(literal("stop").executes(manipulating(EntityPlayerActionPack::stopNavigation)))
                .then(chaseTail("attack", false))
                .then(chaseTail("crit", true))
                .then(chaseTail("jumpreset", true));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> chaseTail(String name, boolean crit)
    {
        return literal(name)
                .executes(c -> navChase(c, crit))
                .then(argument("range", DoubleArgumentType.doubleArg(0.5D, 6.0D))
                        .executes(c -> navChase(c, crit))
                        .then(argument("interval", IntegerArgumentType.integer(0, 40))
                                .executes(c -> navChase(c, crit))
                                .then(argument("target", ArgumentTypes.players())
                                        .executes(c -> navChase(c, crit)))));
    }

    private static int navStatus(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        if (!canNavigate(context)) return 0;
        return manipulating(ap -> {
            if (!ap.isNavEnabled())
            {
                say(context, "Navigation is disabled for " + ap.getPlayer().getName().getString(), ChatFormatting.YELLOW);
                return;
            }
            say(context, "Navigation for " + ap.getPlayer().getName().getString() + ": mode " + ap.getNavMode()
                    + " target " + ap.getNavTargetPos() + " radius " + ap.getNavArrivalRadius(), ChatFormatting.GREEN);
        }).run(context);
    }

    private static int navGoto(CommandContext<CommandSourceStack> context, BotNavMode mode) throws CommandSyntaxException
    {
        if (!canNavigate(context)) return 0;
        Vec3 pos = position(context, "pos");
        return manipulating(ap -> ap.setNavGoto(pos, mode, arrivalRadius(context))).run(context);
    }

    private static double arrivalRadius(CommandContext<CommandSourceStack> context)
    {
        try
        {
            return DoubleArgumentType.getDouble(context, "arrivalRadius");
        }
        catch (IllegalArgumentException notPresent)
        {
            return 1.0D;
        }
    }

    /** Elytra gliding is a setting of its own, the way Fabric's fakePlayerElytraGlide rule is. */
    private static boolean canGlide(CommandContext<CommandSourceStack> context)
    {
        if (carpet.pvp.BotSettings.fakePlayerElytraGlide) return true;
        say(context, "Elytra gliding controls are disabled. Turn navigation.elytraGlide on in config.yml first.",
                ChatFormatting.RED);
        return false;
    }

    /** Navigation is a setting of its own, and only a bot can be navigated. */
    private static boolean canNavigate(CommandContext<CommandSourceStack> context)
    {
        if (carpet.pvp.BotSettings.fakePlayerNavigation) return true;
        say(context, "Navigation is off. Turn navigation.enabled on in config.yml first.", ChatFormatting.RED);
        return false;
    }

    private static int navCome(CommandContext<CommandSourceStack> context, double radius) throws CommandSyntaxException
    {
        if (!canNavigate(context)) return 0;
        Vec3 here = vanilla(context).getPosition();
        return manipulating(ap -> ap.setNavCome(here, radius)).run(context);
    }

    private static int navFollow(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        if (!canNavigate(context)) return 0;
        ServerPlayer target = only(context, "target");
        double given = 1.0D;
        try
        {
            given = DoubleArgumentType.getDouble(context, "radius");
        }
        catch (IllegalArgumentException notPresent)
        {
            // The default radius the Fabric side uses.
        }
        double followRadius = given;
        return manipulating(ap -> ap.setNavFollow(target.getUUID(), followRadius)).run(context);
    }

    private static int navPatrol(CommandContext<CommandSourceStack> context, boolean loop) throws CommandSyntaxException
    {
        if (!canNavigate(context)) return 0;
        List<Vec3> waypoints = new ArrayList<>();
        for (String name : List.of("pos1", "pos2", "pos3", "pos4"))
        {
            if (resolve(context, name, FinePositionResolver.class) == null) break;
            waypoints.add(position(context, name));
        }
        return manipulating(ap -> ap.setNavPatrol(waypoints, loop)).run(context);
    }

    private static int navChase(CommandContext<CommandSourceStack> context, boolean crit) throws CommandSyntaxException
    {
        if (!canNavigate(context)) return 0;
        ServerPlayer target = optional(context, "target");
        if (target == null)
        {
            // With no target named, the Fabric side takes the only other player there is.
            net.minecraft.commands.CommandSourceStack source = vanilla(context);
            List<ServerPlayer> others = new ArrayList<>();
            for (ServerPlayer player : source.getServer().getPlayerList().getPlayers())
            {
                if (player != source.getPlayer()) others.add(player);
            }
            if (others.isEmpty())
            {
                say(context, "There is nobody else to chase", ChatFormatting.RED);
                return 0;
            }
            target = others.getFirst();
        }
        double range = 3.0D;
        int interval = 0;
        try
        {
            range = DoubleArgumentType.getDouble(context, "range");
        }
        catch (IllegalArgumentException notPresent)
        {
            // The default range.
        }
        try
        {
            interval = IntegerArgumentType.getInteger(context, "interval");
        }
        catch (IllegalArgumentException notPresent)
        {
            // Continuous, as the Fabric side spells it without an interval.
        }
        ServerPlayer chased = target;
        double chaseRange = range;
        int every = interval;
        return manipulating(ap -> startChase(ap, chased, crit, chaseRange, every)).run(context);
    }

    private static void startChase(EntityPlayerActionPack pack, ServerPlayer target, boolean crit, double range, int interval)
    {
        pack.setAttackCritical(crit);
        pack.setNavChase(target.getUUID(), crit, range, interval);
    }

    private static int navOption(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        String option = StringArgumentType.getString(context, "option");
        boolean value = BoolArgumentType.getBool(context, "value");
        return manipulating(ap -> ap.setNavOption(option, value)).run(context);
    }

    // --- combat brain and factions ---

    private static LiteralArgumentBuilder<CommandSourceStack> ai()
    {
        return literal("ai")
                .then(literal("show").executes(PaperBotCommands::aiShow))
                .then(literal("reset").executes(PaperBotCommands::aiReset))
                .then(argument("setting", StringArgumentType.word())
                        .suggests(suggestions(List.of(BotPvpConfig.keys())))
                        .then(argument("value", StringArgumentType.greedyString())
                                .executes(PaperBotCommands::aiSet)));
    }

    private static int aiShow(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return manipulating(pack -> {
            if (pack.getPlayer() instanceof EntityPlayerMPFake bot)
            {
                say(context, bot.getName().getString() + ": " + bot.getPvpConfig().describe(), ChatFormatting.YELLOW);
            }
        }).run(context);
    }

    private static int aiReset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return manipulating(pack -> {
            if (pack.getPlayer() instanceof EntityPlayerMPFake bot)
            {
                bot.resetPvpConfig();
                FactionManager.leave(bot.getUUID());
            }
        }).run(context);
    }

    private static int aiSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        String setting = StringArgumentType.getString(context, "setting");
        String value = StringArgumentType.getString(context, "value");
        String[] problem = {null};
        int done = manipulating(pack -> {
            if (!(pack.getPlayer() instanceof EntityPlayerMPFake bot)) return;
            String error = bot.getPvpConfig().apply(setting, value);
            if (error != null)
            {
                problem[0] = error;
                return;
            }
            String faction = bot.getPvpConfig().faction;
            if (faction == null) FactionManager.leave(bot.getUUID());
            else FactionManager.join(faction, bot.getUUID());
        }).run(context);
        if (problem[0] != null)
        {
            say(context, problem[0], ChatFormatting.RED);
            return 0;
        }
        say(context, "Set " + setting + " = " + value + " on " + done + " bot(s)", ChatFormatting.YELLOW);
        return done;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> faction()
    {
        return literal("faction")
                .then(literal("list").executes(PaperBotCommands::factionList))
                .then(literal("info").executes(PaperBotCommands::factionInfo))
                .then(literal("create").then(argument("faction", StringArgumentType.word())
                        .executes(c -> FactionManager.create(StringArgumentType.getString(c, "faction")) ? 1 : 0)))
                .then(literal("delete").then(argument("faction", StringArgumentType.word())
                        .executes(c -> FactionManager.delete(StringArgumentType.getString(c, "faction")) ? 1 : 0)))
                .then(literal("join").then(argument("faction", StringArgumentType.word())
                        .executes(PaperBotCommands::factionJoin)))
                .then(literal("leave").executes(PaperBotCommands::factionLeave))
                .then(literal("ally")
                        .then(argument("a", StringArgumentType.word())
                                .then(argument("b", StringArgumentType.word())
                                        .executes(c -> FactionManager.ally(StringArgumentType.getString(c, "a"),
                                                StringArgumentType.getString(c, "b")) ? 1 : 0))))
                .then(literal("unally")
                        .then(argument("a", StringArgumentType.word())
                                .then(argument("b", StringArgumentType.word())
                                        .executes(c -> FactionManager.unally(StringArgumentType.getString(c, "a"),
                                                StringArgumentType.getString(c, "b")) ? 1 : 0))));
    }

    private static int factionList(CommandContext<CommandSourceStack> context)
    {
        Set<String> all = FactionManager.allFactions();
        if (all.isEmpty())
        {
            say(context, "No factions", ChatFormatting.YELLOW);
            return 0;
        }
        say(context, "Factions: " + String.join(", ", all), ChatFormatting.YELLOW);
        return all.size();
    }

    private static int factionInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return manipulating(pack -> {
            if (!(pack.getPlayer() instanceof EntityPlayerMPFake bot)) return;
            String faction = bot.getPvpConfig().faction;
            if (faction == null)
            {
                say(context, bot.getName().getString() + " has no faction", ChatFormatting.YELLOW);
                return;
            }
            String info = FactionManager.info(faction);
            say(context, info == null ? "Faction " + faction : info, ChatFormatting.YELLOW);
        }).run(context);
    }

    private static int factionJoin(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        String faction = StringArgumentType.getString(context, "faction");
        FactionManager.create(faction);
        return manipulating(pack -> {
            if (pack.getPlayer() instanceof EntityPlayerMPFake bot)
            {
                bot.getPvpConfig().faction = faction;
                FactionManager.join(faction, bot.getUUID());
            }
        }).run(context);
    }

    private static int factionLeave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return manipulating(pack -> {
            if (pack.getPlayer() instanceof EntityPlayerMPFake bot)
            {
                bot.getPvpConfig().faction = null;
                FactionManager.leave(bot.getUUID());
            }
        }).run(context);
    }

    // --- removing ---

    private static int disconnect(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return manipulating(pack -> {
            if (pack.getPlayer() instanceof EntityPlayerMPFake fake) fake.fakePlayerDisconnect(Component.literal(""));
        }).run(context);
    }

    private static int kill(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return manipulating(pack -> {
            if (pack.getPlayer() instanceof EntityPlayerMPFake fake) fake.kill((ServerLevel) pack.getPlayer().level());
        }).run(context);
    }

    // --- plumbing ---

    /** The bot the command names, as the vanilla source stack every piece of shared code takes. */
    private static net.minecraft.commands.CommandSourceStack vanilla(CommandContext<CommandSourceStack> context)
    {
        if (context.getSource() instanceof PaperCommandSourceStack source) return source.getHandle();
        // The console has no source of its own to unwrap, so the plugin's server builds one.
        return CarpetPvpPlugin.get().server().createCommandSourceStack();
    }

    private static net.minecraft.server.MinecraftServer server(CommandContext<CommandSourceStack> context)
    {
        return vanilla(context).getServer();
    }

    /** The accounts that are on the server now, which is what a skin can be taken from. */
    private static SuggestionProvider<CommandSourceStack> onlineNames()
    {
        return (context, builder) -> SharedSuggestionProvider.suggest(
                server(context).getPlayerList().getPlayerNamesArray(), builder);
    }

    private static void say(CommandContext<CommandSourceStack> context, String message, ChatFormatting colour)
    {
        vanilla(context).sendSuccess(() -> Component.literal(message).withStyle(colour), false);
    }

    /** Runs an action on the bot the command names. */
    private static Command<CommandSourceStack> manipulating(BiConsumer<CommandContext<CommandSourceStack>, EntityPlayerActionPack> action)
    {
        return context -> {
            EntityPlayerActionPack pack = pack(context);
            action.accept(context, pack);
            return 1;
        };
    }

    private static Command<CommandSourceStack> manipulating(Consumer<EntityPlayerActionPack> action)
    {
        return context -> {
            action.accept(pack(context));
            return 1;
        };
    }

    /** The action pack of the named bot; a real player has none without Carpet's mixin. */
    private static EntityPlayerActionPack pack(CommandContext<CommandSourceStack> context)
    {
        String name = named(context);
        ServerPlayer player = CarpetPvpPlugin.get().server().getPlayerList().getPlayerByName(name);
        if (player instanceof EntityPlayerMPFake fake) return fake.getActionPack();
        say(context, name + " is not a bot of this server", ChatFormatting.RED);
        throw new IllegalArgumentException(name + " is not a bot");
    }

    private static String named(CommandContext<CommandSourceStack> context)
    {
        return StringArgumentType.getString(context, "name");
    }

    private static GameType gamemode(org.bukkit.GameMode gamemode)
    {
        if (gamemode == null) return GameType.SURVIVAL;
        return switch (gamemode)
        {
            case CREATIVE -> GameType.CREATIVE;
            case ADVENTURE -> GameType.ADVENTURE;
            case SPECTATOR -> GameType.SPECTATOR;
            case SURVIVAL -> GameType.SURVIVAL;
        };
    }

    /** The equipment slot a name spells, the way Fabric's EquipmentSlotMapping reads it. */
    private static EquipmentSlot slot(String name)
    {
        return switch (name)
        {
            case "head", "helmet" -> EquipmentSlot.HEAD;
            case "chest", "chestplate" -> EquipmentSlot.CHEST;
            case "legs", "leggings" -> EquipmentSlot.LEGS;
            case "feet", "boots" -> EquipmentSlot.FEET;
            case "mainhand", "weapon" -> EquipmentSlot.MAINHAND;
            default -> EquipmentSlot.OFFHAND;
        };
    }

    private static List<ServerPlayer> players(CommandContext<CommandSourceStack> context, String argument)
            throws CommandSyntaxException
    {
        PlayerSelectorArgumentResolver resolver = resolve(context, argument, PlayerSelectorArgumentResolver.class);
        if (resolver == null) throw new IllegalArgumentException(argument + " must name players");
        List<ServerPlayer> found = new ArrayList<>();
        for (org.bukkit.entity.Player player : resolver.resolve(context.getSource()))
        {
            found.add(((CraftPlayer) player).getHandle());
        }
        if (found.isEmpty()) throw new IllegalArgumentException("No players matched " + argument);
        return found;
    }

    private static ServerPlayer only(CommandContext<CommandSourceStack> context, String argument)
            throws CommandSyntaxException
    {
        ServerPlayer found = optional(context, argument);
        if (found == null) throw new IllegalArgumentException(argument + " must name exactly one player");
        return found;
    }

    /** The single player an argument names, or null when it is absent or names nobody. */
    private static ServerPlayer optional(CommandContext<CommandSourceStack> context, String argument)
            throws CommandSyntaxException
    {
        PlayerSelectorArgumentResolver resolver = resolve(context, argument, PlayerSelectorArgumentResolver.class);
        if (resolver == null) return null;
        for (org.bukkit.entity.Player player : resolver.resolve(context.getSource()))
        {
            return ((CraftPlayer) player).getHandle();
        }
        return null;
    }

    private static Vec3 position(CommandContext<CommandSourceStack> context, String argument)
            throws CommandSyntaxException
    {
        FinePositionResolver resolver = resolve(context, argument, FinePositionResolver.class);
        if (resolver == null) throw new IllegalArgumentException(argument + " must name a position");
        FinePosition position = resolver.resolve(context.getSource());
        return new Vec3(position.x(), position.y(), position.z());
    }

    private static ItemStack item(CommandContext<CommandSourceStack> context, String argument)
    {
        Identifier id = context.getArgument(argument, Identifier.class);
        if (!BuiltInRegistries.ITEM.containsKey(id))
        {
            throw new IllegalArgumentException("Unknown item: " + id);
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return new ItemStack(item);
    }

    private static <T> T resolve(CommandContext<CommandSourceStack> context, String argument, Class<T> type)
    {
        try
        {
            return context.getArgument(argument, type);
        }
        catch (IllegalArgumentException notPresent)
        {
            return null;
        }
    }

    /** The fixed word lists of this command tree are the only things ever suggested. */
    private static SuggestionProvider<CommandSourceStack> suggestions(List<String> options)
    {
        return (context, builder) -> SharedSuggestionProvider.suggest(options, builder);
    }

    private static SuggestionProvider<CommandSourceStack> botSuggestions()
    {
        return (context, builder) -> SharedSuggestionProvider.suggest(
                CombatCommands.bots(context.getSource() instanceof PaperCommandSourceStack source
                        ? source.getHandle().getServer() : CarpetPvpPlugin.get().server()), builder);
    }

    private static SuggestionProvider<CommandSourceStack> kitSuggestions()
    {
        return (context, builder) -> SharedSuggestionProvider.suggest(
                KitStore.of(server(context)).names(), builder);
    }

    private static SuggestionProvider<CommandSourceStack> itemSuggestions()
    {
        return (context, builder) -> SharedSuggestionProvider.suggest(
                BuiltInRegistries.ITEM.keySet().stream().map(Identifier::toString).toList(), builder);
    }
}
