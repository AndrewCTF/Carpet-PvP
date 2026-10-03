package carpet.pvp;

import carpet.pvp.CombatTrace.Event;
import carpet.pvp.CombatTrace.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatTraceTest
{
    @Test
    void theCountersAreCountedFromTheEvents()
    {
        CombatTrace trace = fight();
        assertEquals(3, trace.count(Kind.HIT));
        assertEquals(2, trace.count(Kind.MISS));
        assertEquals(2, trace.count(Kind.TAKEN));
        assertEquals(1, trace.count(Kind.ITEM));
        assertEquals(2, trace.crits());
        assertEquals(11.5D, trace.damageDealt(), 1.0E-9);
        assertEquals(4.5D, trace.damageTaken(), 1.0E-9);
    }

    @Test
    void everyEventKeepsItsTick()
    {
        CombatTrace trace = fight();
        assertEquals(1000L, trace.startedAt());
        assertEquals(1040L, trace.lastTick());
        assertEquals(40L, trace.ticks());
        for (Event event : trace.events())
        {
            assertTrue(event.tick() >= trace.startedAt() && event.tick() <= trace.lastTick());
        }
    }

    @Test
    void aTraceReadsBackAsWhatWasWritten()
    {
        CombatTrace written = fight();
        CombatTrace read = CombatTrace.parse(written.toJson());

        assertEquals("Bot1", read.bot());
        assertEquals("MELEE", read.mode());
        assertEquals(written.startedAt(), read.startedAt());
        assertEquals(written.ticks(), read.ticks());
        assertEquals(written.events(), read.events());
        assertEquals(written.damageDealt(), read.damageDealt(), 1.0E-9);
        assertEquals(written.damageTaken(), read.damageTaken(), 1.0E-9);
    }

    @Test
    void theFileHoldsTheSummaryTheStatsHold()
    {
        // The number the file reads back as the hits has to be the number of hit events, which is what
        // makes the trace and the bot's own counters the same fight.
        String json = fight().toJson();
        CombatTrace read = CombatTrace.parse(json);
        assertEquals(read.count(Kind.HIT), read.events().stream().filter(e -> e.kind() == Kind.HIT).count());
        assertTrue(json.contains("\"hits\":" + read.count(Kind.HIT)));
        assertTrue(json.contains("\"crits\":" + read.crits()));
        assertTrue(json.contains("\"misses\":" + read.count(Kind.MISS)));
    }

    @Test
    void aTraceIsWrittenUnderTheWorldFolderAndReadBack(@TempDir Path world) throws Exception
    {
        CombatTrace trace = fight();
        Path file = world.resolve("carpet-traces").resolve("Bot1.json");
        trace.writeTo(file);

        assertTrue(Files.isRegularFile(file));
        assertEquals(trace.events(), CombatTrace.read(file).events());
    }

    @Test
    void anEmptyFightHoldsNothingAndStillReadsBack()
    {
        CombatTrace empty = new CombatTrace("Bot2", "MACE", 500L);
        assertEquals(0, empty.size());
        assertEquals(0L, empty.ticks());
        assertEquals(0.0D, empty.damageDealt(), 1.0E-9);

        CombatTrace read = CombatTrace.parse(empty.toJson());
        assertEquals("Bot2", read.bot());
        assertEquals("MACE", read.mode());
        assertEquals(500L, read.startedAt());
        assertEquals(List.of(), read.events());
        assertEquals(0, read.count(Kind.HIT));
        assertFalse(empty.describe().isEmpty());
    }

    @Test
    void aLongFightDropsTheOldestEventsRatherThanGrowWithoutEnd()
    {
        CombatTrace trace = new CombatTrace("Bot3", "SMP", 0L);
        for (int i = 0; i < CombatTrace.MAX_EVENTS + 50; i++)
        {
            trace.add(new Event(i, Kind.MISS, "Bot4", "minecraft:diamond_sword", 0.0, false));
        }

        assertEquals(CombatTrace.MAX_EVENTS, trace.size());
        assertEquals(50L, trace.dropped());
        assertEquals(50L, trace.events().get(0).tick());
        assertEquals(CombatTrace.MAX_EVENTS + 49L, trace.lastTick());
    }

    @Test
    void somethingThatIsNotATraceIsRefused()
    {
        assertThrows(IllegalArgumentException.class, () -> CombatTrace.parse("[]"));
    }

    @Test
    void theShortFormSaysWhatTheFightWasMadeOf()
    {
        String described = fight().describe();
        assertTrue(described.contains("40 ticks"), described);
        assertTrue(described.contains("3 hits"), described);
        assertTrue(described.contains("2 crits"), described);
        assertTrue(described.contains("2 misses"), described);
        assertFalse(described.isEmpty());
    }

    private static CombatTrace fight()
    {
        CombatTrace trace = new CombatTrace("Bot1", "MELEE", 1000L);
        trace.add(new Event(1000L, Kind.HIT, "Bot2", "minecraft:diamond_sword", 4.0, false));
        trace.add(new Event(1005L, Kind.HIT, "Bot2", "minecraft:diamond_sword", 6.5, true));
        trace.add(new Event(1010L, Kind.MISS, "Bot2", "minecraft:diamond_sword", 0.0, false));
        trace.add(new Event(1012L, Kind.TAKEN, "Bot2", "", 2.5, false));
        trace.add(new Event(1020L, Kind.HIT, "Bot3", "minecraft:diamond_sword", 1.0, true));
        trace.add(new Event(1024L, Kind.MISS, "Bot3", "minecraft:diamond_sword", 0.0, false));
        trace.add(new Event(1030L, Kind.ITEM, "", "minecraft:shield", 0.0, false));
        trace.add(new Event(1040L, Kind.TAKEN, "Bot3", "", 2.0, false));
        return trace;
    }
}