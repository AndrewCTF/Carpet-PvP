package carpet.logic.web;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthManagerTest
{
    private static final long HOUR = 3_600_000L;

    @Test
    void issuedTokenResolvesToItsOwner()
    {
        AuthManager auth = new AuthManager();
        UUID steve = UUID.randomUUID();
        String token = auth.issue(steve, "Steve", HOUR);

        AuthManager.Session session = auth.validate(token);
        assertNotNull(session);
        assertEquals(steve, session.owner());
        assertEquals("Steve", session.ownerName());
        assertNotEquals(token, auth.issue(steve, "Steve", HOUR));
    }

    @Test
    void unknownEmptyAndExpiredTokensAreRefused()
    {
        AuthManager auth = new AuthManager();
        String token = auth.issue(UUID.randomUUID(), "Steve", HOUR);

        assertNull(auth.validate(null));
        assertNull(auth.validate(""));
        assertNull(auth.validate(token + "x"));
        assertNull(auth.validate(token.substring(1)));
        assertNull(auth.validate(auth.issue(UUID.randomUUID(), "Alex", -1)));

        auth.clear();
        assertNull(auth.validate(token));
    }

    @Test
    void oldestTokenOfAnOwnerIsDroppedWhenTheyHoldTooMany()
    {
        AuthManager auth = new AuthManager();
        UUID steve = UUID.randomUUID();
        String console = auth.issue(null, "Server", HOUR);
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 5; i++)
        {
            tokens.add(auth.issue(steve, "Steve", HOUR));
        }

        assertNull(auth.validate(tokens.get(0)));
        for (String token : tokens.subList(1, 5))
        {
            assertNotNull(auth.validate(token));
        }
        assertNull(auth.validate(console).owner());
    }

    @Test
    void bearerTokenComesOnlyFromABearerHeader()
    {
        assertEquals("abc", AuthManager.bearerToken("Bearer abc"));
        assertNull(AuthManager.bearerToken(null));
        assertNull(AuthManager.bearerToken("abc"));
        assertNull(AuthManager.bearerToken("Basic abc"));
    }
}
