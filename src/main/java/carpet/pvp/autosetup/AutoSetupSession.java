package carpet.pvp.autosetup;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitInventory;
import carpet.pvp.kit.KitStore;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One player's {@code /auto-setup} session: the arena they fight on, the bot they fight, and
 * everything that has to be given back when it is over.
 *
 * <p>The session is opened by writing down what the player owned <em>before</em> anything of theirs
 * is touched, so that a crash costs them nothing: that file is all a recovery needs to put them
 * back. From then on a round runs by itself: three seconds of countdown, the fight, and the menu
 * with the score once one of the two is down.</p>
 */
public final class AutoSetupSession
{
    /** Ticks of countdown between one round and the next: three seconds. */
    private static final int COUNTDOWN = 60;
    /** The health carpet's fake players are left at by their own death: half a heart. */
    private static final float ONE_HIT_POINT = 1.0F;
    /** The equipment slots a saved inventory holds, in the order they are kept. */
    static final EquipmentSlot[] EQUIPMENT = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND
    };

    /** Where a round is at. */
    public enum Stage { COUNTDOWN, FIGHTING, MENU }

    /** What happened to one of the fighters on a tick of a round. */
    private enum Down { NONE, DIED, TOTEM }

    private final MinecraftServer server;
    private final RegistryAccess registries;
    private final String playerName;
    private AutoMode mode;
    private BotPvpConfig.Difficulty difficulty;
    private SavedState saved;
    private ArenaBlocks blocks;
    private Arena arena;
    private Kit kit;
    private String botName;
    private EntityPlayerMPFake bot;
    private Stage stage = Stage.COUNTDOWN;
    private int countdown = COUNTDOWN;
    private int saidAt = -1;
    private int playerWins;
    private int botWins;
    private final Watched playerWatched = new Watched();
    private final Watched botWatched = new Watched();

    private AutoSetupSession(MinecraftServer server, ServerPlayer player)
    {
        this.server = server;
        this.registries = server.registryAccess();
        this.playerName = player.getName().getString();
    }

    /** What went wrong on the way into a session; the message is for the player. */
    public static class Failed extends RuntimeException
    {
        Failed(String message)
        {
            super(message);
        }
    }

    /**
     * Sets a session up: remembers what the player owned, writes it down, builds the arena, turns
     * the rules on, hands out the kit, moves the player in and puts a bot in front of them.
     *
     * <p>Whatever goes wrong on the way is undone before this returns, so a session either exists
     * completely or not at all.</p>
     *
     * @throws Failed when the session cannot be started, with the reason to tell the player
     */
    public static AutoSetupSession open(MinecraftServer server, ServerPlayer player, AutoMode mode,
            BotPvpConfig.Difficulty difficulty)
    {
        AutoSetupSession session = new AutoSetupSession(server, player);
        try
        {
            session.start(player, mode, difficulty);
        }
        catch (Failed e)
        {
            session.close(true);
            throw e;
        }
        catch (RuntimeException e)
        {
            session.close(true);
            throw new Failed("the session could not be started: " + e);
        }
        return session;
    }

    private void start(ServerPlayer player, AutoMode mode, BotPvpConfig.Difficulty difficulty)
    {
        this.mode = mode;
        this.difficulty = difficulty;
        this.blocks = new ArenaBlocks();
        this.kit = KitStore.of(server).get(mode.kitName())
                .orElseThrow(() -> new Failed("there is no kit called " + mode.kitName()));
        // What the player owned is read before anything of theirs is touched, and the rules only go
        // on once that is known to work: a stack that cannot be written leaves everything as it was.
        SavedState things = capture(player);
        this.saved = things.withRules(switchRulesOn()).withBlocks(blocks);

        // The file goes down before the player's inventory is touched. Until it is written there
        // is nothing to undo, and from then on a crash has everything it needs to undo.
        AutoSetupManager.write(this);
        arena = Arena.build(level(player), mode, player.blockPosition(), blocks);
        AutoSetupManager.write(this);

        KitInventory.overwrite(player, kit, registries);
        player.setGameMode(GameType.SURVIVAL);
        spawnBot();
        Menus.started(player, this);
    }

    /** One tick of the session: how the round standing at is going, then what the next one needs. */
    public void tick()
    {
        ServerPlayer player = player();
        if (player == null) return;
        if (stage == Stage.FIGHTING && roundOver(player)) return;
        if (!ensureBot()) return;
        if (stage == Stage.COUNTDOWN) tickCountdown();
    }

    /**
     * Ends the session: the bot goes, and then everything the session took is given back in one go,
     * which is the same work a login after a crash does. Only once the player is whole again is the
     * file that held their things deleted.
     *
     * @param quiet true when nobody is left to tell, as on a disconnect or a server stop
     * @return whether the player got everything back, which is what the file is kept for
     */
    public boolean close(boolean quiet)
    {
        removeBot();
        ServerPlayer player = player();
        boolean givenBack = AutoSetupManager.giveBack(server, player, saved);
        boolean forgotten = AutoSetupManager.forget(this, givenBack);
        if (player != null && !quiet && forgotten) Menus.stopped(player);
        return givenBack;
    }

    /** Puts both fighters back on their feet and starts the countdown of the next round. */
    public void rematch()
    {
        if (player() == null || !ensureBot()) return;
        prepare();
        Menus.rematch(player(), this);
    }

    /** The difficulty of this session from now on, which the next round starts with. */
    public void setDifficulty(BotPvpConfig.Difficulty wanted)
    {
        difficulty = wanted;
        if (bot != null) bot.getPvpConfig().applyDifficulty(wanted.name());
    }

    // ===== the session as the command and the self-test see it =====

    public String playerName()
    {
        return playerName;
    }

    public AutoMode mode()
    {
        return mode;
    }

    public BotPvpConfig.Difficulty difficulty()
    {
        return difficulty;
    }

    public Stage stage()
    {
        return stage;
    }

    /** The arena the fight is on, or null while it has not been built yet. */
    public Arena arena()
    {
        return arena;
    }

    public String botName()
    {
        return botName;
    }

    public int playerWins()
    {
        return playerWins;
    }

    public int botWins()
    {
        return botWins;
    }

    /** Ticks of countdown left before the fight starts. */
    public int countdown()
    {
        return countdown;
    }

    MinecraftServer server()
    {
        return server;
    }

    /** What has to be on disk for this session to be recoverable. */
    SessionFile file()
    {
        return new SessionFile(SessionFile.VERSION, playerName, mode.name().toLowerCase(Locale.ROOT),
                difficulty.name().toLowerCase(Locale.ROOT), saved);
    }

    // ===== the rounds =====

    /** Whole fighters, both in place, both with the kit, and the countdown running. */
    private void prepare()
    {
        ServerPlayer player = player();
        whole(player);
        if (bot != null)
        {
            whole(bot);
            teleport(bot, arena.botSpawn(), arena.botYaw());
        }
        teleport(player, arena.playerSpawn(), arena.playerYaw());
        countdown = COUNTDOWN;
        saidAt = -1;
        stage = Stage.COUNTDOWN;
        playerWatched.reset();
        botWatched.reset();
    }

    private void tickCountdown()
    {
        int left = countdown;
        if (left % 20 == 0 && left != saidAt)
        {
            saidAt = left;
            Menus.countdown(player(), left / 20);
        }
        countdown = left - 1;
        if (countdown > 0) return;
        countdown = 0;
        stage = Stage.FIGHTING;
        bot.getPvpConfig().combat = true;
        Menus.fight(player(), this);
    }

    /**
     * Whether the round standing at is over, which is what the score is about: one of the two is
     * down, the bot is gone, or in crystal a totem went off.
     *
     * @return true when the round ended and the menu went up
     */
    private boolean roundOver(ServerPlayer player)
    {
        if (player(server, botName) == null)
        {
            endRound(true, "the bot left");
            return true;
        }
        boolean crystal = mode == AutoMode.CRYSTAL;
        Down mine = playerWatched.check(player, crystal);
        if (mine != Down.NONE)
        {
            endRound(false, mine == Down.TOTEM ? "a totem saved you" : "you died");
            return true;
        }
        Down theirs = botWatched.check(bot, crystal);
        if (theirs != Down.NONE)
        {
            endRound(true, theirs == Down.TOTEM ? "a totem saved the bot" : "the bot died");
            return true;
        }
        return false;
    }

    /** Full health, no effects, no fire, a full stomach and the kit. */
    private void whole(ServerPlayer fighter)
    {
        fighter.setHealth(fighter.getMaxHealth());
        fighter.setAbsorptionAmount(0.0F);
        fighter.clearFire();
        fighter.removeAllEffects();
        fighter.getFoodData().setFoodLevel(20);
        KitInventory.overwrite(fighter, kit, registries);
    }

    private void endRound(boolean playerWon, String why)
    {
        stage = Stage.MENU;
        if (bot != null) bot.getPvpConfig().combat = false;
        if (playerWon) playerWins++;
        else botWins++;
        Menus.roundOver(player(), this, playerWon, why);
    }

    /**
     * What a round watches on one of the two fighters. Carpet's fake players do not stay dead: one
     * that dies drops to a single hit point for a tick and is put back on its feet a tick later, so
     * the death of a bot has to be read as that dip followed by a whole fighter. A real player
     * simply stops being alive.
     */
    private static final class Watched
    {
        private float health = -1.0F;
        private boolean dipped;
        private int totems = -1;

        /** Starts a new round with nothing remembered of the last one. */
        void reset()
        {
            health = -1.0F;
            dipped = false;
            totems = -1;
        }

        Down check(ServerPlayer fighter, boolean totemEndsRound)
        {
            int totems = totems(fighter);
            // A totem going off is all the health back with one fewer in the inventory, and in
            // crystal that is what a round is about.
            boolean popped = this.totems >= 0 && totems < this.totems && whole(fighter);
            this.totems = totems;
            float before = this.health;
            float health = fighter.getHealth();
            this.health = health;
            if (popped)
            {
                dipped = false;
                return totemEndsRound ? Down.TOTEM : Down.NONE;
            }
            if (!fighter.isAlive())
            {
                return Down.DIED;
            }
            if (dipped && whole(fighter))
            {
                dipped = false;
                return Down.DIED;
            }
            // A dip only counts when the health fell into it this tick and the fighter is not still
            // recovering from a hit, which is what a hit that happens to leave half a heart is not.
            dipped = health <= ONE_HIT_POINT && fighter.hurtTime == 0 && before > ONE_HIT_POINT;
            return Down.NONE;
        }

        private static boolean whole(ServerPlayer fighter)
        {
            return fighter.getHealth() >= fighter.getMaxHealth();
        }
    }

    // ===== the bot =====

    private void spawnBot()
    {
        BlockPos at = arena.botSpawn();
        botName = AutoSetupManager.botNameFor(server, playerName);
        if (!EntityPlayerMPFake.createFake(botName, server, new Vec3(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D),
                arena.botYaw(), 0.0F, arena.dimension(), GameType.SURVIVAL, false))
        {
            throw new Failed("the bot " + botName + " could not be spawned");
        }
    }

    /**
     * The bot to fight this round, armed the first time it turns up. It is in the player list as
     * soon as it is spawned on an offline server and a tick or two later when its profile still has
     * to be looked up; one that went away in between is replaced, so that a session is never left
     * with nobody to fight.
     */
    private boolean ensureBot()
    {
        if (bot != null && player(server, botName) == bot) return true;
        bot = null;
        if (botName != null && player(server, botName) == null
                && !EntityPlayerMPFake.isSpawningPlayer(botName))
        {
            spawnBot();
        }
        if (!(player(server, botName) instanceof EntityPlayerMPFake found)) return false;
        bot = found;
        BotPvpConfig cfg = found.getPvpConfig();
        cfg.combatStyle = mode.style();
        cfg.combat = false;
        cfg.autoTarget = true;
        cfg.targetPlayers = true;
        // A fake player is a bot as far as the target filters are concerned, and the player running
        // a session may well be one; whoever is nearest in the arena is who the fight is with.
        cfg.targetBots = true;
        cfg.targetMobs = false;
        cfg.faction = null;
        cfg.applyDifficulty(difficulty.name());
        if (stage != Stage.MENU) prepare();
        return true;
    }

    private void removeBot()
    {
        EntityPlayerMPFake fighter = bot;
        if (fighter != null) fighter.fakePlayerDisconnect(Component.literal("The /auto-setup session is over"));
        bot = null;
    }

    // ===== what the session keeps hold of =====

    private SavedState capture(ServerPlayer player)
    {
        Inventory inventory = player.getInventory();
        List<String> slots = new ArrayList<>(Inventory.INVENTORY_SIZE + EQUIPMENT.length);
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++)
        {
            slots.add(encode(registries, inventory.getItem(i)));
        }
        for (EquipmentSlot slot : EQUIPMENT) slots.add(encode(registries, player.getItemBySlot(slot)));
        return new SavedState(new SavedState.Spot(player.level().dimension().identifier().toString(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()),
                player.gameMode.getGameModeForPlayer().getName(), player.getInventory().getSelectedSlot(),
                slots, List.of(), new ArenaBlocks());
    }

    /** Turns on the rules a session needs, keeping what each of them was at. */
    private static List<String> switchRulesOn()
    {
        List<String> changed = new ArrayList<>();
        for (String rule : AutoSetupSettings.neededRules())
        {
            String wanted = AutoSetupSettings.wantedValue(rule);
            String now = AutoSetupSettings.value(rule);
            if (now == null || wanted == null || wanted.equals(now)) continue;
            if (AutoSetupSettings.set(rule, wanted)) changed.add(rule + "=" + now);
        }
        return changed;
    }

    /** A player's stack as the text the session file keeps; an empty slot is an empty string. */
    static String encode(RegistryAccess registries, ItemStack stack)
    {
        if (stack.isEmpty()) return "";
        return ItemStack.CODEC.encodeStart(ops(registries), stack)
                .getOrThrow(message -> new IllegalArgumentException("an item cannot be saved: " + message))
                .toString();
    }

    /** The stack a saved slot holds, or nothing when the slot was empty. */
    static ItemStack decode(RegistryAccess registries, String saved)
    {
        if (saved == null || saved.isEmpty()) return ItemStack.EMPTY;
        return ItemStack.CODEC.parse(ops(registries), JsonParser.parseString(saved))
                .getOrThrow(message -> new IllegalArgumentException("a saved item cannot be read: " + message));
    }

    private static RegistryOps<JsonElement> ops(RegistryAccess registries)
    {
        return registries.createSerializationContext(JsonOps.INSTANCE);
    }

    private static int totems(ServerPlayer player)
    {
        int count = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems())
        {
            if (stack.is(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        if (player.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING)) count++;
        return count;
    }

    private static ServerPlayer player(MinecraftServer server, String name)
    {
        return server.getPlayerList().getPlayerByName(name);
    }

    private ServerPlayer player()
    {
        return player(server, playerName);
    }

    private static ServerLevel level(ServerPlayer player)
    {
        return player.level() instanceof ServerLevel level ? level : null;
    }

    private static void teleport(ServerPlayer player, BlockPos pos, float yaw)
    {
        ServerLevel level = level(player);
        if (level == null) return;
        player.teleportTo(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, Set.of(), yaw, 0.0F, true);
    }
}
