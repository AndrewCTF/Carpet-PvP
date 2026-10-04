package carpet.logic.program;

import carpet.pvp.BotEvents;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The combat nodes: the AI they turn on and off, the body they keep the program away from, what they read
 * while they wait, and what the game tells them.
 */
class CombatExecutorTest
{
    private final ActionSchema schema = ActionSchema.load();
    private final RecordingBot recorder = new RecordingBot();
    private final ProgramExecutor executor = new ProgramExecutor(schema, name -> "bot".equals(name) ? recorder.bot : null, () -> 4);
    private final List<String> warnings = new ArrayList<>();

    private void run(String json)
    {
        BotProgram program = new BotProgram("test", "test", "");
        program.setActions(new Gson().fromJson(json, new TypeToken<List<BotAction>>() {}.getType()));
        schema.validate(program.getActions());
        executor.setLogListener((level, message) ->
        {
            if ("WARN".equals(level))
            {
                warnings.add(message);
            }
        });
        assertNull(executor.startProgram("bot", program, null));
    }

    private void tick(int times)
    {
        for (int i = 0; i < times; i++)
        {
            executor.tick();
        }
    }

    private String status()
    {
        return executor.getPrograms().get("bot").status();
    }

    private String error()
    {
        return executor.getPrograms().get("bot").error();
    }

    @Test
    void combatStartTurnsTheAiOnWithThoseSettings()
    {
        run("[{type: COMBAT_START, params: {style: mace, difficulty: expert, targets: bots, target: Steve}}, {type: HOTBAR, params: {slot: 2}}]");
        tick(1);
        // a program that reaches its end leaves the bot the way it found it: not fighting
        assertEquals(List.of("startCombat[mace, expert, bots, Steve]", "selectHotbar[2]", "stopCombat[]", "stopAll[]"),
                recorder.calls);
        assertEquals(List.of(), warnings, "nothing of the program drove the body");
    }

    @Test
    void combatStopReleasesWhatTheStyleLeftRunning()
    {
        run("[{type: COMBAT_START}, {type: COMBAT_STOP}, {type: DISMOUNT}]");
        tick(1);
        assertEquals(List.of("startCombat[sword, average, players, ]", "stopCombat[]", "dismount[]", "stopAll[]"),
                recorder.calls);
    }

    @Test
    void anOptionIsPassedOnAsItIs()
    {
        run("[{type: SET_COMBAT_OPTION, params: {key: difficulty, value: skilled}}, {type: DISMOUNT}]");
        tick(1);
        assertEquals(List.of("combatOption[difficulty, skilled]", "dismount[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void aStopWithNoFightOpenTurnsTheAiOffAnyway()
    {
        run("[{type: COMBAT_STOP}, {type: DISMOUNT}]");
        tick(1);
        assertEquals(List.of("stopCombat[]", "dismount[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void aStepTheFightOwnsIsNotEvenAskedForItsRule()
    {
        recorder.failures.put("requireRule", new BotActionException("The carpet rule 'fakePlayerNavigation' is off"));
        run("[{type: COMBAT_START}, {type: NAV_GOTO}, {type: COMBAT_STOP}, {type: NAV_GOTO}, {type: DISMOUNT}]");
        tick(3);
        // the first nav goto belongs to the fight, so the rule is not asked for; the second one does
        assertEquals(List.of("startCombat[sword, average, players, ]", "stopCombat[]", "stopAll[]"), recorder.calls);
        assertEquals("ERROR", status());
        assertEquals("The carpet rule 'fakePlayerNavigation' is off", error());
    }

    @Test
    void anUnusableOptionStopsTheProgramWithTheReasonTheCommandGives()
    {
        recorder.failures.put("combatOption", new BotActionException("Unknown setting: nosuch"));
        run("[{type: SPRINT}, {type: SET_COMBAT_OPTION, params: {key: nosuch, value: \"1\"}}, {type: JUMP}]");
        tick(2);
        assertEquals(List.of("setSprinting[true]", "stopAll[]"), recorder.calls);
        assertEquals("ERROR", status());
        assertEquals("Unknown setting: nosuch", error());
    }

    @Test
    void aKitIsGivenByName()
    {
        run("[{type: GIVE_KIT, params: {kit: sword}}, {type: DISMOUNT}]");
        tick(1);
        assertEquals(List.of("giveKit[sword]", "dismount[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void aKitThatDoesNotExistStopsTheProgram()
    {
        recorder.failures.put("giveKit", new BotActionException("There is no kit called nosuch. Kits: sword"));
        run("[{type: GIVE_KIT, params: {kit: nosuch}}, {type: DISMOUNT}]");
        tick(1);
        assertEquals(List.of("stopAll[]"), recorder.calls);
        assertEquals("ERROR", status());
        assertEquals("There is no kit called nosuch. Kits: sword", error());
    }

    @Test
    void whileACombatNodeIsActiveTheBrainOwnsTheBody()
    {
        run("""
                [{type: COMBAT_START},
                 {type: MOVE, params: {ticks: 3}},
                 {type: ATTACK},
                 {type: EQUIP_ARMOR, params: {armorSet: iron}},
                 {type: COMBAT_STOP},
                 {type: MOVE, params: {ticks: 3}},
                 {type: DISMOUNT}]""");
        tick(4);
        assertEquals(List.of("startCombat[sword, average, players, ]", "equipArmor[iron]", "stopCombat[]",
                "move[1.0, 0.0]", "stopMoving[]", "dismount[]", "stopAll[]"), recorder.calls);
        assertEquals(1, warnings.size(), "the author is told once: " + warnings);
        assertTrue(warnings.get(0).contains("MOVE"), warnings.toString());
    }

    @Test
    void aFightingBotKeepsItsBodyUntilTheNodeEnds()
    {
        recorder.answers.put("isAlive", true);
        recorder.answers.put("hasTarget", true);
        run("[{type: FIGHT, params: {timeout: 500}}, {type: JUMP}]");
        tick(20);
        assertEquals(List.of("startCombat[sword, average, players, ]"), recorder.calls);
        assertEquals("RUNNING", status());
    }

    @Test
    void aFightWithNoTargetIsOverBeforeItBegins()
    {
        run("[{type: FIGHT, params: {timeout: 500}}, {type: DISMOUNT}]");
        tick(2);
        assertEquals(List.of("startCombat[sword, average, players, ]", "stopCombat[]", "dismount[]", "stopAll[]"),
                recorder.calls);
        assertEquals("COMPLETED", status());
    }

    @Test
    void aFightEndsWhenTheTargetIsGoneAndTheNextNodeRuns()
    {
        recorder.answers.put("isAlive", true);
        recorder.answers.put("hasTarget", true);
        run("[{type: FIGHT, params: {timeout: 500}}, {type: DISMOUNT}]");
        tick(3);
        assertEquals(List.of("startCombat[sword, average, players, ]"), recorder.calls);
        recorder.answers.put("hasTarget", false);
        tick(1);
        assertEquals(List.of("startCombat[sword, average, players, ]", "stopCombat[]", "dismount[]", "stopAll[]"),
                recorder.calls);
        assertEquals("COMPLETED", status());
    }

    @Test
    void aFightEndsWhenTheBotDies()
    {
        recorder.answers.put("hasTarget", true);
        recorder.answers.put("isAlive", false);
        run("[{type: FIGHT, params: {timeout: 500}}, {type: DISMOUNT}]");
        tick(2);
        assertEquals(List.of("startCombat[sword, average, players, ]", "stopCombat[]", "dismount[]", "stopAll[]"),
                recorder.calls);
    }

    @Test
    void aFightEndsWhenTheTargetStaysOutOfRange()
    {
        recorder.answers.put("isAlive", true);
        recorder.answers.put("hasTarget", true);
        recorder.answers.put("targetDistance", 20.0);
        run("[{type: FIGHT, params: {timeout: 500, range: 4, rangeTicks: 3}}, {type: DISMOUNT}]");
        tick(4);
        assertEquals(List.of("startCombat[sword, average, players, ]", "stopCombat[]", "dismount[]", "stopAll[]"),
                recorder.calls);
    }

    @Test
    void aTargetThatKeepsItsDistanceKeepsTheFightGoing()
    {
        recorder.answers.put("isAlive", true);
        recorder.answers.put("hasTarget", true);
        recorder.answers.put("targetDistance", 2.0);
        run("[{type: FIGHT, params: {timeout: 500, range: 4, rangeTicks: 3}}, {type: DISMOUNT}]");
        tick(10);
        assertEquals(List.of("startCombat[sword, average, players, ]"), recorder.calls);
        assertEquals("RUNNING", status());
    }

    @Test
    void aFightEndsAtItsTimeout()
    {
        recorder.answers.put("isAlive", true);
        recorder.answers.put("hasTarget", true);
        recorder.answers.put("targetDistance", 2.0);
        run("[{type: FIGHT, params: {timeout: 4}}, {type: DISMOUNT}]");
        tick(5);
        assertEquals(List.of("startCombat[sword, average, players, ]", "stopCombat[]", "dismount[]", "stopAll[]"),
                recorder.calls);
    }

    @Test
    void stoppingAProgramMidFightLeavesTheBotNotFighting()
    {
        recorder.answers.put("isAlive", true);
        recorder.answers.put("hasTarget", true);
        run("[{type: COMBAT_START}, {type: LOOP, params: {count: 5}, children: [{type: DELAY, params: {ticks: 4}}]}]");
        tick(2);
        assertEquals(List.of("startCombat[sword, average, players, ]"), recorder.calls);

        assertTrue(executor.stopProgram("bot"));
        assertEquals(List.of("startCombat[sword, average, players, ]", "stopCombat[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void aProgramThatNeverTurnsTheAiOnLeavesItAlone()
    {
        run("[{type: DISMOUNT}]");
        tick(1);
        assertEquals(List.of("dismount[]", "stopAll[]"), recorder.calls);
        assertEquals(0, recorder.count("stopCombat"));
    }

    @Test
    void theFightConditionsReadTheBotsTarget()
    {
        recorder.answers.put("isFighting", true);
        recorder.answers.put("hasTarget", true);
        recorder.answers.put("targetDistance", 2.5);
        recorder.answers.put("targetHealth", 7.0);
        run("""
                [{type: IF_THEN_ELSE, condition: {type: CONDITION_IS_FIGHTING}, children: [{type: DISMOUNT}]},
                 {type: IF_THEN_ELSE, condition: {type: CONDITION_HAS_TARGET}, children: [{type: DISMOUNT}]},
                 {type: IF_THEN_ELSE, condition: {type: CONDITION_TARGET_DISTANCE, params: {operator: "<", value: 3}},
                  children: [{type: DISMOUNT}]},
                 {type: IF_THEN_ELSE, condition: {type: CONDITION_TARGET_HEALTH, params: {operator: "<", value: 8}},
                  children: [{type: DISMOUNT}]}]""");
        tick(1);
        assertEquals(List.of("dismount[]", "dismount[]", "dismount[]", "dismount[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void aTargetConditionIsFalseWhileThereIsNoTarget()
    {
        recorder.answers.put("isFighting", false);
        recorder.answers.put("hasTarget", false);
        recorder.answers.put("targetDistance", Double.POSITIVE_INFINITY);
        recorder.answers.put("targetHealth", Double.POSITIVE_INFINITY);
        run("""
                [{type: IF_THEN_ELSE, condition: {type: CONDITION_IS_FIGHTING}, children: [{type: JUMP}],
                  elseChildren: [{type: DISMOUNT}]},
                 {type: IF_THEN_ELSE, condition: {type: CONDITION_HAS_TARGET}, children: [{type: JUMP}],
                  elseChildren: [{type: DISMOUNT}]},
                 {type: IF_THEN_ELSE, condition: {type: CONDITION_TARGET_DISTANCE, params: {operator: "<", value: 3}},
                  children: [{type: JUMP}], elseChildren: [{type: DISMOUNT}]},
                 {type: IF_THEN_ELSE, condition: {type: CONDITION_TARGET_HEALTH, params: {operator: "<", value: 8}},
                  children: [{type: JUMP}], elseChildren: [{type: DISMOUNT}]}]""");
        tick(1);
        // the distance and the health of a target that is not there are infinite, as the distance of a player
        // that is not there is, so "<" is false and the else branch runs
        assertEquals(List.of("dismount[]", "dismount[]", "dismount[]", "dismount[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void aKillTheGameReportedRunsItsHandler()
    {
        run("""
                [{type: ON_EVENT, params: {event: when_kill}, children: [{type: DISMOUNT}]},
                 {type: LOOP, params: {count: 6}, children: [{type: DELAY, params: {ticks: 2}}]}]""");
        tick(2);
        assertEquals(0, recorder.count("dismount"), "nothing has died yet");
        recorder.answers.put("combatEvents", EnumSet.of(BotEvents.Event.KILL));
        tick(1);
        assertEquals(1, recorder.count("dismount"));
        tick(10);
        assertEquals(1, recorder.count("dismount"), "the same kill is not seen twice");
    }

    @Test
    void aTotemThatPoppedRunsItsHandler()
    {
        run("""
                [{type: ON_EVENT, params: {event: when_totem_pop}, children: [{type: SNEAK}]},
                 {type: LOOP, params: {count: 6}, children: [{type: DELAY, params: {ticks: 2}}]}]""");
        tick(2);
        assertEquals(0, recorder.count("setSneaking"));
        recorder.answers.put("combatEvents", Set.of(BotEvents.Event.TOTEM_POP));
        tick(1);
        assertEquals(1, recorder.count("setSneaking"));
    }

    @Test
    void aTargetComingIntoItsHandsRunsItsHandler()
    {
        run("""
                [{type: ON_EVENT, params: {event: when_target_acquired}, children: [{type: SWAP_HANDS}]},
                 {type: LOOP, params: {count: 6}, children: [{type: DELAY, params: {ticks: 2}}]}]""");
        tick(2);
        assertEquals(0, recorder.count("swapHands"), "it has no target yet");
        recorder.answers.put("hasTarget", true);
        tick(1);
        assertEquals(1, recorder.count("swapHands"));
        tick(10);
        assertEquals(1, recorder.count("swapHands"), "the edge only, not every tick it holds");
    }
}