package carpet.logic;

import carpet.CarpetSettings;
import carpet.logic.program.BotProgram;
import carpet.logic.web.AdminLogin;
import carpet.logic.web.AdminRules;
import carpet.logic.web.WebServer;
import carpet.patches.EntityPlayerMPFake;
import carpet.utils.CommandHelper;
import carpet.utils.Messenger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class CarpetLogicCommand
{
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
    {
        dispatcher.register(literal("carpetlogic")
                .requires(source -> CommandHelper.canUseCommand(source, CarpetSettings.commandCarpetLogic))
                .executes(CarpetLogicCommand::showStatus)
                .then(literal("status").executes(CarpetLogicCommand::showStatus))
                .then(literal("open").executes(CarpetLogicCommand::open))
                .then(literal("password")
                        .executes(ctx -> passwordLink(ctx, null))
                        .then(argument("player", StringArgumentType.word())
                                .executes(ctx -> passwordLink(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(literal("programs")
                        .executes(CarpetLogicCommand::listPrograms)
                        .then(literal("run")
                                .then(argument("program", StringArgumentType.string())
                                        .then(argument("bot", StringArgumentType.word())
                                                .executes(CarpetLogicCommand::runProgram))))
                        .then(literal("stop")
                                .then(argument("bot", StringArgumentType.word())
                                        .executes(CarpetLogicCommand::stopProgram))))
                .then(literal("bots").executes(CarpetLogicCommand::listBots)));
    }

    private static boolean notReady(CommandContext<CommandSourceStack> ctx)
    {
        if (CarpetLogic.INSTANCE.getProgramExecutor() == null)
        {
            Messenger.m(ctx.getSource(), "r CarpetLogic is not initialized");
            return true;
        }
        return false;
    }

    private static int open(CommandContext<CommandSourceStack> ctx)
    {
        CommandSourceStack source = ctx.getSource();
        WebServer web = CarpetLogic.INSTANCE.getWebServer();
        if (web == null)
        {
            Messenger.m(source, "r The web editor is not running; the server log says why");
            return 0;
        }
        // The link is only ever shown to whoever its token is bound to. In particular it does not go back to
        // the sender of an "/execute as <player> run carpetlogic open", and it is not broadcast to operators.
        long lifetime = CarpetSettings.carpetLogicSessionHours * 3_600_000L;
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayer player && !(player instanceof EntityPlayerMPFake))
        {
            String token = CarpetLogic.INSTANCE.getAuth().issue(player.getUUID(), player.getGameProfile().name(), lifetime);
            player.sendSystemMessage(Messenger.c("w Bot editor: ", "cu open in browser", "@" + web.url() + "/#token=" + token,
                    "g  (for you only, valid " + CarpetSettings.carpetLogicSessionHours + "h)"));
            return 1;
        }
        if (entity == null && CommandHelper.hasPermissionLevel(source, 4))
        {
            String token = CarpetLogic.INSTANCE.getAuth().issue(null, source.getTextName(), lifetime);
            source.sendSuccess(() -> Messenger.c("w Bot editor: " + web.url() + "/#token=" + token), false);
            return 1;
        }
        Messenger.m(source, "r Only a player or the server console can open the web editor");
        return 0;
    }

    // The password of an editor admin is typed in the browser and nowhere else. What the game gives out is a
    // link to the page that sets it, to the admin it is for: in their own chat, or on the console for the
    // console to pass on.
    private static int passwordLink(CommandContext<CommandSourceStack> ctx, String named)
    {
        CommandSourceStack source = ctx.getSource();
        WebServer web = CarpetLogic.INSTANCE.getWebServer();
        if (web == null)
        {
            Messenger.m(source, "r The web editor is not running; the server log says why");
            return 0;
        }
        if (!CarpetSettings.carpetLogicAdminLogin)
        {
            Messenger.m(source, "r The editor's admin sign-in is off. Turn it on with /carpet carpetLogicAdminLogin true");
            return 0;
        }
        AdminRules rules = CarpetLogic.INSTANCE.getAdminRules();
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayer player && !(player instanceof EntityPlayerMPFake))
        {
            String own = player.getGameProfile().name();
            if (named != null && !named.equalsIgnoreCase(own))
            {
                Messenger.m(source, "r Only the server console can make a password link for somebody else");
                return 0;
            }
            AdminRules.Account account = rules.admin(own);
            if (account == null || !account.id().equals(player.getUUID()))
            {
                Messenger.m(source, "r Only players who may change Carpet rules can be editor admins");
                return 0;
            }
            player.sendSystemMessage(Messenger.c("w Bot editor admin: ", "cu set your password", "@" + passwordUrl(web, account),
                    "g  (for you only, works once, valid " + AdminLogin.LINK_MINUTES + " min)"));
            return 1;
        }
        if (entity == null && CommandHelper.hasPermissionLevel(source, 4))
        {
            if (named == null)
            {
                Messenger.m(source, "r Name the admin the link is for: /carpetlogic password <player>");
                return 0;
            }
            AdminRules.Account account = rules.admin(named);
            if (account == null)
            {
                Messenger.m(source, "r " + named + " may not change Carpet rules, so cannot be an editor admin. Op them first");
                return 0;
            }
            source.sendSuccess(() -> Messenger.c("w Bot editor admin password for " + account.name() + ", works once, valid "
                    + AdminLogin.LINK_MINUTES + " min: " + passwordUrl(web, account)), false);
            return 1;
        }
        Messenger.m(source, "r Only a player or the server console can ask for a password link");
        return 0;
    }

    private static String passwordUrl(WebServer web, AdminRules.Account account)
    {
        String ticket = CarpetLogic.INSTANCE.getAdminLogin().link(account.id(), account.name());
        return web.url() + "/#setup=" + ticket + "&name=" + URLEncoder.encode(account.name(), StandardCharsets.UTF_8);
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx)
    {
        if (notReady(ctx)) return 0;
        CarpetLogic logic = CarpetLogic.INSTANCE;
        WebServer web = logic.getWebServer();
        Messenger.m(ctx.getSource(), "w Web editor: ", web == null ? "r not running" : "l " + web.url());
        Messenger.m(ctx.getSource(), "w CarpetLogic: ",
                "y " + logic.getBotManager().getBots().size(), "w  bots, ",
                "y " + logic.getProgramExecutor().getRunningCount(), "w  running programs, ",
                "y " + logic.getProgramStorage().getCount(), "w  saved programs");
        return 1;
    }

    private static int listPrograms(CommandContext<CommandSourceStack> ctx)
    {
        if (notReady(ctx)) return 0;
        List<BotProgram> programs = CarpetLogic.INSTANCE.getProgramStorage().getAllPrograms();
        if (programs.isEmpty())
        {
            Messenger.m(ctx.getSource(), "y No saved programs");
            return 1;
        }
        Messenger.m(ctx.getSource(), "g Programs are kept in " + CarpetLogic.INSTANCE.getProgramStorage().location());
        for (BotProgram program : programs)
        {
            String name = (program.getFolder().isEmpty() ? "" : program.getFolder() + "/") + program.getName();
            Messenger.m(ctx.getSource(), "w  - " + name, program.getError() == null
                    ? "g  (" + program.getActionCount() + " actions)" : "y  (does not run yet: " + program.getError() + ")");
        }
        return programs.size();
    }

    private static int runProgram(CommandContext<CommandSourceStack> ctx)
    {
        if (notReady(ctx)) return 0;
        String programName = StringArgumentType.getString(ctx, "program");
        String botName = StringArgumentType.getString(ctx, "bot");
        BotProgram program = CarpetLogic.INSTANCE.getProgramStorage().getByName(programName);
        if (program == null)
        {
            Messenger.m(ctx.getSource(), "r Program '" + programName + "' not found");
            return 0;
        }
        // No owner: commands inside a program only run for the player whose editor started it.
        String refused = CarpetLogic.INSTANCE.getProgramExecutor().startProgram(botName, program, null);
        if (refused != null)
        {
            Messenger.m(ctx.getSource(), "r " + refused);
            return 0;
        }
        Messenger.m(ctx.getSource(), "g Started '" + programName + "' on bot '" + botName + "'");
        return 1;
    }

    private static int stopProgram(CommandContext<CommandSourceStack> ctx)
    {
        if (notReady(ctx)) return 0;
        String botName = StringArgumentType.getString(ctx, "bot");
        if (!CarpetLogic.INSTANCE.getProgramExecutor().stopProgram(botName))
        {
            Messenger.m(ctx.getSource(), "r No program running on bot '" + botName + "'");
            return 0;
        }
        Messenger.m(ctx.getSource(), "y Stopped program on bot '" + botName + "'");
        return 1;
    }

    private static int listBots(CommandContext<CommandSourceStack> ctx)
    {
        if (notReady(ctx)) return 0;
        List<ServerPlayer> bots = CarpetLogic.INSTANCE.getBotManager().getBots();
        if (bots.isEmpty())
        {
            Messenger.m(ctx.getSource(), "y No active bots");
            return 1;
        }
        for (ServerPlayer bot : bots)
        {
            Messenger.m(ctx.getSource(), "w  - " + bot.getGameProfile().name(),
                    "g  " + String.format("[HP:%.0f Pos:%.0f,%.0f,%.0f]", bot.getHealth(), bot.getX(), bot.getY(), bot.getZ()));
        }
        return bots.size();
    }
}
