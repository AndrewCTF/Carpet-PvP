package carpet.logic.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Access tokens for the web editor. A token is issued in game to whoever ran the command, or to an admin who
 * signed in with a password, and is the only credential the API accepts. Tokens live in memory, so a restart
 * invalidates all of them.
 */
public class AuthManager
{
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_SESSIONS_PER_OWNER = 4;

    /**
     * @param owner the player the token was issued to, or null when it was issued from the server console
     * @param admin whether the token came from the admin sign-in, which is what may change rules
     */
    public record Session(UUID owner, String ownerName, long expiresAt, boolean admin)
    {
        public Session(UUID owner, String ownerName, long expiresAt)
        {
            this(owner, ownerName, expiresAt, false);
        }
    }

    private final SecureRandom random = new SecureRandom();
    // Keyed by the SHA-256 of the token, in order of issue, so the token itself is never kept.
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public String issue(UUID owner, String ownerName, long lifetimeMillis)
    {
        return issue(owner, ownerName, lifetimeMillis, false);
    }

    public synchronized String issue(UUID owner, String ownerName, long lifetimeMillis, boolean admin)
    {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> now > s.expiresAt());
        int owned = 0;
        for (Session session : sessions.values())
        {
            if (sameOwner(session, owner))
            {
                owned++;
            }
        }
        Iterator<Session> oldest = sessions.values().iterator();
        while (owned >= MAX_SESSIONS_PER_OWNER && oldest.hasNext())
        {
            if (sameOwner(oldest.next(), owner))
            {
                oldest.remove();
                owned--;
            }
        }

        String token = secret();
        sessions.put(hash(token), new Session(owner, ownerName, now + lifetimeMillis, admin));
        return token;
    }

    /**
     * @return 32 random bytes as text that can go in a link
     */
    String secret()
    {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * @return the session the token belongs to, or null when the token is unknown or expired
     */
    public synchronized Session validate(String token)
    {
        if (token == null || token.isEmpty())
        {
            return null;
        }
        String key = hash(token);
        Session session = sessions.get(key);
        if (session != null && System.currentTimeMillis() > session.expiresAt())
        {
            sessions.remove(key);
            return null;
        }
        return session;
    }

    /**
     * Ends the session of a token, as signing out does.
     *
     * @return whether there was one
     */
    public synchronized boolean revoke(String token)
    {
        return token != null && !token.isEmpty() && sessions.remove(hash(token)) != null;
    }

    /**
     * Ends every admin session of an account: a password that was replaced must not leave the old one signed in.
     */
    public synchronized void revokeAdmin(UUID owner)
    {
        sessions.values().removeIf(session -> session.admin() && sameOwner(session, owner));
    }

    public synchronized void clear()
    {
        sessions.clear();
    }

    /**
     * @return the token of an "Authorization: Bearer ..." header, or null
     */
    public static String bearerToken(String authorizationHeader)
    {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer "))
        {
            return null;
        }
        return authorizationHeader.substring("Bearer ".length()).trim();
    }

    private static boolean sameOwner(Session session, UUID owner)
    {
        return owner == null ? session.owner() == null : owner.equals(session.owner());
    }

    static String hash(String token)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
