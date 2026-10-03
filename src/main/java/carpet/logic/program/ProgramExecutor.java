package carpet.logic.program;

import carpet.logic.program.ActionSchema.Params;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Runs bot programs, advancing each once per server tick. A bot runs at most one program.
 * Every action parameter is read through the {@link ActionSchema}.
 * <p>
 * A program is a tree of actions, walked with a stack of frames: one frame per list of actions being run,
 * the innermost on top. A program runs until it reaches an action that takes time, and picks up there on a
 * later tick. It never runs more than {@link #MAX_STEPS_PER_TICK} steps in one tick, so a loop with nothing
 * to wait for cannot hold up the server.
 */
public class ProgramExecutor
{
    private static final Logger LOG = LogManager.getLogger("CarpetLogic");
    private static final int FLEE_RETARGET_TICKS = 10;
    public static final int MAX_STEPS_PER_TICK = 1000;

    public enum Status
    {
        RUNNING, COMPLETED, ERROR
    }

    public record ProgramInfo(String programName, String status, String currentAction, String error, boolean running)
    {
    }

    // What a program is waiting for before its next action: a number of ticks, a condition, or whichever comes first.
    private static class Wait
    {
        int ticksLeft = -1;
        Predicate<Bot> done;
        Consumer<Bot> eachTick;
        Consumer<Bot> onEnd;
    }

    private static class Frame
    {
        final List<BotAction> actions;
        // How many more times the list runs, this time included; FOREVER when it never stops.
        int runsLeft;
        int index;
        // The reaction this list belongs to, or null for the program's own sequence.
        Handler handler;

        Frame(List<BotAction> actions, int runs)
        {
            this.actions = actions;
            this.runsLeft = runs;
        }
    }

    private static final int FOREVER = -1;

    /**
     * A program's reaction to one event. Its actions take over from the main sequence, which resumes where it
     * was once they are done. An event that happens while its own handler is running is ignored.
     */
    private static class Handler
    {
        // The ON_EVENT node this reaction came from, and the event it watches for.
        final BotAction action;
        String event;
        boolean wasTrue;
        boolean running;
        Wait interrupted;

        Handler(BotAction action)
        {
            this.action = action;
        }
    }

    private static class ProgramState
    {
        final BotProgram program;
        final UUID owner;
        final Deque<Frame> stack = new ArrayDeque<>();
        final Map<String, Double> variables = new LinkedHashMap<>();
        final List<Handler> handlers = new ArrayList<>();
        Status status = Status.RUNNING;
        Wait wait;
        String currentAction;
        String error;
        boolean warnedAboutBudget;
        double lastHealth;

        ProgramState(BotProgram program, UUID owner)
        {
            this.program = program;
            this.owner = owner;
        }
    }

    private final ActionSchema schema;
    private final Function<String, ? extends Bot> bots;
    private final IntSupplier maxPrograms;
    private final Map<String, ProgramState> programs = new LinkedHashMap<>();
    private BiConsumer<String, String> logListener = (level, message) -> {};

    /**
     * @param bots looks a bot up by name, giving null when there is none
     */
    public ProgramExecutor(ActionSchema schema, Function<String, ? extends Bot> bots, IntSupplier maxPrograms)
    {
        this.schema = schema;
        this.bots = bots;
        this.maxPrograms = maxPrograms;
    }

    public void setLogListener(BiConsumer<String, String> logListener)
    {
        this.logListener = logListener;
    }

    /**
     * @param owner the player the program runs on behalf of, or null when it was started from the console
     * @return null when the program was started, otherwise why it was not
     */
    public String startProgram(String botName, BotProgram program, UUID owner)
    {
        Bot bot = bots.apply(botName);
        if (bot == null)
        {
            return "There is no bot named '" + botName + "'";
        }
        stopProgram(botName);
        if (getRunningCount() >= maxPrograms.getAsInt())
        {
            return getRunningCount() + " programs are already running (carpetLogicMaxPrograms)";
        }
        ProgramState state = new ProgramState(program, owner);
        pushFrame(state, program.getActions(), 1);
        programs.put(botName, state);
        logListener.accept("INFO", "Started '" + program.getName() + "' on " + botName);
        return null;
    }

    public boolean stopProgram(String botName)
    {
        ProgramState state = programs.remove(botName);
        if (state == null || state.status != Status.RUNNING)
        {
            return false;
        }
        Bot bot = bots.apply(botName);
        if (bot != null)
        {
            bot.stopAll();
        }
        logListener.accept("INFO", "Stopped program on " + botName);
        return true;
    }

    public void stopAll()
    {
        for (String botName : new ArrayList<>(programs.keySet()))
        {
            stopProgram(botName);
        }
    }

    public void tick()
    {
        for (Map.Entry<String, ProgramState> entry : new ArrayList<>(programs.entrySet()))
        {
            String botName = entry.getKey();
            ProgramState state = entry.getValue();
            Bot bot = bots.apply(botName);
            if (bot == null)
            {
                programs.remove(botName);
                if (state.status == Status.RUNNING)
                {
                    logListener.accept("ERROR", "Program '" + state.program.getName() + "' ended: bot " + botName + " is gone");
                }
                continue;
            }
            if (state.status != Status.RUNNING)
            {
                continue;
            }
            try
            {
                tickProgram(state, bot, botName);
                // Only a program that watches for being hit needs to know how the bot was doing.
                if (!state.handlers.isEmpty())
                {
                    state.lastHealth = bot.health();
                }
            }
            catch (BotActionException e)
            {
                fail(state, bot, botName, e.getMessage());
            }
            catch (RuntimeException e)
            {
                LOG.error("Program '{}' on bot '{}' failed", state.program.getName(), botName, e);
                fail(state, bot, botName, "internal error, see the server log");
            }
        }
    }

    private void fail(ProgramState state, Bot bot, String botName, String reason)
    {
        // The reason may quote text from the program; keep it on one line in the log.
        String message = reason.replaceAll("\\p{Cntrl}", " ");
        state.status = Status.ERROR;
        state.error = message;
        state.wait = null;
        bot.stopAll();
        LOG.warn("Program '{}' on bot '{}' stopped: {}", state.program.getName(), botName, message);
        logListener.accept("ERROR", "Program '" + state.program.getName() + "' on " + botName + " stopped: " + message);
    }

    private void tickProgram(ProgramState state, Bot bot, String botName)
    {
        fireEvents(state, bot, botName);
        if (state.wait != null)
        {
            Wait wait = state.wait;
            if (wait.eachTick != null)
            {
                wait.eachTick.accept(bot);
            }
            boolean timeUp = wait.ticksLeft >= 0 && --wait.ticksLeft <= 0;
            if (!timeUp && (wait.done == null || !wait.done.test(bot)))
            {
                return;
            }
            state.wait = null;
            if (wait.onEnd != null)
            {
                wait.onEnd.accept(bot);
            }
        }

        int stepsLeft = MAX_STEPS_PER_TICK;
        while (state.wait == null)
        {
            if (stepsLeft-- == 0)
            {
                if (!state.warnedAboutBudget)
                {
                    state.warnedAboutBudget = true;
                    logListener.accept("WARN", "Program '" + state.program.getName() + "' on " + botName + " ran " + MAX_STEPS_PER_TICK
                            + " steps in one tick and was paused until the next. Put a Delay inside its loop.");
                }
                return;
            }
            if (state.stack.isEmpty())
            {
                state.status = Status.COMPLETED;
                state.currentAction = null;
                bot.stopAll();
                logListener.accept("INFO", "Program '" + state.program.getName() + "' completed");
                return;
            }
            Frame frame = state.stack.peek();
            if (frame.index >= frame.actions.size())
            {
                if (frame.runsLeft == FOREVER || --frame.runsLeft > 0)
                {
                    frame.index = 0;
                }
                else
                {
                    state.stack.pop();
                    if (frame.handler != null)
                    {
                        // The main sequence takes over again, and waits out what it was waiting for.
                        frame.handler.running = false;
                        state.wait = frame.handler.interrupted;
                    }
                }
                continue;
            }
            BotAction action = frame.actions.get(frame.index++);
            state.currentAction = action.getType();
            execute(action, state, bot);
        }
    }

    /**
     * Hands the tick to the handlers whose event has just become true. Each one that starts takes over from the
     * main sequence, which is put back the way it was when the handler's actions are done. An event that
     * happens again while its own handler is still running is ignored.
     */
    private void fireEvents(ProgramState state, Bot bot, String botName)
    {
        for (Handler handler : state.handlers)
        {
            boolean holds = holds(handler, state, bot);
            boolean fired = holds && !handler.wasTrue;
            handler.wasTrue = holds;
            if (!fired || handler.running || handler.action.getChildren().isEmpty())
            {
                continue;
            }
            handler.interrupted = state.wait;
            state.wait = null;
            handler.running = true;
            Frame frame = new Frame(handler.action.getChildren(), 1);
            frame.handler = handler;
            state.stack.push(frame);
            logListener.accept("INFO", "Program '" + state.program.getName() + "' on " + botName + " is running its " + handler.event + " handler");
        }
    }

    /**
     * Whether an event's condition holds now. Each one is written so that its rising edge is the event: the bot
     * took damage, its health went below the given value, its target went away, or its target came into range.
     */
    private boolean holds(Handler handler, ProgramState state, Bot bot)
    {
        Params p = schema.params(handler.action, state.variables);
        String target = p.string("target");
        double value = p.number("value");
        return switch (handler.event)
        {
            case "when_hit" -> bot.health() < state.lastHealth;
            case "when_health_below" -> bot.health() < value;
            case "when_target_lost" -> Double.isInfinite(bot.distanceToPlayer(target));
            case "when_target_in_range" -> bot.distanceToPlayer(target) <= value;
            default -> throw new IllegalStateException("No interpreter for event " + handler.event);
        };
    }

    private void execute(BotAction action, ProgramState state, Bot bot)
    {
        String requiredRule = schema.definition(action).requires();
        if (requiredRule != null)
        {
            bot.requireRule(requiredRule, state.owner);
        }
        Params p = schema.params(action, state.variables);
        switch (action.getType())
        {
            case "MOVE" ->
            {
                switch (p.string("direction"))
                {
                    case "backward" -> bot.move(-1, 0);
                    case "left" -> bot.move(0, 1);
                    case "right" -> bot.move(0, -1);
                    default -> bot.move(1, 0);
                }
                hold(state, bot, p.integer("ticks"), Bot::stopMoving);
            }
            case "STRAFE" ->
            {
                bot.strafe("right".equals(p.string("direction")) ? -1 : 1);
                hold(state, bot, p.integer("ticks"), Bot::stopStrafing);
            }
            case "SPRINT" ->
            {
                bot.setSprinting(p.bool("enabled"));
                hold(state, bot, p.integer("ticks"), null);
            }
            case "SNEAK" ->
            {
                bot.setSneaking(p.bool("enabled"));
                hold(state, bot, p.integer("ticks"), null);
            }
            case "JUMP" ->
            {
                bot.jump();
                hold(state, bot, p.integer("ticks"), null);
            }
            case "MOUNT" -> bot.mount(p.bool("onlyRideables"));
            case "DISMOUNT" -> bot.dismount();
            case "STOP_MOVEMENT" -> bot.stopMovement();

            case "ATTACK" ->
            {
                String mode = p.string("mode");
                bot.attack(mode, p.integer("interval"), false);
                hold(state, bot, p.integer("ticks"), "once".equals(mode) ? null : Bot::stopAttack);
            }
            case "ATTACK_CRIT" ->
            {
                bot.attack("once", 0, true);
                hold(state, bot, p.integer("ticks"), Bot::stopAttack);
            }
            case "SWORD_BLOCK", "SHIELD_BLOCK" ->
            {
                bot.use("continuous", 0);
                hold(state, bot, p.integer("ticks"), Bot::stopUse);
            }
            case "USE" ->
            {
                String mode = p.string("mode");
                bot.use(mode, p.integer("interval"));
                hold(state, bot, p.integer("ticks"), "once".equals(mode) ? null : Bot::stopUse);
            }
            case "PLACE_BLOCK", "PLACE_CRYSTAL" ->
            {
                bot.use("once", 0);
                hold(state, bot, p.integer("ticks"), null);
            }
            case "DETONATE_CRYSTAL" ->
            {
                bot.attack("once", 0, false);
                hold(state, bot, p.integer("ticks"), null);
            }

            case "HOTBAR" -> bot.selectHotbar(p.integer("slot"));
            case "EQUIP_ARMOR" -> bot.equipArmor(p.string("armorSet"));
            case "EQUIP_SLOT" -> bot.equipItem(p.string("slot"), p.string("item"));
            case "UNEQUIP" -> bot.unequip(p.string("slot"));
            case "DROP" ->
            {
                bot.drop(false);
                hold(state, bot, p.integer("ticks"), null);
            }
            case "DROP_STACK" ->
            {
                bot.drop(true);
                hold(state, bot, p.integer("ticks"), null);
            }
            case "SWAP_HANDS" ->
            {
                bot.swapHands();
                hold(state, bot, 1, null);
            }

            case "LOOK_DIRECTION" -> bot.lookDirection(p.string("direction"));
            case "LOOK_AT" -> bot.lookAt(p.number("x"), p.number("y"), p.number("z"));
            case "LOOK_AT_PLAYER" -> bot.lookAtPlayer(p.string("player"));
            case "LOOK_YAW_PITCH" -> bot.look((float) p.number("yaw"), (float) p.number("pitch"));
            case "TURN" -> bot.turn((float) p.number("yaw"), (float) p.number("pitch"));

            case "NAV_GOTO" ->
            {
                bot.navGoto(p.number("x"), p.number("y"), p.number("z"), p.string("mode"), p.number("radius"));
                Wait wait = new Wait();
                wait.done = b -> !b.isNavigating();
                state.wait = wait;
            }
            case "NAV_STOP" -> bot.stopNavigation();
            case "FOLLOW_PLAYER" ->
            {
                bot.follow(p.string("player"), p.number("distance"));
                Wait wait = hold(state, bot, p.integer("ticks"), Bot::stopNavigation);
                wait.done = b -> !b.isNavigating();
            }
            case "CHASE_PLAYER" ->
            {
                bot.chase(p.string("player"), p.bool("critical"), p.number("range"), p.integer("interval"));
                Wait wait = hold(state, bot, p.integer("ticks"), b ->
                {
                    b.stopNavigation();
                    b.stopAttack();
                });
                wait.done = b -> !b.isNavigating();
            }
            case "PATROL" ->
            {
                bot.patrol(p.number("x1"), p.number("y1"), p.number("z1"), p.number("x2"), p.number("y2"), p.number("z2"), p.bool("loop"));
                Wait wait = hold(state, bot, p.integer("ticks"), Bot::stopNavigation);
                wait.done = b -> !b.isNavigating();
            }
            case "FLEE_FROM" ->
            {
                String player = p.string("player");
                double distance = p.number("distance");
                int ticks = p.integer("ticks");
                if (bot.fleeFrom(player, distance))
                {
                    Wait wait = hold(state, bot, ticks, Bot::stopNavigation);
                    int[] untilRetarget = {FLEE_RETARGET_TICKS};
                    wait.done = b ->
                    {
                        if (--untilRetarget[0] > 0)
                        {
                            return false;
                        }
                        untilRetarget[0] = FLEE_RETARGET_TICKS;
                        return !b.fleeFrom(player, distance);
                    };
                }
            }
            case "WANDER" ->
            {
                double radius = p.number("radius");
                bot.wander(radius);
                Wait wait = hold(state, bot, p.integer("ticks"), Bot::stopNavigation);
                wait.eachTick = b ->
                {
                    if (!b.isNavigating())
                    {
                        b.wander(radius);
                    }
                };
            }

            case "GLIDE_START" -> bot.setGliding(true);
            case "GLIDE_STOP" -> bot.setGliding(false);
            case "GLIDE_GOTO" ->
            {
                double x = p.number("x");
                double y = p.number("y");
                double z = p.number("z");
                double radius = p.number("radius");
                bot.glideGoto(x, y, z, radius);
                Wait wait = new Wait();
                wait.done = b -> !b.isGliding() || b.distanceTo(x, y, z) <= radius;
                state.wait = wait;
            }
            case "GLIDE_HEADING" -> bot.glideHeading((float) p.number("yaw"), (float) p.number("pitch"));
            case "GLIDE_SPEED" -> bot.glideSpeed(p.number("speed"));
            case "GLIDE_FREEZE" -> bot.glideFreeze(p.bool("enabled"));
            case "GLIDE_LAND" -> bot.glideLand();

            case "DELAY" -> hold(state, bot, p.integer("ticks"), null);
            case "WAIT_UNTIL" ->
            {
                BotAction condition = action.getCondition();
                Wait wait = new Wait();
                wait.ticksLeft = p.integer("timeout");
                wait.done = b -> test(condition, b, state);
                if (!wait.done.test(bot))
                {
                    state.wait = wait;
                }
            }
            case "SET_VARIABLE" -> setVariable(state, p.string("name"), p.number("value"));
            case "ADD_VARIABLE" -> setVariable(state, p.string("name"), variable(state, p.string("name")) + p.number("amount"));
            case "ON_EVENT" -> addHandler(action, state, bot);
            case "EXECUTE_COMMAND" -> bot.executeCommand(p.string("command"), state.owner);
            case "SEQUENCE" -> pushFrame(state, action.getChildren(), 1);
            case "LOOP" -> pushFrame(state, action.getChildren(), p.integer("count"));
            case "FOREVER" ->
            {
                if (action.getChildren().isEmpty())
                {
                    // Nothing to repeat: the program just never moves on.
                    state.wait = new Wait();
                }
                pushFrame(state, action.getChildren(), FOREVER);
            }
            case "IF_THEN_ELSE" -> pushFrame(state, test(action.getCondition(), bot, state) ? action.getChildren() : action.getElseChildren(), 1);

            default -> throw new IllegalStateException("No interpreter for action type " + action.getType());
        }
    }

    // Makes the program wait the given ticks before its next action, then runs onEnd. No wait when ticks is 0.
    private static Wait hold(ProgramState state, Bot bot, int ticks, Consumer<Bot> onEnd)
    {
        Wait wait = new Wait();
        wait.ticksLeft = ticks;
        wait.onEnd = onEnd;
        if (ticks > 0)
        {
            state.wait = wait;
        }
        else if (onEnd != null)
        {
            onEnd.accept(bot);
        }
        return wait;
    }

    // Runs the actions the given number of times before the frame below continues.
    private static void pushFrame(ProgramState state, List<BotAction> actions, int runs)
    {
        if (!actions.isEmpty() && runs != 0)
        {
            state.stack.push(new Frame(actions, runs));
        }
    }

    /** What a number parameter naming a variable reads as: 0 for a variable the program never set. */
    private static double variable(ProgramState state, String name)
    {
        Double value = state.variables.get(name);
        return value == null ? 0.0 : value;
    }

    private void setVariable(ProgramState state, String name, double value)
    {
        if (!schema.variables().isName(name))
        {
            throw new BotActionException("'" + name + "' is not a variable name: it must match " + schema.variables().names().pattern());
        }
        if (!state.variables.containsKey(name) && state.variables.size() >= schema.variables().limit())
        {
            throw new BotActionException("A program may hold at most " + schema.variables().limit() + " variables");
        }
        state.variables.put(name, value);
    }

    /**
     * Registers a reaction, once, however often the sequence comes back to it. Whether its event already holds
     * is remembered now, so that the handler only ever fires on the edge that comes later.
     */
    private void addHandler(BotAction event, ProgramState state, Bot bot)
    {
        if (state.handlers.stream().anyMatch(handler -> handler.action == event))
        {
            return;
        }
        Handler handler = new Handler(event);
        handler.event = schema.params(event, state.variables).string("event");
        if (state.handlers.isEmpty())
        {
            state.lastHealth = bot.health();
        }
        handler.wasTrue = holds(handler, state, bot);
        state.handlers.add(handler);
    }

    private boolean test(BotAction condition, Bot bot, ProgramState state)
    {
        Params p = schema.params(condition, state.variables);
        return switch (condition.getType())
        {
            case "CONDITION_HEALTH" -> compare(bot.health(), p.string("operator"), p.number("value"));
            case "CONDITION_FOOD" -> compare(bot.food(), p.string("operator"), p.number("value"));
            case "CONDITION_ARMOR" -> compare(bot.armor(), p.string("operator"), p.number("value"));
            case "CONDITION_DISTANCE" -> compare(bot.distanceToPlayer(p.string("target")), p.string("operator"), p.number("value"));
            case "CONDITION_VARIABLE" -> compare(variable(state, p.string("name")), p.string("operator"), p.number("value"));
            case "CONDITION_RANDOM" -> Math.random() * 100 < p.number("chance");
            case "CONDITION_HAS_ITEM" -> bot.hasItem(p.string("item"));
            case "CONDITION_IS_FLYING" -> bot.isFlying();
            case "CONDITION_IS_SNEAKING" -> bot.isSneaking();
            case "CONDITION_IS_SPRINTING" -> bot.isSprinting();
            case "CONDITION_IS_IN_WATER" -> bot.isInWater();
            default -> throw new IllegalStateException("No interpreter for condition type " + condition.getType());
        };
    }

    private static boolean compare(double actual, String operator, double value)
    {
        return switch (operator)
        {
            case "<" -> actual < value;
            case "<=" -> actual <= value;
            case ">" -> actual > value;
            case ">=" -> actual >= value;
            case "==" -> Math.abs(actual - value) < 0.01;
            default -> Math.abs(actual - value) >= 0.01;
        };
    }

    public int getRunningCount()
    {
        return (int) programs.values().stream().filter(s -> s.status == Status.RUNNING).count();
    }

    public Map<String, ProgramInfo> getPrograms()
    {
        Map<String, ProgramInfo> infos = new LinkedHashMap<>();
        programs.forEach((bot, s) -> infos.put(bot, new ProgramInfo(
                s.program.getName(), s.status.name(), s.currentAction, s.error, s.status == Status.RUNNING)));
        return infos;
    }
}
