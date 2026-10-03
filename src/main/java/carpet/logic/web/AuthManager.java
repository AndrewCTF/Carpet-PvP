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
 * Access tokens for the web editor. A token is issued in game to whoever ran the command and is the only
 * credential the web server accepts. Tokens live in memory, so a restart invalidates all of them.
 */
public class AuthManager
{
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_SESSIONS_PER_OWNER = 4;

    /**
     * @param owner the player the token was issued to, or null when it was issued from the server console
     */
    public record Session(UUID owner, String ownerName, long expiresAt)
    {
    }

    private final SecureRandom random = new SecureRandom();
    // Keyed by the SHA-256 of the token, in order of issue, so the token itself is never kept.
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public synchronized String issue(UUID owner, String ownerName, long lifetimeMillis)
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

        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(hash(token), new Session(owner, ownerName, now + lifetimeMillis));
        return token;
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

    private static String hash(String token)
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
