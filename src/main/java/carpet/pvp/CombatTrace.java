package carpet.pvp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What happened in one bot fight, event by event: every hit and crit, every swing that hit nothing,
 * every hit the bot took and every item it used, each with the tick it happened on.
 *
 * <p>The trace is recorded next to the counters of {@link BotStats}, so the numbers a trace gives are
 * the same numbers the stats do: the summary at the top of the file is counted from the events, not
 * kept beside them. A trace is only written when a fight ends, which is when {@link CombatTraces}
 * rotates it.</p>
 *
 * <p>No Minecraft types, so the format is unit testable on its own.</p>
 */
public final class CombatTrace
{
    /** How many events one fight keeps before the oldest ones fall off, so a long fight cannot eat all memory. */
    public static final int MAX_EVENTS = 4096;

    public enum Kind
    {
        /** A click that passed the reach and aim checks and landed. */
        HIT,
        /** A click that was swung at nothing, or at something out of reach. */
        MISS,
        /** Health the bot lost. */
        TAKEN,
        /** An item the bot put to use, named by its item id. */
        ITEM
    }

    /**
     * One thing that happened.
     *
     * @param tick   the game tick it happened on
     * @param other  the other fighter's name, empty when there was none to name
     * @param item   the item id involved, empty when the event had no item
     * @param damage health points, for a hit what it cost the target, for a hit taken what it cost the bot
     * @param crit   whether the hit was a crit; always false for the other kinds
     */
    public record Event(long tick, Kind kind, String other, String item, double damage, boolean crit)
    {
    }

    private final String bot;
    private final String mode;
    private final long startedAt;
    private final List<Event> events = new ArrayList<>();
    private long dropped;
    private long lastTick;

    public CombatTrace(String bot, String mode, long startedAt)
    {
        this.bot = bot;
        this.mode = mode;
        this.startedAt = startedAt;
        this.lastTick = startedAt;
    }

    public String bot()
    {
        return bot;
    }

    /** The combat style the bot was fighting with. */
    public String mode()
    {
        return mode;
    }

    /** The game tick the fight started on. */
    public long startedAt()
    {
        return startedAt;
    }

    /** The game tick of the newest event. */
    public long lastTick()
    {
        return lastTick;
    }

    /** How long the fight took, in ticks. */
    public long ticks()
    {
        return Math.max(0L, lastTick - startedAt);
    }

    public List<Event> events()
    {
        return List.copyOf(events);
    }

    /** How many events were dropped because the fight went over {@link #MAX_EVENTS}. */
    public long dropped()
    {
        return dropped;
    }

    public int size()
    {
        return events.size();
    }

    public void add(Event event)
    {
        events.add(event);
        while (events.size() > MAX_EVENTS)
        {
            events.remove(0);
            dropped++;
        }
        if (event.tick() > lastTick)
        {
            lastTick = event.tick();
        }
    }

    /** How many events of one kind the fight holds. */
    public int count(Kind kind)
    {
        int count = 0;
        for (Event event : events)
        {
            if (event.kind() == kind)
            {
                count++;
            }
        }
        return count;
    }

    /** The health points the bot dealt, which is what the damage of every landed hit adds up to. */
    public double damageDealt()
    {
        return damage(true);
    }

    /** The health points the bot took. */
    public double damageTaken()
    {
        return damage(false);
    }

    private double damage(boolean dealt)
    {
        Kind kind = dealt ? Kind.HIT : Kind.TAKEN;
        double total = 0.0;
        for (Event event : events)
        {
            if (event.kind() == kind)
            {
                total += event.damage();
            }
        }
        return total;
    }

    /** How many landed hits were crits. */
    public int crits()
    {
        int crits = 0;
        for (Event event : events)
        {
            if (event.kind() == Kind.HIT && event.crit())
            {
                crits++;
            }
        }
        return crits;
    }

    /** The short form {@code /bot trace} prints. */
    public String describe()
    {
        return String.format(Locale.ROOT,
                "%d ticks, %d hits (%d crits), %d misses, %d hits taken, %.1f dealt, %.1f taken, %d item uses",
                ticks(), count(Kind.HIT), crits(), count(Kind.MISS), count(Kind.TAKEN),
                damageDealt(), damageTaken(), count(Kind.ITEM));
    }

    /**
     * The file a finished fight is written as: a summary of the counters a trace keeps, and every
     * event behind it. The summary is counted from the events so the two cannot drift apart.
     */
    public String toJson()
    {
        JsonArray list = new JsonArray();
        for (Event event : events)
        {
            JsonObject one = new JsonObject();
            one.addProperty("tick", event.tick());
            one.addProperty("kind", event.kind().name());
            one.addProperty("other", event.other());
            one.addProperty("item", event.item());
            one.addProperty("damage", event.damage());
            one.addProperty("crit", event.crit());
            list.add(one);
        }
        JsonObject root = new JsonObject();
        root.addProperty("bot", bot);
        root.addProperty("mode", mode);
        root.addProperty("startedAt", startedAt);
        root.addProperty("ticks", ticks());
        root.addProperty("hits", count(Kind.HIT));
        root.addProperty("crits", crits());
        root.addProperty("misses", count(Kind.MISS));
        root.addProperty("hitsTaken", count(Kind.TAKEN));
        root.addProperty("itemUses", count(Kind.ITEM));
        root.addProperty("damageDealt", damageDealt());
        root.addProperty("damageTaken", damageTaken());
        root.addProperty("droppedEvents", dropped);
        root.add("events", list);
        return root.toString();
    }

    /** Reads a trace back from what {@link #toJson()} wrote. */
    public static CombatTrace parse(String json)
    {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject())
        {
            throw new IllegalArgumentException("a trace is a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        CombatTrace trace = new CombatTrace(text(object, "bot"), text(object, "mode"),
                object.has("startedAt") ? object.get("startedAt").getAsLong() : 0L);
        trace.dropped = object.has("droppedEvents") ? object.get("droppedEvents").getAsLong() : 0L;
        JsonElement list = object.get("events");
        if (list != null && list.isJsonArray())
        {
            for (JsonElement element : list.getAsJsonArray())
            {
                JsonObject one = element.getAsJsonObject();
                // read straight into the list: the file was written from a trace that was already capped
                trace.events.add(new Event(one.get("tick").getAsLong(),
                        Kind.valueOf(one.get("kind").getAsString()),
                        text(one, "other"), text(one, "item"),
                        one.has("damage") ? one.get("damage").getAsDouble() : 0.0,
                        one.has("crit") && one.get("crit").getAsBoolean()));
            }
            if (!trace.events.isEmpty())
            {
                trace.lastTick = trace.events.get(trace.events.size() - 1).tick();
            }
        }
        return trace;
    }

    public static CombatTrace read(Path file) throws IOException
    {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public void writeTo(Path file) throws IOException
    {
        if (file.getParent() != null)
        {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, toJson(), StandardCharsets.UTF_8);
    }

    private static String text(JsonObject object, String key)
    {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    @Override
    public String toString()
    {
        return "CombatTrace[" + bot + " " + mode + " " + describe() + "]";
    }
}