package carpet.logic.program;

import carpet.logic.bot.BotController;
import carpet.logic.bot.BotManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

/**
 * Runs bot programs one step per server tick. Each bot runs at most one program.
 */
public class ProgramExecutor
{
    private static final Logger LOG = LogManager.getLogger("CarpetLogic");

    private final BotManager botManager;
    private final IntSupplier maxPrograms;
    private final Map<String, ProgramState> runningPrograms = new HashMap<>();
    private BiConsumer<String, String> logListener = (level, message) -> {};

    public ProgramExecutor(BotManager botManager, IntSupplier maxPrograms)
    {
        this.botManager = botManager;
        this.maxPrograms = maxPrograms;
    }

    public void setLogListener(BiConsumer<String, String> logListener)
    {
        this.logListener = logListener;
    }

    public void tick()
    {
        Iterator<Map.Entry<String, ProgramState>> it = runningPrograms.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<String, ProgramState> entry = it.next();
            String botName = entry.getKey();
            ProgramState state = entry.getValue();

            if (state.status == ProgramStatus.COMPLETED || state.status == ProgramStatus.ERROR)
            {
                it.remove();
                continue;
            }
            if (state.status == ProgramStatus.PAUSED)
            {
                continue;
            }

            BotController controller = botManager.getController(botName);
            if (controller == null)
            {
                state.status = ProgramStatus.ERROR;
                state.errorMessage = "Bot '" + botName + "' not found or disconnected";
                LOG.warn("Bot '{}' lost while its program was running", botName);
                continue;
            }

            try
            {
                tickProgram(state, controller);
            }
            catch (Exception e)
            {
                state.status = ProgramStatus.ERROR;
                state.errorMessage = e.getMessage();
                LOG.error("Error executing program on bot '{}'", botName, e);
            }
        }
    }

    public boolean startProgram(String botName, BotProgram program)
    {
        if (runningPrograms.size() >= maxPrograms.getAsInt() && !runningPrograms.containsKey(botName))
        {
            LOG.warn("Cannot start program: {} programs are already running", runningPrograms.size());
            return false;
        }
        if (botManager.getController(botName) == null)
        {
            LOG.warn("Cannot start program: bot '{}' not found", botName);
            return false;
        }

        stopProgram(botName);

        ProgramState state = new ProgramState(program);
        state.status = ProgramStatus.RUNNING;
        runningPrograms.put(botName, state);
        logListener.accept("INFO", "Started '" + program.getName() + "' on " + botName);
        return true;
    }

    public boolean stopProgram(String botName)
    {
        ProgramState state = runningPrograms.remove(botName);
        if (state == null)
        {
            return false;
        }
        BotController controller = botManager.getController(botName);
        if (controller != null)
        {
            controller.stopAll();
        }
        logListener.accept("INFO", "Stopped program on " + botName);
        return true;
    }

    public void stopAll()
    {
        for (String botName : new ArrayList<>(runningPrograms.keySet()))
        {
            stopProgram(botName);
        }
    }

    private void tickProgram(ProgramState state, BotController controller)
    {
        if (state.delayRemaining > 0)
        {
            state.delayRemaining--;
            return;
        }

        if (state.waitingForCompletion)
        {
            if (state.tickAction != null)
            {
                state.tickAction.tick(controller);
            }
            if (state.completionCheck != null && !state.completionCheck.isComplete(controller))
            {
                return;
            }
            state.clearWait();
        }

        if (state.currentActionDuration > 0)
        {
            state.currentActionDuration--;
            if (state.currentActionDuration > 0)
            {
                return;
            }
        }

        while (state.status == ProgramStatus.RUNNING)
        {
            if (state.executionStack.isEmpty())
            {
                state.status = ProgramStatus.COMPLETED;
                controller.stopAll();
                logListener.accept("INFO", "Program '" + state.program.getName() + "' completed");
                return;
            }

            StackFrame frame = state.executionStack.peek();
            if (frame.actionIndex >= frame.actions.size())
            {
                if (frame.isLoop)
                {
                    frame.loopIteration++;
                    if (frame.maxIterations == -1 || frame.loopIteration < frame.maxIterations)
                    {
                        frame.actionIndex = 0;
                        continue;
                    }
                }
                state.executionStack.pop();
                continue;
            }

            BotAction action = frame.actions.get(frame.actionIndex);
            frame.actionIndex++;
            state.currentBlockType = action.getType().name();

            if (executeAction(action, state, controller))
            {
                return;
            }
        }
    }

    /**
     * @return true when the program has to wait before its next action
     */
    private boolean executeAction(BotAction action, ProgramState state, BotController controller)
    {
        switch (action.getType())
        {
            case MOVE ->
            {
                int duration = action.getIntParam("duration", 20);
                controller.move(action.getStringParam("direction"));
                state.currentActionDuration = duration;
                return true;
            }
            case SPRINT -> controller.sprint(!"stop".equals(action.getStringParam("mode")));
            case SNEAK -> controller.sneak(!"stop".equals(action.getStringParam("mode")));
            case JUMP -> controller.jump();
            case STRAFE ->
            {
                int duration = action.getIntParam("duration", 20);
                controller.strafe(action.getStringParam("direction"));
                state.currentActionDuration = duration;
                return true;
            }
            case MOUNT -> controller.mount(action.getBoolParam("onlyRideables", false));
            case DISMOUNT -> controller.dismount();
            case STOP_MOVEMENT -> controller.stopMovement();

            case ATTACK ->
            {
                String mode = action.getStringParam("mode");
                controller.attack(mode != null ? mode : "once", false, action.getIntParam("interval", 20));
                if (mode != null && !"once".equals(mode))
                {
                    state.currentActionDuration = action.getIntParam("duration", 40);
                    return true;
                }
            }
            case ATTACK_CRIT ->
            {
                String mode = action.getStringParam("mode");
                boolean once = mode == null || "once".equals(mode);
                controller.attack(once ? "once" : mode, true, action.getIntParam("interval", 20));
                state.currentActionDuration = once ? 10 : action.getIntParam("duration", 60);
                return true;
            }
            case SWORD_BLOCK ->
            {
                int duration = action.getIntParam("duration", 10);
                controller.swordBlock();
                state.currentActionDuration = duration;
                return true;
            }
            case SHIELD_BLOCK ->
            {
                int duration = action.getIntParam("duration", 20);
                controller.shieldBlock();
                state.currentActionDuration = duration;
                return true;
            }

            case EQUIP_ARMOR -> controller.equipArmor(action.getStringParam("set"));
            case EQUIP_SLOT -> controller.equipSlot(action.getStringParam("slot"), action.getStringParam("item"));
            case UNEQUIP -> controller.unequip(action.getStringParam("slot"));
            case HOTBAR -> controller.hotbar(action.getIntParam("slot", 1));
            case DROP -> controller.drop();
            case DROP_STACK -> controller.dropStack();
            case SWAP_HANDS -> controller.swapHands();

            case LOOK_DIRECTION -> controller.lookDirection(action.getStringParam("direction"));
            case LOOK_AT ->
            {
                String playerParam = action.getStringParam("player");
                if (playerParam != null && !playerParam.isEmpty())
                {
                    controller.lookAtPlayer(playerParam);
                }
                else
                {
                    controller.lookAt(action.getDoubleParam("x", 0), action.getDoubleParam("y", 0), action.getDoubleParam("z", 0));
                }
            }
            case LOOK_YAW_PITCH -> controller.lookYawPitch((float) action.getDoubleParam("yaw", 0), (float) action.getDoubleParam("pitch", 0));
            case TURN -> controller.turn(action.getStringParam("direction"), (float) action.getDoubleParam("degrees", 90));

            case USE ->
            {
                String mode = action.getStringParam("mode");
                controller.use(mode != null ? mode : "once", action.getIntParam("interval", 20));
            }
            case PLACE_BLOCK, PLACE_CRYSTAL -> controller.use("once", 0);
            case DETONATE_CRYSTAL -> controller.attack("once", false, 0);

            case NAV_GOTO ->
            {
                String mode = action.getStringParam("mode");
                controller.navGoto(action.getDoubleParam("x", 0), action.getDoubleParam("y", 0), action.getDoubleParam("z", 0),
                        mode != null ? mode : "land", action.getDoubleParam("radius", 1.0));
                state.waitFor(BotController::tickNavigation, BotController::isNavigationComplete);
                return true;
            }
            case NAV_STOP -> controller.navStop();
            case NAV_MODE ->
            {
            }
            case FOLLOW_PLAYER ->
            {
                String target = action.getStringParam("player");
                String name = target != null ? target : "nearest";
                controller.followPlayer(name, action.getDoubleParam("distance", 3.0));
                state.waitFor(BotController::tickNavigation, c -> c.findPlayer(name) == null);
                return true;
            }
            case FLEE_FROM ->
            {
                String target = action.getStringParam("player");
                controller.fleeFrom(target != null ? target : "nearest", action.getDoubleParam("distance", 10.0));
                state.waitFor(BotController::tickNavigation, BotController::isNavigationComplete);
                return true;
            }
            case WANDER ->
            {
                controller.wander(action.getDoubleParam("radius", 10.0));
                state.waitFor(BotController::tickNavigation, c -> false);
                return true;
            }

            case GLIDE_START -> controller.glideStart();
            case GLIDE_STOP -> controller.glideStop();
            case GLIDE_GOTO ->
            {
                double x = action.getDoubleParam("x", 0);
                double y = action.getDoubleParam("y", 0);
                double z = action.getDoubleParam("z", 0);
                double radius = action.getDoubleParam("radius", 5.0);
                controller.glideGoto(x, y, z, radius);
                state.waitFor(null, c -> c.getPlayer().distanceToSqr(x, y, z) <= radius * radius);
                return true;
            }
            case GLIDE_HEADING -> controller.glideHeading((float) action.getDoubleParam("yaw", 0), (float) action.getDoubleParam("pitch", 0));
            case GLIDE_SPEED -> controller.glideSpeed(action.getDoubleParam("speed", 1.6));
            case GLIDE_FREEZE -> controller.glideFreeze();
            case GLIDE_LAND -> controller.glideLand();

            case SEQUENCE -> pushFrame(state, action.getChildren(), false, 1);
            case LOOP -> pushFrame(state, action.getChildren(), true, action.getIntParam("count", 1));
            case FOREVER -> pushFrame(state, action.getChildren(), true, -1);
            case DELAY ->
            {
                state.delayRemaining = action.getIntParam("ticks", 20);
                return true;
            }
            case IF_THEN ->
            {
                if (evaluateCondition(action.getCondition(), controller))
                {
                    pushFrame(state, action.getChildren(), false, 1);
                }
            }
            case IF_THEN_ELSE ->
            {
                boolean met = evaluateCondition(action.getCondition(), controller);
                pushFrame(state, met ? action.getChildren() : action.getElseChildren(), false, 1);
            }
            case EXECUTE_COMMAND ->
            {
                String command = action.getStringParam("command");
                if (command != null && !command.isEmpty())
                {
                    controller.executeCommand(command);
                }
            }

            default -> LOG.warn("Unhandled action type: {}", action.getType());
        }
        return false;
    }

    private static void pushFrame(ProgramState state, List<BotAction> actions, boolean isLoop, int maxIterations)
    {
        if (actions != null && !actions.isEmpty())
        {
            state.executionStack.push(new StackFrame(actions, isLoop, maxIterations));
        }
    }

    private boolean evaluateCondition(BotAction condition, BotController controller)
    {
        if (condition == null)
        {
            return false;
        }
        return switch (condition.getType())
        {
            case CONDITION_HEALTH -> compare(controller.getHealth(), condition.getStringParam("operator"), condition.getDoubleParam("value", 10));
            case CONDITION_DISTANCE -> compare(controller.getDistanceToNearestPlayer(), condition.getStringParam("operator"), condition.getDoubleParam("value", 5));
            case CONDITION_RANDOM -> Math.random() * 100 < condition.getDoubleParam("chance", 50);
            case CONDITION_FOOD -> compare(controller.getFoodLevel(), condition.getStringParam("operator"), condition.getDoubleParam("value", 10));
            case CONDITION_HAS_ITEM -> controller.hasItem(condition.getStringParam("item"));
            case CONDITION_IS_FLYING -> controller.isFlying();
            case CONDITION_IS_SNEAKING -> controller.isSneaking();
            case CONDITION_IS_SPRINTING -> controller.isSprinting();
            case CONDITION_IS_IN_WATER -> controller.isInWater();
            case CONDITION_ARMOR -> compare(controller.getArmorValue(), condition.getStringParam("operator"), condition.getDoubleParam("value", 10));
            default -> false;
        };
    }

    private static boolean compare(double actual, String op, double threshold)
    {
        return switch (op == null ? "<" : op)
        {
            case "<" -> actual < threshold;
            case ">" -> actual > threshold;
            case "=", "==" -> Math.abs(actual - threshold) < 0.01;
            case "<=" -> actual <= threshold;
            case ">=" -> actual >= threshold;
            case "!=" -> Math.abs(actual - threshold) >= 0.01;
            default -> false;
        };
    }

    public int getRunningCount()
    {
        return (int) runningPrograms.values().stream().filter(s -> s.status == ProgramStatus.RUNNING).count();
    }

    public Map<String, ProgramStateInfo> getRunningStates()
    {
        Map<String, ProgramStateInfo> states = new HashMap<>();
        runningPrograms.forEach((bot, s) -> states.put(bot, new ProgramStateInfo(
                s.program.getName(), s.status.name(), s.currentBlockType, s.errorMessage, s.status == ProgramStatus.RUNNING)));
        return states;
    }

    public enum ProgramStatus
    {
        RUNNING, PAUSED, COMPLETED, ERROR
    }

    @FunctionalInterface
    interface CompletionCheck
    {
        boolean isComplete(BotController controller);
    }

    @FunctionalInterface
    interface TickAction
    {
        void tick(BotController controller);
    }

    private static class ProgramState
    {
        final BotProgram program;
        final Deque<StackFrame> executionStack = new ArrayDeque<>();
        ProgramStatus status;
        int delayRemaining;
        int currentActionDuration;
        boolean waitingForCompletion;
        String currentBlockType;
        String errorMessage;
        CompletionCheck completionCheck;
        TickAction tickAction;

        ProgramState(BotProgram program)
        {
            this.program = program;
            pushFrame(this, program.getActions(), false, 1);
        }

        void waitFor(TickAction eachTick, CompletionCheck done)
        {
            waitingForCompletion = true;
            tickAction = eachTick;
            completionCheck = done;
        }

        void clearWait()
        {
            waitingForCompletion = false;
            completionCheck = null;
            tickAction = null;
        }
    }

    private static class StackFrame
    {
        final List<BotAction> actions;
        final boolean isLoop;
        final int maxIterations;
        int actionIndex;
        int loopIteration;

        StackFrame(List<BotAction> actions, boolean isLoop, int maxIterations)
        {
            this.actions = actions;
            this.isLoop = isLoop;
            this.maxIterations = maxIterations;
        }
    }

    public record ProgramStateInfo(String programName, String status, String currentBlock, String error, boolean running)
    {
    }
}
