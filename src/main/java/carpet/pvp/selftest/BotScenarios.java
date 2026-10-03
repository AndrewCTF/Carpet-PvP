package carpet.pvp.selftest;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** The /bot command itself: what a spawned bot is given and how it is configured. */
final class BotScenarios
{
    private BotScenarios() {}

    /** /bot spawn gives the bot the kit of its style, turns its combat on and applies the difficulty. */
    static Scenario spawnKit(String a, String b, String c, Vec3 origin)
    {
        return new Scenario(200, List.of(), List.of("bot spawn " + a + " mace expert at " + SelfTest.coords(origin)), server ->
        {
            ServerPlayer player = SelfTest.player(server, a);
            if (!(player instanceof EntityPlayerMPFake bot)) return SelfTest.pending(a + " has not joined yet");
            BotPvpConfig cfg = bot.getPvpConfig();
            boolean refused = SelfTest.result(server, "bot option " + a + " nosuch.option 1") == 0;
            boolean sword = SelfTest.result(server, "bot option " + a + " combatstyle sword") == 1
                    && cfg.combatStyle == BotPvpConfig.CombatStyle.MELEE;
            boolean ok = bot.getMainHandItem().is(Items.MACE) && cfg.combat && cfg.difficulty == BotPvpConfig.Difficulty.EXPERT
                    && refused && sword;
            String detail = SelfTest.fmt("%s holds %s, combat %s, difficulty %s; an unknown option was %s; the style name sword gave %s",
                    a, bot.getMainHandItem().getItem(), cfg.combat, cfg.difficulty, refused ? "refused" : "accepted", cfg.combatStyle);
            // the command spawned this bot, so the runner does not know to remove it
            SelfTest.run(server, "player " + a + " disconnect");
            return new Probe(ok, detail);
        });
    }
}
