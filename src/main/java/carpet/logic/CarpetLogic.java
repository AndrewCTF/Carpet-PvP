package carpet.logic;

import carpet.CarpetExtension;
import carpet.CarpetSettings;
import carpet.logic.bot.BotManager;
import carpet.logic.program.ProgramExecutor;
import carpet.logic.program.ProgramStorage;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Bot programming: a node editor compiles a graph to an action tree, and a tick-based interpreter
 * replays that tree on fake players.
 */
public class CarpetLogic implements CarpetExtension
{
    public static final CarpetLogic INSTANCE = new CarpetLogic();

    private BotManager botManager;
    private ProgramExecutor programExecutor;
    private ProgramStorage programStorage;

    private CarpetLogic()
    {
    }

    @Override
    public void onServerLoaded(MinecraftServer server)
    {
        programStorage = new ProgramStorage(server.getWorldPath(LevelResource.ROOT).resolve("carpetlogic").resolve("programs"));
        programStorage.loadAll();
        botManager = new BotManager(server);
        programExecutor = new ProgramExecutor(botManager, () -> CarpetSettings.carpetLogicMaxPrograms);
    }

    @Override
    public void onTick(MinecraftServer server)
    {
        if (programExecutor != null)
        {
            programExecutor.tick();
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
}
