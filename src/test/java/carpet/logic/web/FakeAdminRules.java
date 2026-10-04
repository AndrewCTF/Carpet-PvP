package carpet.logic.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A server small enough for a test: a few accounts that may change rules, and two rules. The real answers
 * come from {@link CarpetAdminRules}, which needs a running game and is checked by the self-test instead.
 */
class FakeAdminRules implements AdminRules
{
    boolean login = true;
    boolean viewer = false;
    String locked;
    long sessionMillis = 3_600_000L;
    final Map<String, Account> admins = new LinkedHashMap<>();
    final Map<String, String> values = new LinkedHashMap<>(Map.of("carpetLogicMaxPrograms", "4", "carpetLogicViewerMode", "false"));
    /** Who changed what, in order, as "name rule=value". */
    final List<String> changes = new ArrayList<>();
    int lookups;

    Account op(String name)
    {
        Account account = new Account(UUID.randomUUID(), name);
        admins.put(name.toLowerCase(), account);
        return account;
    }

    void deop(String name)
    {
        admins.remove(name.toLowerCase());
    }

    @Override
    public boolean loginEnabled()
    {
        return login;
    }

    @Override
    public boolean viewerMode()
    {
        return viewer;
    }

    @Override
    public long sessionMillis()
    {
        return sessionMillis;
    }

    @Override
    public Account admin(String name)
    {
        lookups++;
        return admins.get(name.toLowerCase());
    }

    @Override
    public boolean isAdmin(UUID id)
    {
        return admins.values().stream().anyMatch(account -> account.id().equals(id));
    }

    @Override
    public JsonArray rules()
    {
        JsonArray rules = new JsonArray();
        values.keySet().forEach(name -> rules.add(describe(name)));
        return rules;
    }

    private JsonObject describe(String name)
    {
        JsonObject rule = new JsonObject();
        rule.addProperty("name", name);
        rule.addProperty("value", values.get(name));
        return rule;
    }

    @Override
    public String locked()
    {
        return locked;
    }

    @Override
    public Change set(String rule, String value, AuthManager.Session session)
    {
        if (!values.containsKey(rule))
        {
            return new Change(404, "The editor has no setting named '" + rule + "'", null);
        }
        if (locked != null)
        {
            return new Change(409, locked, describe(rule));
        }
        boolean fits = rule.equals("carpetLogicViewerMode") ? value.equals("true") || value.equals("false") : value.matches("\\d+");
        if (!fits)
        {
            return new Change(400, "Wrong value for " + rule + ": " + value, describe(rule));
        }
        values.put(rule, value);
        if (rule.equals("carpetLogicViewerMode"))
        {
            viewer = Boolean.parseBoolean(value);
        }
        changes.add(session.ownerName() + " " + rule + "=" + value);
        return new Change(200, null, describe(rule));
    }
}
