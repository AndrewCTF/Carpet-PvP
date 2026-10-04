package carpet.pvp.selftest;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import carpet.patches.EntityPlayerMPFake;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /bot skin} with the profile handed in rather than looked up, which is the half of it that has
 * nothing to do with the network.
 *
 * <p>A client draws the skin of whichever account the profile the server handed it belongs to, so the
 * profile is the whole of it, and a live fake player carries one that cannot be swapped: the bot goes off
 * and comes back under its own name with the other account's id. What has to survive that is its name,
 * where it stood, what it held, how it was fighting and which faction it was in, and a self-test that
 * never goes online can only check that by handing it a made-up profile.</p>
 */
final class SkinScenarios
{
    /** The account a self-test wears: a fixed uuid, so the run is the same every time. */
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000c0de");
    private static final String ACCOUNT_NAME = "SelfSkinAccount";
    /** Ticks the bot is given to leave and come back. */
    private static final int SWAP_TIMEOUT = 60;
    /** How far off the spot it was sent to the bot may come back, in blocks. */
    private static final double SPOT_TOLERANCE = 2.0D;

    private SkinScenarios() {}

    static SelfTest.Scenario profile(String a, String b, String c, Vec3 origin)
    {
        int[] phase = {0};
        int[] waited = {SWAP_TIMEOUT};
        String[] refused = {""};
        ServerPlayer[] first = {null};
        Vec3[] stoodAt = {null};
        return new SelfTest.Scenario(300, List.of(new SelfTest.Bot(a, origin)), List.of(), server ->
        {
            ServerPlayer bot = SelfTest.player(server, a);
            if (bot == null) return SelfTest.pending(a + " has not joined yet");
            if (SelfTest.warmingUp(server, a)) return SelfTest.pending(a + " is still loading");
            if (phase[0] == 0)
            {
                phase[0] = 1;
                return SelfTest.pending(a + " is still settling after loading");
            }
            if (phase[0] == 1)
            {
                phase[0] = 2;
                return SelfTest.pending(a + " is about to be set up");
            }
            if (phase[0] == 2)
            {
                SelfTest.run(server, "bot kit give " + a + " sword");
                SelfTest.run(server, "bot option " + a + " combatstyle sword");
                SelfTest.run(server, "bot option " + a + " difficulty skilled");
                SelfTest.run(server, "bot option " + a + " combat true");
                SelfTest.run(server, "bot option " + a + " autoshield true");
                first[0] = bot;
                stoodAt[0] = bot.position();
                String problem = carpet.pvp.BotSkins.wear(server, bot, new GameProfile(ACCOUNT, ACCOUNT_NAME));
                refused[0] = problem == null ? "" : problem;
                refused[0] += "; before the swap combat=" + ((EntityPlayerMPFake) bot).getPvpConfig().combat
                        + " shield=" + ((EntityPlayerMPFake) bot).getPvpConfig().autoShield;
                phase[0] = 3;
                return SelfTest.pending("asked " + a + " to wear " + ACCOUNT_NAME
                        + (problem == null ? "" : ", refused: " + problem));
            }
            if (phase[0] == 3)
            {
                // The old bot has to leave first: the new one comes back in under the same name, so it
                // is the instance that changed that says the swap has happened, not the name.
                ServerPlayer back = server.getPlayerList().getPlayerByName(a);
                if (back == null || back == first[0])
                {
                    return waited[0]-- <= 0
                            ? new SelfTest.Probe(false, a + " never came back under the other profile")
                            : SelfTest.pending("waiting for " + a + " to come back");
                }
                phase[0] = 4;
                return SelfTest.pending(a + " came back");
            }
            ServerPlayer back = server.getPlayerList().getPlayerByName(a);
            if (back == null) return SelfTest.pending(a + " is not in the player list");
            EntityPlayerMPFake worn = (EntityPlayerMPFake) back;
            double from = worn.position().distanceTo(stoodAt[0]);
            boolean wears = worn.getUUID().equals(ACCOUNT);
            boolean sameName = worn.getGameProfile().name().equals(a);
            boolean armed = worn.getMainHandItem().is(Items.DIAMOND_SWORD);
            // What place() puts back that it does not: read so the run says either way.
            boolean fighting = worn.getPvpConfig().combat;
            boolean shielded = worn.getPvpConfig().autoShield;
            SelfTest.run(server, "bot stop " + a);
            return new SelfTest.Probe(wears && sameName && armed && from <= SPOT_TOLERANCE,
                    SelfTest.fmt("%s came back as %s under the name %s holding %s, %.2f blocks from where"
                                    + " it stood%s; of what it was set up with, fighting came back %s and shield"
                                    + " play %s",
                            a, worn.getUUID(), worn.getGameProfile().name(), worn.getMainHandItem(), from,
                            refused[0], fighting, shielded));
        });
    }
}