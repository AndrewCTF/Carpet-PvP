package carpet.logic.program;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramExecutorTest
{
    private final ActionSchema schema = ActionSchema.load();
    private final RecordingBot recorder = new RecordingBot();
    private final ProgramExecutor executor = new ProgramExecutor(schema, name -> "bot".equals(name) ? recorder.bot : null, () -> 4);

    private static final String NAV_RULE = "requireRule[fakePlayerNavigation, null]";

    private final List<String> warnings = new ArrayList<>();

    // Programs are written in Gson's lenient JSON: no quotes needed around names and plain words.
    private List<BotAction> parse(String json)
    {
        List<BotAction> actions = new Gson().fromJson(json, new TypeToken<List<BotAction>>() {}.getType());
        schema.validate(actions);
        return actions;
    }

    private void run(String json)
    {
        run(json, null);
    }

    private void run(String json, UUID owner)
    {
        BotProgram program = new BotProgram("test", "test", "");
        program.setActions(parse(json));
        executor.setLogListener((level, message) ->
        {
            if ("WARN".equals(level))
            {
                warnings.add(message);
            }
        });
        assertNull(executor.startProgram("bot", program, owner));
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

    @Test
    void moveHoldsItsDirectionForExactlyItsTicks()
    {
        run("[{type: MOVE, params: {direction: backward, ticks: 3}}, {type: JUMP}]");
        tick(1);
        assertEquals(List.of("move[-1.0, 0.0]"), recorder.calls);
        tick(2);
        assertEquals(List.of("move[-1.0, 0.0]"), recorder.calls);
        tick(1);
        assertEquals(List.of("move[-1.0, 0.0]", "stopMoving[]", "jump[]"), recorder.calls);
    }

    @Test
    void aStepWithTicksDelaysTheNextStepByThatMany()
    {
        run("[{type: JUMP, params: {ticks: 4}}, {type: DROP, params: {ticks: 2}}, {type: DELAY, params: {ticks: 5}}, {type: DISMOUNT}]");
        tick(4);
        assertEquals(List.of("jump[]"), recorder.calls);
        tick(1);
        assertEquals(List.of("jump[]", "drop[false]"), recorder.calls);
        tick(6);
        assertEquals(List.of("jump[]", "drop[false]"), recorder.calls);
        tick(1);
        assertEquals(List.of("jump[]", "drop[false]", "dismount[]", "stopAll[]"), recorder.calls);
        assertEquals("COMPLETED", status());
    }

    @Test
    void sprintAndSneakCanBeTurnedOff()
    {
        run("[{type: SPRINT}, {type: SNEAK}, {type: SPRINT, params: {enabled: false}}, {type: SNEAK, params: {enabled: false}}]");
        tick(1);
        assertEquals(List.of("setSprinting[true]", "setSneaking[true]", "setSprinting[false]", "setSneaking[false]", "stopAll[]"), recorder.calls);
    }

    @Test
    void attackAndUseHonourModeIntervalAndTicks()
    {
        run("""
                [{type: ATTACK, params: {mode: interval, interval: 4, ticks: 5}},
                 {type: USE, params: {mode: continuous, ticks: 2}},
                 {type: ATTACK},
                 {type: USE}]""");
        tick(5);
        assertEquals(List.of("attack[interval, 4, false]"), recorder.calls);
        tick(1);
        assertEquals(List.of("attack[interval, 4, false]", "stopAttack[]", "use[continuous, 10]"), recorder.calls);
        tick(4);
        assertEquals(List.of("attack[interval, 4, false]", "stopAttack[]", "use[continuous, 10]", "stopUse[]",
                "attack[once, 10, false]", "use[once, 10]", "stopAll[]"), recorder.calls);
    }

    @Test
    void criticalAttackAndBlocksEndAfterTheirTicks()
    {
        run("[{type: ATTACK_CRIT, params: {ticks: 10}}, {type: SWORD_BLOCK, params: {ticks: 2}}, {type: SHIELD_BLOCK, params: {ticks: 2}}]");
        tick(15);
        assertEquals(List.of("attack[once, 0, true]", "stopAttack[]", "requireRule[swordBlockHitting, null]", "use[continuous, 0]", "stopUse[]",
                "use[continuous, 0]", "stopUse[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void hotbarSlotsAreOneToNine()
    {
        run("[{type: HOTBAR}, {type: HOTBAR, params: {slot: 9}}, {type: HOTBAR, params: {slot: 0}}, {type: HOTBAR, params: {slot: 12}}]");
        tick(1);
        assertEquals(List.of("selectHotbar[1]", "selectHotbar[9]", "selectHotbar[1]", "selectHotbar[9]", "stopAll[]"), recorder.calls);
    }

    @Test
    void equipmentActionsPassTheirSetSlotAndItem()
    {
        run("""
                [{type: EQUIP_ARMOR, params: {armorSet: iron}},
                 {type: EQUIP_SLOT, params: {slot: offhand, item: shield}},
                 {type: UNEQUIP, params: {slot: head}},
                 {type: UNEQUIP}]""");
        tick(1);
        assertEquals(List.of("equipArmor[iron]", "equipItem[offhand, shield]", "unequip[head]", "unequip[all]", "stopAll[]"), recorder.calls);
    }

    @Test
    void lookActionsPassTheirAngles()
    {
        run("""
                [{type: TURN, params: {yaw: -45, pitch: 10}},
                 {type: LOOK_YAW_PITCH, params: {yaw: 30, pitch: -20}},
                 {type: LOOK_AT, params: {x: 1, y: 2, z: 3}},
                 {type: LOOK_AT_PLAYER, params: {player: Steve}},
                 {type: LOOK_DIRECTION, params: {direction: up}}]""");
        tick(1);
        assertEquals(List.of("turn[-45.0, 10.0]", "look[30.0, -20.0]", "lookAt[1.0, 2.0, 3.0]", "lookAtPlayer[Steve]", "lookDirection[up]", "stopAll[]"),
                recorder.calls);
    }

    @Test
    void distanceCheckAsksAboutItsTarget()
    {
        recorder.answers.put("distanceToPlayer", 3.0);
        run("""
                [{type: IF_THEN_ELSE, condition: {type: CONDITION_DISTANCE, params: {target: Steve, operator: "<", value: 5}},
                  children: [{type: JUMP}], elseChildren: [{type: DISMOUNT}]}]""");
        tick(1);
        // combatEvents is asked first, so that the fight events of this tick are seen once and only once
        assertEquals(List.of("combatEvents[]", "distanceToPlayer[Steve]"), recorder.questions);
        assertEquals(List.of("jump[]"), recorder.calls);
    }

    @Test
    void followEndsAfterItsTicks()
    {
        recorder.answers.put("isNavigating", true);
        run("[{type: FOLLOW_PLAYER, params: {player: Steve, distance: 4, ticks: 3}}, {type: JUMP}]");
        tick(3);
        assertEquals(List.of(NAV_RULE, "follow[Steve, 4.0]"), recorder.calls);
        tick(1);
        assertEquals(List.of(NAV_RULE, "follow[Steve, 4.0]", "stopNavigation[]", "jump[]"), recorder.calls);
    }

    @Test
    void fleeEndsAfterItsTicks()
    {
        recorder.answers.put("fleeFrom", true);
        run("[{type: FLEE_FROM, params: {player: Steve, distance: 20, ticks: 3}}, {type: JUMP}]");
        tick(3);
        assertEquals(List.of(NAV_RULE), recorder.calls);
        // every tick asks what the game reported about the fight, and once more for the retest below
        assertEquals(List.of("combatEvents[]", "fleeFrom[Steve, 20.0]", "combatEvents[]", "combatEvents[]"), recorder.questions);
        tick(1);
        assertEquals(List.of(NAV_RULE, "stopNavigation[]", "jump[]"), recorder.calls);
    }

    @Test
    void fleeEndsEarlyOnceFarEnough()
    {
        recorder.answers.put("fleeFrom", true);
        run("[{type: FLEE_FROM, params: {distance: 20, ticks: 500}}, {type: JUMP}]");
        tick(5);
        assertEquals(List.of(NAV_RULE), recorder.calls);
        recorder.answers.put("fleeFrom", false);
        tick(6);
        assertEquals(List.of(NAV_RULE, "stopNavigation[]", "jump[]"), recorder.calls);
    }

    @Test
    void wanderPicksNewSpotsUntilItsTicksRunOut()
    {
        run("[{type: WANDER, params: {radius: 8, ticks: 3}}, {type: JUMP}]");
        tick(4);
        assertEquals(List.of(NAV_RULE, "wander[8.0]", "wander[8.0]", "wander[8.0]", "wander[8.0]", "stopNavigation[]", "jump[]"), recorder.calls);
    }

    @Test
    void navGotoWaitsForArrival()
    {
        recorder.answers.put("isNavigating", true);
        run("[{type: NAV_GOTO, params: {x: 10, y: 64, z: -3, mode: land, radius: 2}}, {type: JUMP}]");
        tick(50);
        assertEquals(List.of(NAV_RULE, "navGoto[10.0, 64.0, -3.0, land, 2.0]"), recorder.calls);
        recorder.answers.put("isNavigating", false);
        tick(1);
        assertEquals(List.of(NAV_RULE, "navGoto[10.0, 64.0, -3.0, land, 2.0]", "jump[]"), recorder.calls);
    }

    @Test
    void chaseAttacksUntilItsTicksRunOut()
    {
        recorder.answers.put("isNavigating", true);
        run("[{type: CHASE_PLAYER, params: {player: Steve, critical: true, range: 2.5, interval: 5, ticks: 3}}, {type: JUMP}]");
        tick(3);
        assertEquals(List.of(NAV_RULE, "chase[Steve, true, 2.5, 5]"), recorder.calls);
        tick(1);
        assertEquals(List.of(NAV_RULE, "chase[Steve, true, 2.5, 5]", "stopNavigation[]", "stopAttack[]", "jump[]"), recorder.calls);
    }

    @Test
    void chaseEndsWhenItsTargetIsGone()
    {
        recorder.answers.put("isNavigating", true);
        run("[{type: CHASE_PLAYER, params: {ticks: 500}}, {type: JUMP}]");
        tick(5);
        recorder.answers.put("isNavigating", false);
        tick(1);
        assertEquals(List.of(NAV_RULE, "chase[, false, 3.0, 0]", "stopNavigation[]", "stopAttack[]", "jump[]"), recorder.calls);
    }

    @Test
    void patrolWalksBetweenItsPointsForItsTicks()
    {
        recorder.answers.put("isNavigating", true);
        run("[{type: PATROL, params: {x1: 1, y1: 2, z1: 3, x2: 4, y2: 5, z2: 6, loop: false, ticks: 2}}, {type: JUMP}]");
        tick(3);
        assertEquals(List.of(NAV_RULE, "patrol[1.0, 2.0, 3.0, 4.0, 5.0, 6.0, false]", "stopNavigation[]", "jump[]"), recorder.calls);
    }

    @Test
    void glidingActionsAskForTheGlideRule()
    {
        run("""
                [{type: GLIDE_START}, {type: GLIDE_HEADING, params: {yaw: 90, pitch: -10}}, {type: GLIDE_SPEED, params: {speed: 2}},
                 {type: GLIDE_FREEZE}, {type: GLIDE_LAND}, {type: GLIDE_STOP}, {type: NAV_STOP}]""");
        tick(1);
        String rule = "requireRule[fakePlayerElytraGlide, null]";
        assertEquals(List.of(rule, "setGliding[true]", rule, "glideHeading[90.0, -10.0]", rule, "glideSpeed[2.0]",
                rule, "glideFreeze[true]", rule, "glideLand[]", "setGliding[false]", "stopNavigation[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void anActionWhoseRuleIsOffStopsTheProgramBeforeActing()
    {
        recorder.failures.put("requireRule", new BotActionException("The carpet rule 'fakePlayerNavigation' is off"));
        run("[{type: SPRINT}, {type: NAV_GOTO}, {type: JUMP}]");
        tick(2);
        assertEquals(List.of("setSprinting[true]", "stopAll[]"), recorder.calls);
        assertEquals("ERROR", status());
        assertEquals("The carpet rule 'fakePlayerNavigation' is off", executor.getPrograms().get("bot").error());
    }

    @Test
    void foreverWithoutAWaitStopsAtTheTickBudget()
    {
        run("[{type: FOREVER, children: [{type: SPRINT}]}]");
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> tick(1));
        long firstTick = recorder.count("setSprinting");
        assertTrue(firstTick > 0 && firstTick <= ProgramExecutor.MAX_STEPS_PER_TICK, "actions run in the first tick: " + firstTick);
        assertEquals("RUNNING", status());

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> tick(1));
        long secondTick = recorder.count("setSprinting") - firstTick;
        assertTrue(secondTick > 0 && secondTick <= ProgramExecutor.MAX_STEPS_PER_TICK, "actions run in the second tick: " + secondTick);
        assertEquals("RUNNING", status());
        assertEquals(1, warnings.size(), "the author is told once: " + warnings);
    }

    @Test
    void aLoopWithoutAWaitDoesNotStarveOtherPrograms()
    {
        RecordingBot other = new RecordingBot();
        ProgramExecutor two = new ProgramExecutor(schema, name -> "spinner".equals(name) ? recorder.bot : other.bot, () -> 4);
        BotProgram spin = new BotProgram("spin", "spin", "");
        spin.setActions(parse("[{type: FOREVER, children: [{type: FOREVER, children: [{type: SNEAK}]}]}]"));
        BotProgram walk = new BotProgram("walk", "walk", "");
        walk.setActions(parse("[{type: MOVE, params: {ticks: 2}}, {type: JUMP}]"));
        assertNull(two.startProgram("spinner", spin, null));
        assertNull(two.startProgram("walker", walk, null));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
        {
            two.tick();
            two.tick();
            two.tick();
        });
        assertEquals(List.of("move[1.0, 0.0]", "stopMoving[]", "jump[]"), other.calls);
        assertEquals("RUNNING", two.getPrograms().get("spinner").status());
    }

    @Test
    void anEmptyForeverWaitsWithoutSpinning()
    {
        run("[{type: FOREVER}, {type: JUMP}]");
        tick(50);
        assertEquals(List.of(), recorder.calls);
        assertEquals("RUNNING", status());
        assertEquals(List.of(), warnings);
    }

    @Test
    void repeatRunsItsBodyCountTimesThenContinues()
    {
        run("[{type: LOOP, params: {count: 3}, children: [{type: SPRINT}, {type: JUMP}]}, {type: DISMOUNT}]");
        tick(4);
        assertEquals(List.of("setSprinting[true]", "jump[]", "setSprinting[true]", "jump[]", "setSprinting[true]", "jump[]",
                "dismount[]", "stopAll[]"), recorder.calls);
        assertEquals("COMPLETED", status());
    }

    @Test
    void repeatZeroTimesSkipsItsBody()
    {
        run("[{type: LOOP, params: {count: 0}, children: [{type: JUMP}]}, {type: DISMOUNT}]");
        tick(1);
        assertEquals(List.of("dismount[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void nestedRepeatsMultiply()
    {
        run("""
                [{type: LOOP, params: {count: 2}, children: [
                    {type: LOOP, params: {count: 3}, children: [{type: SPRINT}]},
                    {type: SNEAK}]},
                 {type: DISMOUNT}]""");
        tick(1);
        assertEquals(6, recorder.count("setSprinting"));
        assertEquals(2, recorder.count("setSneaking"));
        assertEquals("dismount[]", recorder.calls.get(recorder.calls.size() - 2));
    }

    @Test
    void ifElseTakesTheThenBranchOnlyThenContinues()
    {
        recorder.answers.put("isSprinting", true);
        run("""
                [{type: IF_THEN_ELSE, condition: {type: CONDITION_IS_SPRINTING},
                  children: [{type: JUMP}], elseChildren: [{type: DISMOUNT}]},
                 {type: SWAP_HANDS}]""");
        tick(3);
        assertEquals(List.of("jump[]", "swapHands[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void ifElseTakesTheElseBranchOnlyThenContinues()
    {
        run("""
                [{type: IF_THEN_ELSE, condition: {type: CONDITION_IS_SPRINTING},
                  children: [{type: JUMP}], elseChildren: [{type: DISMOUNT}]},
                 {type: SWAP_HANDS}]""");
        tick(3);
        assertEquals(List.of("dismount[]", "swapHands[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void ifElseWithAnEmptyBranchJustContinues()
    {
        run("[{type: IF_THEN_ELSE, condition: {type: CONDITION_IS_SPRINTING}, children: [{type: JUMP}]}, {type: DISMOUNT}]");
        tick(1);
        assertEquals(List.of("dismount[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void ifElseIsTestedAgainOnEveryPassOfALoop()
    {
        run("""
                [{type: LOOP, params: {count: 2}, children: [
                    {type: IF_THEN_ELSE, condition: {type: CONDITION_IS_SPRINTING},
                     children: [{type: SNEAK}], elseChildren: [{type: SPRINT}]},
                    {type: DELAY, params: {ticks: 1}}]}]""");
        tick(1);
        recorder.answers.put("isSprinting", true);
        tick(2);
        assertEquals(List.of("setSprinting[true]", "setSneaking[true]", "stopAll[]"), recorder.calls);
    }

    @Test
    void anActionThatCannotBeDoneStopsTheProgramWithItsReason()
    {
        recorder.failures.put("executeCommand", new BotActionException("no player to run it as"));
        run("[{type: SPRINT}, {type: EXECUTE_COMMAND, params: {command: \"say hi\"}}, {type: JUMP}]");
        tick(3);
        assertEquals(List.of("setSprinting[true]", "stopAll[]"), recorder.calls);
        assertEquals("ERROR", status());
        assertEquals("no player to run it as", executor.getPrograms().get("bot").error());
    }

    @Test
    void programsOnlyStartOnExistingBotsAndWithinTheLimit()
    {
        BotProgram program = new BotProgram("test", "test", "");
        assertNotNull(executor.startProgram("nobody", program, null));

        ProgramExecutor none = new ProgramExecutor(schema, name -> recorder.bot, () -> 0);
        assertTrue(none.startProgram("bot", program, null).contains("carpetLogicMaxPrograms"));
    }

    @Test
    void aCommandInAProgramRunsAsWhoeverStartedIt()
    {
        UUID steve = UUID.randomUUID();
        run("[{type: EXECUTE_COMMAND, params: {command: \"say hi\"}}]", steve);
        tick(1);
        assertEquals(List.of("executeCommand[say hi, " + steve + "]", "stopAll[]"), recorder.calls);

        // Started from the console there is no player, so the program gets no owner to run commands as.
        executor.stopProgram("bot");
        recorder.calls.clear();
        run("[{type: EXECUTE_COMMAND, params: {command: \"say hi\"}}]");
        tick(1);
        assertEquals(List.of("executeCommand[say hi, null]", "stopAll[]"), recorder.calls);
    }

    @Test
    void variablesAreSetChangedAndComparedWithANumber()
    {
        run("""
                [{type: SET_VARIABLE, params: {name: count, value: 4}},
                 {type: DELAY, params: {ticks: 2}},
                 {type: ADD_VARIABLE, params: {name: count, amount: 3}},
                 {type: IF_THEN_ELSE, condition: {type: CONDITION_VARIABLE, params: {name: count, operator: ">", value: 6}},
                  children: [{type: MOVE, params: {direction: backward, ticks: 5}}]}]""");
        tick(2);
        assertEquals(List.of(), recorder.calls);
        tick(1);
        assertEquals(List.of("move[-1.0, 0.0]"), recorder.calls, "count is 7 by the time the condition is tested");
        tick(6);
        assertEquals(List.of("move[-1.0, 0.0]", "stopMoving[]", "stopAll[]"), recorder.calls);
        assertEquals("COMPLETED", status());
    }

    @Test
    void aVariableStandsInForANumberWhereverOneIsTaken()
    {
        run("[{type: SET_VARIABLE, params: {name: steps, value: 3}}, {type: MOVE, params: {ticks: \"$steps\"}}, {type: JUMP}]");
        tick(3);
        assertEquals(List.of("move[1.0, 0.0]"), recorder.calls);
        tick(1);
        assertEquals(List.of("move[1.0, 0.0]", "stopMoving[]", "jump[]"), recorder.calls);
    }

    @Test
    void aVariableThatWasNeverSetIsZero()
    {
        run("""
                [{type: IF_THEN_ELSE, condition: {type: CONDITION_VARIABLE, params: {name: nothing, operator: "==", value: 0}},
                  children: [{type: JUMP}]},
                 {type: MOVE, params: {ticks: "$missing"}}]""");
        tick(3);
        assertEquals(List.of("jump[]", "move[1.0, 0.0]", "stopMoving[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void aNameTheSchemaDoesNotAcceptStopsTheProgram()
    {
        run("[{type: SET_VARIABLE, params: {name: \"two words\", value: 1}}]");
        tick(1);
        assertEquals(List.of("stopAll[]"), recorder.calls);
        assertEquals("ERROR", status());
        assertTrue(executor.getPrograms().get("bot").error().contains("not a variable name"), executor.getPrograms().get("bot").error());
    }

    @Test
    void aProgramIsNotAllowedMoreVariablesThanTheSchemaAllows()
    {
        int allowed = schema.variables().limit();
        List<BotAction> sets = new ArrayList<>();
        for (int i = 0; i <= allowed; i++)
        {
            sets.add(new BotAction("SET_VARIABLE", Map.of("name", "v" + i, "value", 1.0)));
        }
        run(new Gson().toJson(sets));
        tick(1);
        assertEquals("ERROR", status());
        assertEquals("A program may hold at most " + allowed + " variables", executor.getPrograms().get("bot").error());
    }

    @Test
    void waitUntilCarriesOnAsSoonAsItsConditionHolds()
    {
        recorder.answers.put("isInWater", false);
        run("[{type: WAIT_UNTIL, condition: {type: CONDITION_IS_IN_WATER}, params: {timeout: 100}}, {type: JUMP}]");
        tick(5);
        assertEquals(List.of(), recorder.calls);

        recorder.answers.put("isInWater", true);
        tick(2);
        assertEquals(List.of("jump[]", "stopAll[]"), recorder.calls);
        assertEquals("COMPLETED", status());
    }

    @Test
    void waitUntilCarriesOnWhenItsTimeoutRunsOut()
    {
        run("[{type: WAIT_UNTIL, condition: {type: CONDITION_IS_SPRINTING}, params: {timeout: 3}}, {type: JUMP}]");
        tick(3);
        assertEquals(List.of(), recorder.calls);
        tick(1);
        assertEquals(List.of("jump[]"), recorder.calls);
        tick(1);
        assertEquals(List.of("jump[]", "stopAll[]"), recorder.calls);
    }

    @Test
    void waitUntilWithAConditionThatAlreadyHoldsDoesNotWaitAtAll()
    {
        recorder.answers.put("isSneaking", true);
        run("[{type: WAIT_UNTIL, condition: {type: CONDITION_IS_SNEAKING}, params: {timeout: 100}}, {type: JUMP}]");
        tick(1);
        assertEquals(List.of("jump[]"), recorder.calls);
    }

    @Test
    void anEventTakesOverFromTheSequenceWhichResumesWhereItWas()
    {
        recorder.answers.put("distanceToPlayer", 12.0);
        run("""
                [{type: ON_EVENT, params: {event: when_target_in_range, value: 4}, children: [{type: JUMP}]},
                 {type: MOVE, params: {direction: backward, ticks: 6}},
                 {type: SWAP_HANDS}]""");
        tick(2);
        assertEquals(List.of("move[-1.0, 0.0]"), recorder.calls);

        recorder.answers.put("distanceToPlayer", 2.0);
        tick(1);
        assertEquals(List.of("move[-1.0, 0.0]", "jump[]"), recorder.calls, "the handler runs before the sequence carries on");

        tick(20);
        assertEquals(List.of("move[-1.0, 0.0]", "jump[]", "stopMoving[]", "swapHands[]", "stopAll[]"), recorder.calls);
        assertEquals("COMPLETED", status());
    }

    @Test
    void anEventThatComesAgainWhileItsHandlerIsRunningIsIgnored()
    {
        recorder.answers.put("health", 20.0);
        run("""
                [{type: ON_EVENT, params: {event: when_health_below, value: 10}, children: [
                    {type: DELAY, params: {ticks: 4}}, {type: JUMP}]},
                 {type: LOOP, params: {count: 4}, children: [{type: DELAY, params: {ticks: 2}}, {type: SNEAK}]}]""");
        tick(1);
        recorder.answers.put("health", 5.0);
        tick(1);
        assertEquals(List.of(), recorder.calls, "the handler is waiting out its delay");
        recorder.answers.put("health", 20.0);
        tick(1);
        recorder.answers.put("health", 5.0);
        tick(20);
        assertEquals(1, recorder.count("jump"), recorder.calls.toString());
        assertEquals("COMPLETED", status());
    }

    @Test
    void beingHitFiresTheHandlerOnceTheHealthGoesDown()
    {
        recorder.answers.put("health", 20.0);
        run("""
                [{type: ON_EVENT, params: {event: when_hit}, children: [{type: SNEAK}]},
                 {type: LOOP, params: {count: 4}, children: [{type: DELAY, params: {ticks: 2}}]}]""");
        tick(1);
        recorder.answers.put("health", 16.0);
        tick(2);
        assertEquals(List.of("setSneaking[true]"), recorder.calls);
        tick(20);
        assertEquals(1, recorder.count("setSneaking"), recorder.calls.toString());
    }

    @Test
    void losingItsTargetFiresTheHandler()
    {
        recorder.answers.put("distanceToPlayer", 6.0);
        run("""
                [{type: ON_EVENT, params: {event: when_target_lost}, children: [{type: SWAP_HANDS}]},
                 {type: LOOP, params: {count: 4}, children: [{type: DELAY, params: {ticks: 2}}]}]""");
        tick(1);
        assertEquals(List.of(), recorder.calls, "there is still a target");
        recorder.answers.put("distanceToPlayer", Double.POSITIVE_INFINITY);
        tick(2);
        assertEquals(List.of("swapHands[]"), recorder.calls);
    }

    @Test
    void aHandlerRunsUnderTheSameStepBudgetAsTheSequence()
    {
        recorder.answers.put("health", 20.0);
        run("""
                [{type: ON_EVENT, params: {event: when_health_below, value: 10}, children: [
                    {type: FOREVER, children: [{type: SNEAK}]}]},
                 {type: LOOP, params: {count: 6}, children: [{type: DELAY, params: {ticks: 1}}]}]""");
        tick(2);
        recorder.answers.put("health", 5.0);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> tick(1));
        long inOneTick = recorder.count("setSneaking");
        assertTrue(inOneTick > 0 && inOneTick <= ProgramExecutor.MAX_STEPS_PER_TICK, "the handler's steps in one tick: " + inOneTick);
        assertEquals(1, warnings.size(), "the author is told once: " + warnings);
        assertEquals("RUNNING", status());
    }

    @Test
    void aHandlerIsOnlyRegisteredOnceHoweverOftenItsNodeIsPassed()
    {
        recorder.answers.put("health", 20.0);
        run("""
                [{type: LOOP, params: {count: 6}, children: [
                    {type: ON_EVENT, params: {event: when_health_below, value: 10}, children: [
                        {type: DELAY, params: {ticks: 1}}, {type: SNEAK}]},
                    {type: DELAY, params: {ticks: 1}}]}]""");
        tick(4);
        recorder.answers.put("health", 5.0);
        tick(2);
        assertEquals(List.of("setSneaking[true]"), recorder.calls, "one reaction, not one per pass of the loop");
    }
}
