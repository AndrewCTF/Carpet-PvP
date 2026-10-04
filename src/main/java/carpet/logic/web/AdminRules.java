package carpet.logic.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * What the admin sign-in and the Settings panel need from the running game: who may change Carpet rules, and
 * the rules themselves. {@link CarpetAdminRules} answers from the server; a test answers on its own.
 */
public interface AdminRules
{
    /** A player account, by the name the server knows it under. */
    record Account(UUID id, String name)
    {
    }

    /**
     * @param status 200 when the rule was changed, otherwise the HTTP status of the refusal
     * @param message why it was refused, or what the rule said when it took the value; may be null
     * @param rule the rule as {@link #rules()} lists it, after the change
     */
    record Change(int status, String message, JsonObject rule)
    {
    }

    /** Whether the carpetLogicAdminLogin rule is on. */
    boolean loginEnabled();

    boolean viewerMode();

    long sessionMillis();

    /**
     * @return the account of that name if it may change Carpet rules, online or not, otherwise null
     */
    Account admin(String name);

    boolean isAdmin(UUID id);

    /** The rules the Settings panel shows, each with its type, options, description and current value. */
    JsonArray rules();

    /**
     * @return why no rule can be changed at all, or null when they can
     */
    String locked();

    /** Changes one of {@link #rules()} on behalf of an admin session. */
    Change set(String rule, String value, AuthManager.Session session);
}
