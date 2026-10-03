package carpet.logic.program;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramExecutorTest
{
    private final ActionSchema schema = ActionSchema.load();
    private final RecordingBot recorder = new RecordingBot();
    private final ProgramExecutor executor = new ProgramExecutor(schema, name -> "bot".equals(name) ? recorder.bot : null, () -> 4);

    // Programs are written in Gson's lenient JSON: no quotes needed around names and plain words.
    private void run(String json)
    {
        List<BotAction> actions = new Gson().fromJson(json, new TypeToken<List<BotAction>>() {}.getType());
        schema.validate(actions);
        BotProgram program = new BotProgram("test", "test", "");
        program.setActions(actions);
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
        assertEquals(List.of("attack[once, 0, true]", "stopAttack[]", "swordBlock[]", "stopUse[]", "use[continuous, 0]", "stopUse[]", "stopAll[]"),
                recorder.calls);
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
        assertEquals(List.of("distanceToPlayer[Steve]"), recorder.questions);
        assertEquals(List.of("jump[]"), recorder.calls);
    }

    @Test
    void followEndsAfterItsTicks()
    {
        recorder.answers.put("isNavigating", true);
        run("[{type: FOLLOW_PLAYER, params: {player: Steve, distance: 4, ticks: 3}}, {type: JUMP}]");
        tick(3);
        assertEquals(List.of("follow[Steve, 4.0]"), recorder.calls);
        tick(1);
        assertEquals(List.of("follow[Steve, 4.0]", "stopNavigation[]", "jump[]"), recorder.calls);
    }

    @Test
    void fleeEndsAfterItsTicks()
    {
        recorder.answers.put("fleeFrom", true);
        run("[{type: FLEE_FROM, params: {player: Steve, distance: 20, ticks: 3}}, {type: JUMP}]");
        tick(3);
        assertEquals(List.of(), recorder.calls);
        assertEquals(List.of("fleeFrom[Steve, 20.0]"), recorder.questions);
        tick(1);
        assertEquals(List.of("stopNavigation[]", "jump[]"), recorder.calls);
    }

    @Test
    void fleeEndsEarlyOnceFarEnough()
    {
        recorder.answers.put("fleeFrom", true);
        run("[{type: FLEE_FROM, params: {distance: 20, ticks: 500}}, {type: JUMP}]");
        tick(5);
        assertEquals(List.of(), recorder.calls);
        recorder.answers.put("fleeFrom", false);
        tick(6);
        assertEquals(List.of("stopNavigation[]", "jump[]"), recorder.calls);
    }

    @Test
    void wanderPicksNewSpotsUntilItsTicksRunOut()
    {
        run("[{type: WANDER, params: {radius: 8, ticks: 3}}, {type: JUMP}]");
        tick(4);
        assertEquals(List.of("wander[8.0]", "wander[8.0]", "wander[8.0]", "wander[8.0]", "stopNavigation[]", "jump[]"), recorder.calls);
    }

    @Test
    void navGotoWaitsForArrival()
    {
        recorder.answers.put("isNavigating", true);
        run("[{type: NAV_GOTO, params: {x: 10, y: 64, z: -3, mode: land, radius: 2}}, {type: JUMP}]");
        tick(50);
        assertEquals(List.of("navGoto[10.0, 64.0, -3.0, land, 2.0]"), recorder.calls);
        recorder.answers.put("isNavigating", false);
        tick(1);
        assertEquals(List.of("navGoto[10.0, 64.0, -3.0, land, 2.0]", "jump[]"), recorder.calls);
    }

    @Test
    void programsOnlyStartOnExistingBotsAndWithinTheLimit()
    {
        BotProgram program = new BotProgram("test", "test", "");
        assertNotNull(executor.startProgram("nobody", program, null));

        ProgramExecutor none = new ProgramExecutor(schema, name -> recorder.bot, () -> 0);
        assertTrue(none.startProgram("bot", program, null).contains("carpetLogicMaxPrograms"));
    }
}
