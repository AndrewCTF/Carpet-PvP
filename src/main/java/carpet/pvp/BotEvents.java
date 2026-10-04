package carpet.pvp;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the game tells a bot about its own fight, for the program layer to react to.
 *
 * <p>A death and a totem popping are told once, by the game itself, from the hooks in
 * {@code LivingEntity_deathEventMixin} and {@code LivingEntity_totemEventMixin}. They are pushed here and
 * drained once per tick by whoever is driving the bot, so a program sees each of them exactly once and
 * never has to watch for them.</p>
 */
public final class BotEvents
{
    /** The things a bot is told about, that a program may react to. */
    public enum Event
    {
        /** The entity the bot was fighting died. */
        KILL,
        /** A totem of the bot's own popped. */
        TOTEM_POP
    }

    private static final Map<UUID, Set<Event>> PENDING = new HashMap<>();

    private BotEvents() {}

    /**
     * The death hook: every bot that was fighting this entity is told its target died.
     *
     * @param level the level the entity died in, whose server holds the bots
     */
    public static void targetDied(LivingEntity dead, ServerLevel level)
    {
        MinecraftServer server = level.getServer();
        if (server == null)
        {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (!(player instanceof EntityPlayerMPFake bot)) continue;
            LivingEntity target = bot.getBotBrain().target();
            if (target != null && target.getUUID().equals(dead.getUUID()))
            {
                push(bot, Event.KILL);
            }
        }
    }

    /** The totem hook: the bot that used its own totem is told about it. */
    public static void totemPopped(EntityPlayerMPFake bot)
    {
        push(bot, Event.TOTEM_POP);
    }

    /**
     * The events the game has reported since the last call, in no particular order, and forgets them. An event
     * that came before the last drain is not seen again.
     */
    public static Set<Event> drain(EntityPlayerMPFake bot)
    {
        Set<Event> pending = PENDING.remove(bot.getUUID());
        return pending == null ? Set.of() : pending;
    }

    private static void push(EntityPlayerMPFake bot, Event event)
    {
        PENDING.computeIfAbsent(bot.getUUID(), key -> EnumSet.noneOf(Event.class)).add(event);
    }
}