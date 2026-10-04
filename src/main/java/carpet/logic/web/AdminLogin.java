package carpet.logic.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The admin sign-in of the web editor: a name and a web password give a session that may change rules.
 * A password is only ever set in the browser, through a link the game hands to the admin it is for; it is
 * never typed in chat or at the console. Both routes here are open to anybody who can reach the editor, so
 * neither says more than it must and both are throttled.
 */
public class AdminLogin
{
    private static final Logger LOG = LogManager.getLogger("CarpetLogic");
    public static final int LINK_MINUTES = 10;
    private static final int MAX_LINKS = 64;
    private static final int MAX_NAME_LENGTH = 32;
    // A hash takes a core for a moment. Two at a time is plenty for people signing in and leaves the rest of
    // the machine to the game, however many requests arrive.
    private static final int HASHES_AT_ONCE = 2;
    private static final int HASH_WAIT_SECONDS = 10;
    private static final String WRONG = "Wrong name or password";
    private static final String STALE_LINK = "This link is no longer valid. Ask for a new one with /carpetlogic password";

    private record Link(UUID id, String name, long expiresAt)
    {
    }

    private final AdminRules rules;
    private final AuthManager auth;
    private final PasswordStore passwords;
    private final LongSupplier clock;
    private final LoginThrottle throttle = new LoginThrottle();
    private final Semaphore hashing = new Semaphore(HASHES_AT_ONCE);
    // Keyed by the SHA-256 of the ticket, in order of issue, so the ticket itself is never kept.
    private final Map<String, Link> links = new LinkedHashMap<>();

    public AdminLogin(AdminRules rules, AuthManager auth, PasswordStore passwords)
    {
        this(rules, auth, passwords, System::currentTimeMillis);
    }

    AdminLogin(AdminRules rules, AuthManager auth, PasswordStore passwords, LongSupplier clock)
    {
        this.rules = rules;
        this.auth = auth;
        this.passwords = passwords;
        this.clock = clock;
    }

    public boolean enabled()
    {
        return rules.loginEnabled();
    }

    /**
     * Makes the ticket of a set-password link for an account. It works once and for {@link #LINK_MINUTES}
     * minutes; a link the account was given before stops working.
     */
    public synchronized String link(UUID id, String name)
    {
        long now = clock.getAsLong();
        links.values().removeIf(link -> now > link.expiresAt() || link.id().equals(id));
        Iterator<Link> oldest = links.values().iterator();
        while (links.size() >= MAX_LINKS && oldest.hasNext())
        {
            oldest.next();
            oldest.remove();
        }
        String ticket = auth.secret();
        links.put(AuthManager.hash(ticket), new Link(id, name, now + LINK_MINUTES * 60_000L));
        return ticket;
    }

    /**
     * POST /api/password: {"ticket", "name", "password"}.
     */
    public Api.Response setPassword(String address, JsonObject request)
    {
        String ticket = text(request, "ticket");
        String name = text(request, "name");
        String password = text(request, "password");
        if (ticket == null || !isName(name) || password == null)
        {
            return Api.error(400, "Give the ticket of the link, the name it is for and a password");
        }
        long now = clock.getAsLong();
        long wait = throttle.blocked(address, now);
        if (wait > 0)
        {
            return tooMany(wait);
        }
        String key = AuthManager.hash(ticket);
        Link link;
        synchronized (this)
        {
            link = links.get(key);
        }
        // One answer for a ticket that never was, one that ran out, one for somebody else and one whose
        // account has lost its rights since.
        if (link == null || now > link.expiresAt() || !link.name().equalsIgnoreCase(name) || !rules.isAdmin(link.id()))
        {
            throttle.failed(address, now);
            return Api.error(403, STALE_LINK);
        }
        String refusal = PasswordStore.refusal(password);
        if (refusal != null)
        {
            // The link is not used up by a password that was too short: it can be tried again.
            return Api.error(400, refusal);
        }
        synchronized (this)
        {
            if (links.remove(key) == null)
            {
                return Api.error(403, STALE_LINK);
            }
        }
        if (!startHashing())
        {
            restore(key, link);
            return busy();
        }
        try
        {
            passwords.set(link.id(), link.name(), password);
        }
        catch (IOException e)
        {
            LOG.error("The web editor could not save its admin passwords", e);
            restore(key, link);
            return Api.error(500, "The password could not be saved; the server log says why");
        }
        finally
        {
            hashing.release();
        }
        auth.revokeAdmin(link.id());
        LOG.info("{} set a web editor admin password", link.name());
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("name", link.name());
        return new Api.Response(200, result);
    }

    /**
     * POST /api/login: {"name", "password"}. Every failure gets the same answer after the same work: one hash,
     * whether or not the name has a password, and one look at who may change rules.
     */
    public Api.Response login(String address, JsonObject request)
    {
        String name = text(request, "name");
        String password = text(request, "password");
        if (!isName(name) || password == null || password.isEmpty() || password.length() > PasswordStore.MAX_LENGTH)
        {
            return Api.error(400, "Give a name and a password");
        }
        long wait = throttle.admit(address, name, clock.getAsLong());
        if (wait > 0)
        {
            return tooMany(wait);
        }
        if (!startHashing())
        {
            return busy();
        }
        UUID id;
        try
        {
            id = passwords.verify(name, password);
        }
        finally
        {
            hashing.release();
        }
        AdminRules.Account admin = rules.admin(name);
        if (id == null || admin == null || !admin.id().equals(id))
        {
            return Api.error(401, WRONG);
        }
        throttle.succeeded(address, name);
        JsonObject result = new JsonObject();
        result.addProperty("token", auth.issue(admin.id(), admin.name(), rules.sessionMillis(), true));
        result.addProperty("user", admin.name());
        result.addProperty("admin", true);
        return new Api.Response(200, result);
    }

    private boolean startHashing()
    {
        try
        {
            return hashing.tryAcquire(HASH_WAIT_SECONDS, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private synchronized void restore(String key, Link link)
    {
        links.put(key, link);
    }

    private static Api.Response busy()
    {
        return Api.error(503, "The server is busy; try again in a moment");
    }

    private static Api.Response tooMany(long waitMillis)
    {
        long seconds = (waitMillis + 999) / 1000;
        String time = seconds < 120 ? seconds + " seconds" : (seconds + 59) / 60 + " minutes";
        Api.Response response = Api.error(429, "Too many attempts. Try again in " + time);
        response.body().getAsJsonObject().addProperty("retryAfter", seconds);
        return response;
    }

    private static boolean isName(String name)
    {
        return name != null && !name.isEmpty() && name.length() <= MAX_NAME_LENGTH
                && name.chars().noneMatch(c -> c <= ' ' || c == 0x7f);
    }

    private static String text(JsonObject object, String key)
    {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
}
