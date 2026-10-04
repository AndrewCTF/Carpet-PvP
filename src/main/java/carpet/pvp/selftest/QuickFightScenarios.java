package carpet.pvp.selftest;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
//~ if <26.1 'ContainerInput' -> 'ClickType' {
import net.minecraft.world.inventory.ContainerInput;
//~}
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one button of the main menu that starts a fight on its own: two bots of the default style,
 * spawned where the viewer stands and set on each other.
 *
 * <p>A bot is placed on the spot on a server without authentication and a tick or two later on one
 * that has to look a profile up, so the pairing of the two has to survive both routes or the bots
 * come up and never find each other.</p>
 */
final class QuickFightScenarios
{
    /** The slot of the button on the main menu, which is the fifth of its own row. */
    private static final int QUICK_FIGHT = 49;

    /** How long the two bots are given to close on each other and land a hit. */
    private static final int FIGHT_TICKS = 320;

    private QuickFightScenarios() {}

    static Scenario quickFight(String a, String b, String c, Vec3 origin)
    {
        Set<String> before = new TreeSet<>();
        boolean[] pressed = {false};
        Probe[] settled = {null};
        int[] waited = {0};
        return new Scenario(600, List.of(new Bot(a, origin)), List.of(), server ->
        {
            if (settled[0] != null) return settled[0];
            if (!pressed[0])
            {
                if (SelfTest.warmingUp(server, a)) return SelfTest.pending(a + " is still loading");
                // What is on the server is read on the tick of the press: a bot an earlier scenario left
                // on its way out is still in the player list for the tick after it was disconnected, and
                // it would then be taken for one of ours.
                before.addAll(bots(server));
                ServerPlayer viewer = SelfTest.player(server, a);
                if (SelfTest.result(server, "execute as " + a + " run bot gui") == 0)
                {
                    return new Probe(false, a + " could not open the menu");
                }
                if (!(viewer.containerMenu instanceof AbstractContainerMenu menu))
                {
                    return new Probe(false, "the menu of " + a + " is " + viewer.containerMenu);
                }
                click(menu, viewer, QUICK_FIGHT);
                pressed[0] = true;
                return SelfTest.pending("pressed the quick fight button of " + a);
            }
            waited[0]++;
            // Whatever the button left on the server, and nothing that was there before it.
            List<String> here = arrived(server, before);
            Probe answer = fought(server, here, waited[0]);
            if (answer != null || waited[0] > FIGHT_TICKS + 60)
            {
                settled[0] = answer != null ? answer : new Probe(false, SelfTest.fmt(
                        "the two bots never got to fight each other: %s", here));
                for (String name : here) SelfTest.run(server, SelfTest.cmd(name + " disconnect"));
            }
            if (settled[0] != null) return settled[0];
            return SelfTest.pending(SelfTest.fmt("waiting for the quick fight of %s to get going: %s", a, here));
        });
    }

    /**
     * What the two bots the button spawned are doing.
     *
     * @return null while the fight is still being set up, and the answer once it is
     */
    private static Probe fought(MinecraftServer server, List<String> here, int waited)
    {
        if (here.size() < 2)
        {
            return null;
        }
        EntityPlayerMPFake one = (EntityPlayerMPFake) SelfTest.player(server, here.get(0));
        EntityPlayerMPFake other = (EntityPlayerMPFake) SelfTest.player(server, here.get(1));
        BotPvpConfig mine = one.getPvpConfig();
        BotPvpConfig theirs = other.getPvpConfig();
        String pairing = SelfTest.fmt("%s is in faction %s and %s in faction %s, both fight %s",
                one.getName().getString(), mine.faction, other.getName().getString(), theirs.faction,
                mine.combat);
        // Both have to be on, looking for bots and far enough to have found each other.
        if (!mine.combat || !theirs.combat || !mine.targetBots || !theirs.targetBots
                || mine.targetRange < 16.0D || theirs.targetRange < 16.0D)
        {
            return null;
        }
        if (mine.faction == null || theirs.faction == null || mine.faction.equals(theirs.faction))
        {
            return new Probe(false, "the two bots are not paired with each other: " + pairing);
        }
        // A hit can only land once both are out of the window a fake player cannot be hurt in.
        if (SelfTest.warmingUp(server, one.getName().getString(), other.getName().getString()))
        {
            return null;
        }
        int hits = hits(one) + hits(other);
        if (hits == 0)
        {
            return waited < FIGHT_TICKS ? null : new Probe(false, SelfTest.fmt(
                    "neither bot landed a hit in %d ticks of a fight at %.1f blocks", FIGHT_TICKS,
                    one.distanceTo(other)));
        }
        return new Probe(true, SelfTest.fmt(
                "the button spawned %s and %s, %s, and one of them landed %d hits in %d ticks",
                one.getName().getString(), other.getName().getString(), pairing, hits, waited));
    }

    /** The bots on the server that were not there before the button was pressed, by name. */
    private static List<String> arrived(MinecraftServer server, Set<String> before)
    {
        return bots(server).stream().filter(name -> !before.contains(name)).sorted().toList();
    }

    /** A click, the way the server handles the packet a client sends for it. */
//~ if <26.1 'ContainerInput' -> 'ClickType' {
    private static void click(AbstractContainerMenu menu, Player viewer, int slot)
    {
        menu.suppressRemoteUpdates();
        menu.clicked(slot, 0, ContainerInput.PICKUP, viewer);
        menu.resumeRemoteUpdates();
        menu.broadcastChanges();
    }
//~}

    /** The bots on the server, which is every fake player in the player list. */
    private static Set<String> bots(MinecraftServer server)
    {
        Set<String> names = new TreeSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (player instanceof EntityPlayerMPFake) names.add(player.getName().getString());
        }
        return names;
    }

    /** How many hits the bot's body has landed, or 0 while it has not started fighting yet. */
    private static int hits(EntityPlayerMPFake bot)
    {
        BotBody body = bot.getBotBrain() == null ? null : bot.getBotBrain().body();
        return body == null || body.stats() == null ? 0 : body.stats().hits;
    }
}
