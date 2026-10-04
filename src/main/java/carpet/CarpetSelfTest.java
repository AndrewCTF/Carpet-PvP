package carpet;

import carpet.logic.CarpetLogic;
import carpet.logic.program.BotAction;
import carpet.logic.program.BotProgram;
import carpet.logic.program.ProgramExecutor.ProgramInfo;
import carpet.logic.web.Api;
import carpet.logic.web.AuthManager;
import carpet.logic.web.WebServer;
import carpet.pvp.selftest.SelfTest;
import carpet.utils.SpawnReporter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Feeds the self-test the parts of Carpet it cannot reach on its own: starting and inspecting
 * CarpetLogic programs, the web panel's bot snapshot, the explosion position cache and the spawn
 * reporter. The Paper plugin reports the scenarios that need them as unsupported instead.
 */
public final class CarpetSelfTest
{
    private static final Gson GSON = new Gson();
    /** A session that was never issued to a player, as /carpetlogic from the console makes. */
    private static final AuthManager.Session CONSOLE_SESSION = new AuthManager.Session(null, "Server", Long.MAX_VALUE);

    private CarpetSelfTest() {}

    /** Called from {@link CarpetServer#onServerLoaded}, once the registries are bound. */
    public static void hook()
    {
        // A program is started through the Java API, the way the web editor's execute endpoint starts
        // one, and never as the console: a program started from a command has no player to run its
        // commands as.
        SelfTest.programStarter = (server, botName) -> {
            CarpetLogic logic = CarpetLogic.INSTANCE;
            BotProgram program = new BotProgram("_selftest", "selftest", "");
            program.setActions(GSON.fromJson(SelfTest.pendingProgram(), new TypeToken<List<BotAction>>() {}.getType()));
            logic.getSchema().validate(program.getActions());
            String refused = logic.getProgramExecutor().startProgram(botName, program, null);
            if (refused != null)
            {
                server.sendSystemMessage(Component.literal("[selftest] could not start the program on " + botName + ": " + refused));
            }
        };
        SelfTest.programStatus = botName -> {
            ProgramInfo info = CarpetLogic.INSTANCE.getProgramExecutor().getPrograms().get(botName);
            return info == null ? "gone" : info.status();
        };
        SelfTest.programStopper = botName -> CarpetLogic.INSTANCE.getProgramExecutor().stopProgram(botName);
        SelfTest.programError = botName -> {
            ProgramInfo info = CarpetLogic.INSTANCE.getProgramExecutor().getPrograms().get(botName);
            return info == null ? null : info.error();
        };
        SelfTest.botSnapshot = (server, name) -> new Api(server, CarpetLogic.INSTANCE)
                .handle("GET", "/api/bots", CONSOLE_SESSION, "").body().toString();
        SelfTest.consoleSays = (server, command) -> {
            List<String> lines = new ArrayList<>();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(new CommandSource()
            {
                @Override
                public void sendSystemMessage(Component message)
                {
                    lines.add(message.getString());
                }

                @Override
                public boolean acceptsSuccess()
                {
                    return true;
                }

                @Override
                public boolean acceptsFailure()
                {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins()
                {
                    return false;
                }
            }), command);
            return String.join(" | ", lines);
        };
        SelfTest.webEditorUrl = () -> {
            WebServer web = CarpetLogic.INSTANCE.getWebServer();
            return web == null ? null : web.url();
        };
        SelfTest.programVariable = (botName, name) -> {
            String value = CarpetLogic.INSTANCE.getProgramExecutor().variable(botName, name);
            return value == null ? "unset" : value;
        };
        SelfTest.reloadPrograms = () -> CarpetLogic.INSTANCE.getProgramStorage().loadAll();
        SelfTest.spawnAttempts = () -> SpawnReporter.spawn_attempts.isEmpty() ? 0L
                : SpawnReporter.spawn_attempts.values().stream().mapToLong(Long::longValue).sum();
        SelfTest.explosionPositionLeaver = CarpetSelfTest::queueLeftoverPositions;
        SelfTest.ruleSnapshot = CarpetAutoSetup::everyRule;
        SelfTest.ruleRestore = CarpetSelfTest::putRulesBack;
    }

    /**
     * Puts one rule back with the {@code /carpet} command, so that everything watching rules sees it.
     *
     * @return whether the rule was out of step and has been set again
     */
    private static List<String> putRulesBack(Map<String, String> before)
    {
        List<String> restored = new ArrayList<>();
        for (Map.Entry<String, String> rule : before.entrySet())
        {
            if (Objects.equals(CarpetAutoSetup.value(rule.getKey()), rule.getValue())) continue;
            if (CarpetAutoSetup.set(rule.getKey(), rule.getValue())) restored.add(rule.getKey());
        }
        return restored;
    }

    /**
     * Puts a block into the position set carpet.helpers.OptimizedExplosion carries from one explosion
     * to the next, the way an explosion that skipped the walk would. Read and written by reflection,
     * the way the self-test's watchdog reads the player tracker: nothing outside the helper has any
     * business there.
     */
    private static void queueLeftoverPositions(net.minecraft.core.BlockPos leftover)
    {
        try
        {
            java.lang.reflect.Field field = carpet.helpers.OptimizedExplosion.class.getDeclaredField("affectedBlockPositionsSet");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Collection<net.minecraft.core.BlockPos> positions =
                    (java.util.Collection<net.minecraft.core.BlockPos>) field.get(null);
            positions.clear();
            positions.add(leftover.immutable());
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("could not reach the explosion position cache", e);
        }
    }
}
