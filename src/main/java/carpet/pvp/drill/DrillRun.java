package carpet.pvp.drill;

import carpet.pvp.BotPvpConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * One run of a drill: the player practising, the bot in front of them and everything the drill has
 * counted so far.
 *
 * <p>The counters are the ones every drill measures: the clicks the player made, the hits they landed
 * on the bot, the attempts the drill set them and how many of those went right. What else a drill
 * needs to keep - when it threw its pearl, whether the shield was down - is its own, in {@link #state}.</p>
 */
public final class DrillRun
{
    private final MinecraftServer server;
    private final ServerPlayer player;
    private final Drill drill;
    private final String botName;
    private final BotPvpConfig.CombatStyle style;
    private int ticks;
    private DrillBot bot;
    private int swings;
    private int hits;
    private int attempts;
    private int successes;
    /** Ticks summed over every timing the drill took, and how many of them there were. */
    private int elapsed;
    private int samples;
    private float previousCharge;
    private float botHealth;
    private Object state;

    public DrillRun(MinecraftServer server, ServerPlayer player, Drill drill, String botName)
    {
        this.server = server;
        this.player = player;
        this.drill = drill;
        this.botName = botName;
        this.style = drill.style();
        this.previousCharge = player.getAttackStrengthScale(0.5F);
        this.botHealth = 20.0F;
    }

    public MinecraftServer server()
    {
        return server;
    }

    public ServerPlayer player()
    {
        return player;
    }

    public Drill drill()
    {
        return drill;
    }

    /** The name the bot of this drill is spawned under. */
    public String botName()
    {
        return botName;
    }

    public BotPvpConfig.CombatStyle style()
    {
        return style;
    }

    public int ticks()
    {
        return ticks;
    }

    /** The bot of this drill, or null while it has not joined yet. */
    public DrillBot bot()
    {
        if (bot == null)
        {
            bot = Drills.botOf(server, botName);
            if (bot != null)
            {
                botHealth = bot.bot().getHealth();
            }
        }
        return bot;
    }

    /** Whether the drill has measured everything it was for. */
    public boolean over()
    {
        return drill.done(this) || ticks >= drill.limit();
    }

    /** What the drill keeps for itself, whatever shape that is. */
    public Object state()
    {
        return state;
    }

    public void state(Object state)
    {
        this.state = state;
    }

    /** The player's clicks, which a reset of their attack strength scale gives away. */
    public int swings()
    {
        return swings;
    }

    /** How many hits the player landed on the bot of this drill. */
    public int hits()
    {
        return hits;
    }

    public int attempts()
    {
        return attempts;
    }

    public int successes()
    {
        return successes;
    }

    /** The mean of the timings the drill took, or -1 when it has not taken any. */
    public int meanTime()
    {
        return samples == 0 ? -1 : elapsed / samples;
    }

    /** Books a timing the drill took, such as how long a re-totem took. */
    public void timed(int ticks)
    {
        elapsed += ticks;
        samples++;
    }

    /**
     * Counts a tick: the clicks the player made and the damage the bot took. Called once per tick
     * before the drill's own tick.
     */
    public void count()
    {
        ticks++;
        float charge = player.getAttackStrengthScale(0.5F);
        if (charge + 0.5F < previousCharge)
        {
            swings++;
        }
        previousCharge = charge;
        DrillBot current = bot();
        if (current != null)
        {
            float health = current.bot().getHealth();
            if (health < botHealth)
            {
                hits++;
            }
            botHealth = health;
        }
    }

    /** Books an attempt the drill set that the player got right. */
    public void succeed()
    {
        attempts++;
        successes++;
    }

    /** Books an attempt the drill set that did not come off. */
    public void attempt()
    {
        attempts++;
    }

    /** Whether the player holds something that can hit the bot of this drill. */
    public static boolean holdsWeapon(ServerPlayer player)
    {
        return isWeapon(player.getMainHandItem());
    }

    /** Whether an item is one of the melee weapons a drill is fought with. */
    public static boolean isWeapon(ItemStack stack)
    {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(Items.MACE);
    }

    /** How many of an item the player is carrying, offhand and inventory together. */
    public static int count(ServerPlayer player, Item item)
    {
        int count = 0;
        if (player.getItemBySlot(EquipmentSlot.OFFHAND).is(item)) count++;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
        {
            if (player.getInventory().getItem(slot).is(item)) count++;
        }
        return count;
    }

    /** Whether the player is carrying one of these anywhere in their inventory. */
    public static boolean carries(ServerPlayer player, Item item)
    {
        return count(player, item) > 0;
    }

    /** Whether the player is carrying anything of a kind, such as any axe at all. */
    public static boolean carriesKind(ServerPlayer player, TagKey<Item> kind)
    {
        return countKind(player, kind) > 0;
    }

    private static int countKind(ServerPlayer player, TagKey<Item> kind)
    {
        int count = 0;
        if (player.getItemBySlot(EquipmentSlot.OFFHAND).is(kind)) count++;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
        {
            if (player.getInventory().getItem(slot).is(kind)) count++;
        }
        return count;
    }

    /** Puts an item in the first free hotbar slot of the drill bot and returns the slot, or -1. */
    public static int give(DrillBot bot, ItemStack stack)
    {
        for (int slot = 0; slot < 9; slot++)
        {
            if (bot.bot().getInventory().getItem(slot).isEmpty())
            {
                bot.bot().getInventory().setItem(slot, stack);
                bot.bot().getInventory().setChanged();
                return slot;
            }
        }
        return -1;
    }

        /** A number for a score line, rounded the way a drill reads it. */
    public static String percent(int part, int whole)
    {
        return whole == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * part / whole);
    }
}