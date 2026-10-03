package carpet.logic;

import carpet.CarpetExtension;
import carpet.CarpetSettings;
import carpet.logic.bot.BotManager;
import carpet.logic.program.ActionSchema;
import carpet.logic.program.ProgramExecutor;
import carpet.logic.program.ProgramStorage;
import carpet.logic.web.Api;
import carpet.logic.web.AuthManager;
import carpet.logic.web.WebServer;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;

/**
 * Bot programming: a node editor compiles a graph to an action tree, and a tick-based interpreter
 * replays that tree on fake players.
 */
public class CarpetLogic implements CarpetExtension
{
    public static final CarpetLogic INSTANCE = new CarpetLogic();

    private final ActionSchema schema = ActionSchema.load();
    private BotManager botManager;
    private ProgramExecutor programExecutor;
    private ProgramStorage programStorage;
    private final AuthManager auth = new AuthManager();
    private WebServer webServer;

    private CarpetLogic()
    {
    }

    @Override
    public void onServerLoaded(MinecraftServer server)
    {
        programStorage = new ProgramStorage(server.getWorldPath(LevelResource.ROOT).resolve("carpetlogic").resolve("programs"), schema);
        programStorage.loadAll();
        botManager = new BotManager(server);
        programExecutor = new ProgramExecutor(schema, botManager::getController, () -> CarpetSettings.carpetLogicMaxPrograms);
        programExecutor.setLogListener((level, message) ->
        {
            if (webServer != null)
            {
                webServer.log(level, message);
            }
        });
        startWebServer(server);
    }

    private void startWebServer(MinecraftServer server)
    {
        try
        {
            Class.forName("com.sun.net.httpserver.HttpServer");
        }
        catch (ClassNotFoundException | LinkageError e)
        {
            CarpetSettings.LOG.warn("CarpetLogic web editor disabled: this Java runtime lacks the jdk.httpserver module. "
                    + "Bot programs can still be run with /carpetlogic.");
            return;
        }
        String address = CarpetSettings.carpetLogicBindAddress;
        int port = CarpetSettings.carpetLogicPort;
        try
        {
            webServer = new WebServer(server, auth, new Api(server, this), address, port);
            CarpetSettings.LOG.info("CarpetLogic web editor listening on {}. Run /carpetlogic open for a link.", webServer.url());
        }
        catch (IOException e)
        {
            CarpetSettings.LOG.error("CarpetLogic web editor could not listen on {}:{}: {}", address, port, e.toString());
        }
    }

    @Override
    public void onTick(MinecraftServer server)
    {
        if (programExecutor != null)
        {
            programExecutor.tick();
        }
        if (webServer != null)
        {
            webServer.tick();
        }
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext)
    {
        CarpetLogicCommand.register(dispatcher);
    }

    @Override
    public void onServerClosed(MinecraftServer server)
    {
        if (webServer != null)
        {
            webServer.stop();
            webServer = null;
        }
        auth.clear();
        if (programExecutor != null)
        {
            programExecutor.stopAll();
        }
        botManager = null;
        programExecutor = null;
        programStorage = null;
    }

    @Override
    public void onReload(MinecraftServer server)
    {
        if (programStorage != null)
        {
            programStorage.loadAll();
        }
    }

    public BotManager getBotManager()
    {
        return botManager;
    }

    public ProgramExecutor getProgramExecutor()
    {
        return programExecutor;
    }

    public ProgramStorage getProgramStorage()
    {
        return programStorage;
    }

    public ActionSchema getSchema()
    {
        return schema;
    }

    public AuthManager getAuth()
    {
        return auth;
    }

    public WebServer getWebServer()
    {
        return webServer;
    }
}
