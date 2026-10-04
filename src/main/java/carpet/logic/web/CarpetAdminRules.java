package carpet.logic.web;

import carpet.CarpetServer;
import carpet.CarpetSettings;
import carpet.api.settings.CarpetRule;
import carpet.api.settings.InvalidRuleValueException;
import carpet.api.settings.RuleCategory;
import carpet.api.settings.RuleHelper;
import carpet.api.settings.SettingsManager;
import carpet.logic.CarpetLogic;
import carpet.logic.program.ActionSchema;
import carpet.utils.CommandHelper;
import carpet.utils.Messenger;
import carpet.utils.TranslationKeys;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static carpet.utils.Translations.tr;

/**
 * {@link AdminRules} of a running server. An admin is whoever {@code /carpet <rule> <value>} would obey, and a
 * rule is changed the way that command changes it.
 */
public class CarpetAdminRules implements AdminRules
{
    /** The groups of the Settings panel, in the order it shows them. */
    private static final List<String> GROUPS = List.of("editor", "actions", "bots");
    private static final int SERVER_THREAD_TIMEOUT_SECONDS = 5;

    private final MinecraftServer server;
    private final CarpetLogic logic;

    public CarpetAdminRules(MinecraftServer server, CarpetLogic logic)
    {
        this.server = server;
        this.logic = logic;
    }

    @Override
    public boolean loginEnabled()
    {
        return CarpetSettings.carpetLogicAdminLogin;
    }

    @Override
    public boolean viewerMode()
    {
        return CarpetSettings.carpetLogicViewerMode;
    }

    @Override
    public long sessionMillis()
    {
        return CarpetSettings.carpetLogicSessionHours * 3_600_000L;
    }

    @Override
    public Account admin(String name)
    {
        return onServerThread(() ->
        {
            ServerPlayer online = server.getPlayerList().getPlayerByName(name);
            NameAndId who = online != null ? online.nameAndId() : operator(user -> user.name().equalsIgnoreCase(name));
            return who != null && mayChangeRules(who) ? new Account(who.id(), who.name()) : null;
        }, null);
    }

    @Override
    public boolean isAdmin(UUID id)
    {
        return onServerThread(() ->
        {
            NameAndId who = account(id);
            return who != null && mayChangeRules(who);
        }, false);
    }

    private NameAndId account(UUID id)
    {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        return online != null ? online.nameAndId() : operator(user -> user.id().equals(id));
    }

    // The op list is where the server keeps who may do what while they are away.
    private NameAndId operator(Predicate<NameAndId> wanted)
    {
        for (ServerOpListEntry entry : server.getPlayerList().getOps().getEntries())
        {
            if (entry.getUser() != null && wanted.test(entry.getUser()))
            {
                return entry.getUser();
            }
        }
        return null;
    }

    // The check /carpet makes of whoever types it, made of the permissions the account has.
    private boolean mayChangeRules(NameAndId who)
    {
        return CommandHelper.canUseCommand(as(who), CarpetSettings.carpetCommandPermissionLevel);
    }

    private CommandSourceStack as(NameAndId who)
    {
        return server.createCommandSourceStack().withPermission(server.getProfilePermissions(who));
    }

    private <T> T onServerThread(Supplier<T> work, T otherwise)
    {
        if (server.isSameThread())
        {
            return work.get();
        }
        try
        {
            return server.submit(work).get(SERVER_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return otherwise;
        }
        catch (ExecutionException | TimeoutException e)
        {
            return otherwise;
        }
    }

    @SuppressWarnings("removal") // the one field carpet still keeps of its own settings manager
    private static SettingsManager manager()
    {
        return CarpetServer.settingsManager;
    }

    /**
     * Which group of the panel a rule belongs to, or null for a rule the panel does not show: the editor's own
     * rules, the rules an action of the schema needs, and the defaults of the bots' combat AI.
     */
    private String group(CarpetRule<?> rule)
    {
        String name = rule.name();
        if (name.startsWith("carpetLogic") || name.equals("commandCarpetLogic"))
        {
            return "editor";
        }
        for (ActionSchema.Definition action : logic.getSchema().definitions())
        {
            if (name.equals(action.requires()))
            {
                return "actions";
            }
        }
        return rule.categories().contains(RuleCategory.PVP) ? "bots" : null;
    }

    @Override
    public JsonArray rules()
    {
        List<CarpetRule<?>> shown = new ArrayList<>();
        for (CarpetRule<?> rule : manager().getCarpetRules())
        {
            if (group(rule) != null)
            {
                shown.add(rule);
            }
        }
        shown.sort(Comparator.<CarpetRule<?>>comparingInt(rule -> GROUPS.indexOf(group(rule))).thenComparing(CarpetRule::name));
        JsonArray rules = new JsonArray();
        shown.forEach(rule -> rules.add(describe(rule)));
        return rules;
    }

    private JsonObject describe(CarpetRule<?> rule)
    {
        JsonObject described = new JsonObject();
        described.addProperty("name", rule.name());
        described.addProperty("group", group(rule));
        described.addProperty("type", type(rule.type()));
        described.addProperty("value", RuleHelper.toRuleString(rule.value()));
        described.addProperty("default", RuleHelper.toRuleString(rule.defaultValue()));
        described.addProperty("strict", rule.strict());
        described.addProperty("description", RuleHelper.translatedDescription(rule));
        JsonArray options = new JsonArray();
        rule.suggestions().forEach(options::add);
        described.add("options", options);
        JsonArray extra = new JsonArray();
        rule.extraInfo().forEach(line -> extra.add(line.getString()));
        described.add("extra", extra);
        return described;
    }

    private static String type(Class<?> type)
    {
        if (type == Boolean.class)
        {
            return "boolean";
        }
        if (type == Integer.class || type == Long.class)
        {
            return "int";
        }
        return type == Double.class || type == Float.class ? "number" : "string";
    }

    @Override
    public String locked()
    {
        return manager().locked() ? "Carpet's settings are locked in " + manager().identifier()
                + ".conf, so no rule can be changed while the server runs" : null;
    }

    @Override
    public Change set(String name, String value, AuthManager.Session session)
    {
        CarpetRule<?> rule = manager().getCarpetRule(name);
        if (rule == null || group(rule) == null)
        {
            return new Change(404, "The editor has no setting named '" + name + "'", null);
        }
        if (locked() != null)
        {
            return new Change(409, locked(), describe(rule));
        }
        NameAndId who = account(session.owner());
        if (who == null)
        {
            return new Change(403, "This account may no longer change Carpet rules", describe(rule));
        }
        // What the rule tells whoever changes it is collected for the editor, and goes to the operators and
        // the console as it does when the change is typed.
        List<String> said = new ArrayList<>();
        ServerPlayer online = server.getPlayerList().getPlayer(session.owner());
        CommandSourceStack source = (online != null ? online.createCommandSourceStack() : as(who)).withSource(new CommandSource()
        {
            @Override
            public void sendSystemMessage(Component message)
            {
                said.add(message.getString());
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
                return true;
            }
        });
        String before = RuleHelper.toRuleString(rule.value());
        try
        {
            rule.set(source, value);
        }
        catch (InvalidRuleValueException e)
        {
            String reason = e.getMessage() != null ? e.getMessage()
                    : said.isEmpty() ? "'" + value + "' is not a value this rule takes" : String.join(". ", said);
            return new Change(400, reason, describe(rule));
        }
        String heard = said.isEmpty() ? null : String.join(". ", said);
        String after = RuleHelper.toRuleString(rule.value());
        CarpetSettings.LOG.info("[CarpetLogic] {} changed the rule {} from {} to {} in the web editor",
                who.name(), rule.name(), before, after);
        String identifier = manager().identifier();
        Messenger.m(source, "w " + rule + ", ", "g set by " + who.name() + " in the web editor, ",
                "c [" + tr(TranslationKeys.CHANGE_PERMANENTLY) + "?]",
                "^w " + String.format(tr(TranslationKeys.CHANGE_PERMANENTLY_HOVER), identifier + ".conf"),
                "?/" + identifier + " setDefault " + rule.name() + " " + after);
        return new Change(200, heard, describe(rule));
    }
}
