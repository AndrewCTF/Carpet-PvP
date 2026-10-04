package carpet.logic.program;

import carpet.logic.program.ActionSchema.Params;
import carpet.pvp.BotEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * to wait for cannot hold up the server. Evaluating an expression is charged to the same budget: a step for
 * every part of it, and more for the functions that look at the world.
 */
public class ProgramExecutor
{
    private static final Logger LOG = LogManager.getLogger("CarpetLogic");
    private static final int FLEE_RETARGET_TICKS = 10;
    public static final int MAX_STEPS_PER_TICK = 1000;
    /**
     * What a Scarpet node costs out of the tick's steps. The budget cannot interrupt a snippet, so the node is
     * charged as a whole: twenty of them fit into a tick, and a loop of them leaves the tick to everything else.
     */
    public static final int SCRIPT_STEPS = 50;

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
        // The WHILE this list is the body of: it runs again for as long as that one's condition holds.
        BotAction loop;
        // For the body of a FOR_EACH: what is still to come, and the variable each item is put in.
        Iterator<Object> items;
        String itemName;
        // Whether BREAK and CONTINUE mean this list: it is the body of a loop.
        boolean breakable;

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
        final Map<String, Object> variables = new LinkedHashMap<>();
        final List<Handler> handlers = new ArrayList<>();
        // What this tick may still spend, and how many ticks the program has run for.
        int stepsLeft;
        long ticks;
        Bot bot;
        final Reader reader = new Reader(this);
        Status status = Status.RUNNING;
        Wait wait;
        String currentAction;
        String error;
        boolean warnedAboutBudget;
        /** How many combat nodes this program has open; the brain owns the body while there is one. */
        int combatNodes;
        /** Whether this program is the one that turned the combat AI on, and so has to turn it off again. */
        boolean combatStarted;
        boolean warnedAboutCombat;
        double lastHealth;

        ProgramState(BotProgram program, UUID owner)
        {
            this.program = program;
            this.owner = owner;
        }
    }

    /**
     * What an expression of a running program reads: the program's variables, its bot, and the world around it.
     * Every part of an expression that is evaluated is paid for out of the tick's steps.
     */
    private static final class Reader implements Expression.Context
    {
        private final ProgramState state;

        Reader(ProgramState state)
        {
            this.state = state;
        }

        @Override
        public Object variable(String name)
        {
            return state.variables.get(name);
        }

        @Override
        public Object value(String name)
        {
            Bot bot = state.bot;
            return switch (name)
            {
                case "health" -> bot.health();
                case "max_health" -> bot.maxHealth();
                case "food" -> bot.food();
                case "armor" -> bot.armor();
                case "x" -> bot.x();
                case "y" -> bot.y();
                case "z" -> bot.z();
                case "yaw" -> bot.yaw();
                case "pitch" -> bot.pitch();
                case "held_count" -> (double) bot.heldCount();
                case "hotbar_slot" -> (double) bot.hotbarSlot();
                case "target_distance" -> bot.targetDistance();
                case "target_health" -> bot.targetHealth();
                // Counted from the tick the program started on, which is tick 0.
                case "tick" -> (double) (state.ticks - 1);
                case "random" -> Math.random();
                case "held_item" -> bot.heldItem();
                case "offhand_item" -> bot.offhandItem();
                case "target_held_item" -> bot.targetHeldItem();
                case "target_name" -> bot.targetName();
                case "bot_name" -> bot.name();
                case "on_ground" -> bot.isOnGround();
                case "in_water" -> bot.isInWater();
                case "gliding" -> bot.isGliding();
                case "blocking" -> bot.isBlocking();
                case "using_item" -> bot.isUsingItem();
                case "sprinting" -> bot.isSprinting();
                case "sneaking" -> bot.isSneaking();
                case "alive" -> bot.isAlive();
                case "has_target" -> bot.hasTarget();
                case "fighting" -> bot.isFighting();
                case "target_blocking" -> bot.isTargetBlocking();
                default -> throw new ExpressionException("Nothing reads '" + name + "'", -1);
            };
        }

        @Override
        public Object call(String function, List<Object> args)
        {
            Bot bot = state.bot;
            return switch (function)
            {
                case "distance" -> bot.distanceTo((Double) args.get(0), (Double) args.get(1), (Double) args.get(2));
                case "player_distance" -> bot.distanceToPlayer((String) args.get(0));
                case "count" -> (double) bot.countItem((String) args.get(0));
                case "block" -> bot.blockAt((Double) args.get(0), (Double) args.get(1), (Double) args.get(2));
                case "entities" -> (double) bot.countEntities((String) args.get(0), (Double) args.get(1));
                default -> throw new ExpressionException("Nothing answers '" + function + "'", -1);
            };
        }

        @Override
        public void charge(int steps)
        {
            state.stepsLeft -= steps;
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
        if (program.getError() != null)
        {
            return "'" + program.getName() + "' does not run yet: " + program.getError();
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
            // A program that was stopped mid-fight leaves its bot standing still, not fighting.
            releaseBot(state, bot);
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
        releaseBot(state, bot);
        LOG.warn("Program '{}' on bot '{}' stopped: {}", state.program.getName(), botName, message);
        logListener.accept("ERROR", "Program '" + state.program.getName() + "' on " + botName + " stopped: " + message);
    }

    /**
     * Gives the bot back to whoever is driving it: the inputs the program held are released, and a fight it
     * started with a combat node is over. A program that never turned the combat AI on leaves it alone.
     */
    private void releaseBot(ProgramState state, Bot bot)
    {
        state.combatNodes = 0;
        if (state.combatStarted)
        {
            state.combatStarted = false;
            bot.stopCombat();
        }
        bot.stopAll();
    }

    private void tickProgram(ProgramState state, Bot bot, String botName)
    {
        state.bot = bot;
        state.stepsLeft = MAX_STEPS_PER_TICK;
        state.ticks++;
        Set<BotEvents.Event> events = bot.combatEvents();
        fireEvents(state, bot, botName, events);
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

        while (state.wait == null)
        {
            if (state.stepsLeft-- <= 0)
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
                releaseBot(state, bot);
                logListener.accept("INFO", "Program '" + state.program.getName() + "' completed");
                return;
            }
            Frame frame = state.stack.peek();
            if (frame.index >= frame.actions.size())
            {
                boolean again;
                if (frame.loop != null)
                {
                    again = schema.params(frame.loop, state.reader).truth("condition");
                }
                else if (frame.items != null)
                {
                    again = frame.items.hasNext();
                    if (again)
                    {
                        setVariable(state, frame.itemName, frame.items.next());
                    }
                }
                else
                {
                    again = frame.runsLeft == FOREVER || --frame.runsLeft > 0;
                }
                if (again)
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
            execute(action, state, bot, botName);
        }
    }

    /**
     * Hands the tick to the handlers whose event has just become true. Each one that starts takes over from the
     * main sequence, which is put back the way it was when the handler's actions are done. An event that
     * happens again while its own handler is still running is ignored.
     *
     * @param events what the game told the bot about its fight since the last tick
     */
    private void fireEvents(ProgramState state, Bot bot, String botName, Set<BotEvents.Event> events)
    {
        for (Handler handler : state.handlers)
        {
            boolean holds = holds(handler, state, bot, events);
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
     * took damage, its health went below the given value, its target went away, its target came into range, or
     * the game reported its target dead, its totem popping or a target coming into its hands.
     */
    private boolean holds(Handler handler, ProgramState state, Bot bot, Set<BotEvents.Event> events)
    {
        Params p = schema.params(handler.action, state.reader);
        String target = p.string("target");
        double value = p.number("value");
        return switch (handler.event)
        {
            case "when_hit" -> bot.health() < state.lastHealth;
            case "when_health_below" -> bot.health() < value;
            case "when_target_lost" -> Double.isInfinite(bot.distanceToPlayer(target));
            case "when_target_in_range" -> bot.distanceToPlayer(target) <= value;
            // a death and a totem are told by the game, so each of them holds for exactly one tick
            case "when_kill" -> events.contains(BotEvents.Event.KILL);
            case "when_totem_pop" -> events.contains(BotEvents.Event.TOTEM_POP);
            // the brain picking a target is its own decision, so this is the edge of it having one
            case "when_target_acquired" -> bot.hasTarget();
            default -> throw new IllegalStateException("No interpreter for event " + handler.event);
        };
    }

    private void execute(BotAction action, ProgramState state, Bot bot, String botName)
    {
        if (state.combatNodes > 0 && schema.definition(action).drivesBody())
        {
            // The combat brain is driving the action pack, so a step of the program that would drive it too is
            // left out. Its wait would be the wait of a fight, so the step does not happen at all this tick.
            if (!state.warnedAboutCombat)
            {
                state.warnedAboutCombat = true;
                logListener.accept("WARN", "Program '" + state.program.getName() + "' on " + botName + " wanted to " + action.getType()
                        + " while a combat node owned " + botName + ", so it was skipped. The brain drives the body until the node ends.");
            }
            return;
        }
        String requiredRule = schema.definition(action).requires();
        if (requiredRule != null)
        {
            bot.requireRule(requiredRule, state.owner);
        }
        Params p = schema.params(action, state.reader);
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

            case "COMBAT_START" -> startCombat(p, state, bot);
            case "COMBAT_STOP" -> stopCombat(state, bot);
            case "FIGHT" ->
            {
                startCombat(p, state, bot);
                double range = p.number("range");
                int rangeTicks = p.integer("rangeTicks");
                int[] outOfRangeFor = {0};
                Wait wait = new Wait();
                wait.ticksLeft = p.integer("timeout");
                wait.onEnd = b -> stopCombat(state, bot);
                wait.done = b ->
                {
                    // The fight is over once the target or the bot is gone, or once the target has stayed
                    // out of reach for as long as the node allows.
                    if (!b.isAlive() || !b.hasTarget()) return true;
                    outOfRangeFor[0] = b.targetDistance() <= range ? 0 : outOfRangeFor[0] + 1;
                    return outOfRangeFor[0] >= rangeTicks;
                };
                state.wait = wait;
            }
            case "SET_COMBAT_OPTION" -> bot.combatOption(p.string("key"), p.string("value"));
            case "GIVE_KIT" -> bot.giveKit(p.string("kit"));

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
            case "WAIT_FOR" ->
            {
                Wait wait = new Wait();
                wait.ticksLeft = p.integer("timeout");
                wait.done = b -> schema.params(action, state.reader).truth("condition");
                if (!wait.done.test(bot))
                {
                    state.wait = wait;
                }
            }
            case "SET" -> setVariable(state, p.string("name"), p.value("value"));
            case "FOR_EACH" ->
            {
                @SuppressWarnings("unchecked")
                Iterator<Object> items = ((List<Object>) p.value("list")).iterator();
                String name = p.string("variable");
                if (items.hasNext() && !action.getChildren().isEmpty())
                {
                    setVariable(state, name, items.next());
                    Frame body = new Frame(action.getChildren(), 1);
                    body.items = items;
                    body.itemName = name;
                    body.breakable = true;
                    state.stack.push(body);
                }
            }
            case "BREAK" -> leaveLoop(state, false);
            case "CONTINUE" -> leaveLoop(state, true);
            case "STOP_PROGRAM" ->
            {
                // Everything the program had open is closed: it ends as one that ran to its end does.
                state.stack.clear();
                state.handlers.clear();
            }
            case "IF" -> pushFrame(state, p.truth("condition") ? action.getChildren() : action.getElseChildren(), 1);
            case "WHILE" ->
            {
                if (action.getChildren().isEmpty())
                {
                    // Nothing to repeat: the program stays here for as long as the condition holds.
                    Wait wait = new Wait();
                    wait.done = b -> !schema.params(action, state.reader).truth("condition");
                    if (!wait.done.test(bot))
                    {
                        state.wait = wait;
                    }
                }
                else if (p.truth("condition"))
                {
                    Frame body = new Frame(action.getChildren(), 1);
                    body.loop = action;
                    body.breakable = true;
                    state.stack.push(body);
                }
            }
            case "SET_VARIABLE" -> setVariable(state, p.string("name"), p.number("value"));
            case "ADD_VARIABLE" -> setVariable(state, p.string("name"), variable(state, p.string("name")) + p.number("amount"));
            case "ON_EVENT" -> addHandler(action, state, bot);
            case "EXECUTE_COMMAND" -> bot.executeCommand(p.string("command"), state.owner);
            case "SCARPET" ->
            {
                Object result = runScript(p.string("code"), state, bot);
                String into = p.string("result");
                if (!into.isEmpty())
                {
                    setVariable(state, into, result);
                }
            }
            case "SEQUENCE" -> pushFrame(state, action.getChildren(), 1);
            case "LOOP" -> pushLoop(state, action.getChildren(), p.integer("count"));
            case "FOREVER" ->
            {
                if (action.getChildren().isEmpty())
                {
                    // Nothing to repeat: the program just never moves on.
                    state.wait = new Wait();
                }
                pushLoop(state, action.getChildren(), FOREVER);
            }
            case "IF_THEN_ELSE" -> pushFrame(state, test(action.getCondition(), bot, state) ? action.getChildren() : action.getElseChildren(), 1);

            default -> throw new IllegalStateException("No interpreter for action type " + action.getType());
        }
    }

    // Turns the bot's combat AI on with the settings the node carries, and takes the body away from the program.
    private void startCombat(Params p, ProgramState state, Bot bot)
    {
        bot.startCombat(p.string("style"), p.string("difficulty"), p.string("targets"), p.string("target"));
        state.combatNodes++;
        state.combatStarted = true;
    }

    // Closes one combat node. The AI stops once the last node that opened it is closed, so that a fight node
    // inside a handler does not end the fight the sequence started. A stop with none open turns the AI off
    // anyway, which is what a program that did not start the fight is asking for.
    private void stopCombat(ProgramState state, Bot bot)
    {
        if (state.combatNodes > 0) state.combatNodes--;
        if (state.combatNodes == 0)
        {
            state.combatStarted = false;
            bot.stopCombat();
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

    /**
     * Runs a Scarpet snippet for a program: the program's variables go in, and what the snippet leaves in them
     * comes back. Nothing here can stop a snippet that does not end; what it costs the tick is fixed.
     *
     * @return what the snippet evaluated to
     */
    private Object runScript(String code, ProgramState state, Bot bot)
    {
        state.stepsLeft -= SCRIPT_STEPS;
        // The names the snippet has for itself are not handed over: its player, its position and its loop variables.
        Map<String, Object> shared = new LinkedHashMap<>();
        state.variables.forEach((name, value) ->
        {
            if (!SCRIPT_OWN_NAMES.contains(name) && !name.startsWith("_") && !name.startsWith("global_"))
            {
                shared.put(name, value);
            }
        });
        List<Object> answer = bot.runScript(code, shared, state.owner);
        try
        {
            int index = 1;
            for (String name : shared.keySet())
            {
                if (index < answer.size())
                {
                    state.variables.put(name, Expression.limited(answer.get(index++)));
                }
            }
            return Expression.limited(answer.getFirst());
        }
        catch (ExpressionException e)
        {
            throw new BotActionException("SCARPET gave back more than a program can hold: " + e.getMessage());
        }
    }

    private static final Set<String> SCRIPT_OWN_NAMES = Set.of("p", "x", "y", "z");

    // What a snippet's result means as a condition: false for false, 0, no text and an empty list.
    private static boolean truthy(Object value)
    {
        return switch (value)
        {
            case Boolean bool -> bool;
            case Double number -> number != 0.0D;
            case String text -> !text.isEmpty();
            case List<?> list -> !list.isEmpty();
            default -> true;
        };
    }

    private static void pushLoop(ProgramState state, List<BotAction> actions, int runs)
    {
        pushFrame(state, actions, runs);
        if (!actions.isEmpty() && runs != 0)
        {
            state.stack.peek().breakable = true;
        }
    }

    /**
     * BREAK and CONTINUE: everything that was begun inside the innermost loop is given up. To break, the loop
     * goes with it; to continue, its body is taken as done, so that the loop decides about another round.
     */
    private static void leaveLoop(ProgramState state, boolean nextRound)
    {
        while (!state.stack.isEmpty() && !state.stack.peek().breakable)
        {
            state.stack.pop();
        }
        if (state.stack.isEmpty())
        {
            return;
        }
        if (nextRound)
        {
            Frame loop = state.stack.peek();
            loop.index = loop.actions.size();
        }
        else
        {
            state.stack.pop();
        }
    }

    /** What a variable reads as where a number is needed: 0 for one the program never set. */
    private static double variable(ProgramState state, String name)
    {
        Object value = state.variables.get(name);
        if (value != null && !(value instanceof Double))
        {
            throw new BotActionException("The variable '" + name + "' holds " + Expression.typeOf(value).words() + ", not a number");
        }
        return value == null ? 0.0 : (Double) value;
    }

    private void setVariable(ProgramState state, String name, Object value)
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
        handler.event = schema.params(event, state.reader).string("event");
        if (state.handlers.isEmpty())
        {
            state.lastHealth = bot.health();
        }
        handler.wasTrue = holds(handler, state, bot, Set.of());
        state.handlers.add(handler);
    }

    private boolean test(BotAction condition, Bot bot, ProgramState state)
    {
        Params p = schema.params(condition, state.reader);
        return switch (condition.getType())
        {
            case "CONDITION_EXPRESSION" -> p.truth("expression");
            case "CONDITION_SCARPET" -> truthy(runScript(p.string("code"), state, bot));
            case "CONDITION_ALL" -> condition.getConditions().stream().allMatch(each -> test(each, bot, state));
            case "CONDITION_ANY" -> condition.getConditions().stream().anyMatch(each -> test(each, bot, state));
            case "CONDITION_NOT" -> !test(condition.getCondition(), bot, state);
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
            case "CONDITION_IS_FIGHTING" -> bot.isFighting();
            case "CONDITION_HAS_TARGET" -> bot.hasTarget();
            case "CONDITION_TARGET_DISTANCE" -> compare(bot.targetDistance(), p.string("operator"), p.number("value"));
            case "CONDITION_TARGET_HEALTH" -> compare(bot.targetHealth(), p.string("operator"), p.number("value"));
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

    /**
     * @return what a variable of the program on a bot holds, written out as text, or null when the program
     *         never set it or the bot runs none
     */
    public String variable(String botName, String name)
    {
        ProgramState state = programs.get(botName);
        Object value = state == null ? null : state.variables.get(name);
        return value == null ? null : Expression.format(value);
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
