package carpet.logic.web;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin sign-in without a game: who may change rules is what {@link FakeAdminRules} says, and the clock is
 * a number the test moves.
 */
class AdminLoginTest
{
    private static final String ADDRESS = "203.0.113.7";
    private static final String PASSWORD = "correct horse battery";
    private static final String WRONG = "{\"error\":\"Wrong name or password\"}";

    @TempDir
    Path folder;

    private final FakeAdminRules rules = new FakeAdminRules();
    private final AuthManager auth = new AuthManager();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private PasswordStore passwords;
    private AdminLogin login;
    private AdminRules.Account steve;

    @BeforeEach
    void start()
    {
        passwords = new PasswordStore(folder.resolve("admins.json"), 1_000);
        login = new AdminLogin(rules, auth, passwords, clock::get);
        steve = rules.op("Steve");
    }

    private static JsonObject body(String... pairs)
    {
        JsonObject body = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2)
        {
            body.addProperty(pairs[i], pairs[i + 1]);
        }
        return body;
    }

    private Api.Response setPassword(String ticket, String name, String password)
    {
        return login.setPassword(ADDRESS, body("ticket", ticket, "name", name, "password", password));
    }

    private Api.Response signIn(String name, String password)
    {
        return login.login(ADDRESS, body("name", name, "password", password));
    }

    /** Gives Steve a password through a link, the only way there is. */
    private void steveHasAPassword()
    {
        assertEquals(200, setPassword(login.link(steve.id(), steve.name()), "Steve", PASSWORD).status());
    }

    // ── The set-password link ──

    @Test
    void aLinkSetsThePasswordOfItsAccountOnce() throws IOException
    {
        String ticket = login.link(steve.id(), steve.name());

        Api.Response set = setPassword(ticket, "steve", PASSWORD);
        assertEquals(200, set.status(), set.body().toString());
        assertEquals("Steve", set.body().getAsJsonObject().get("name").getAsString());
        assertEquals(steve.id(), passwords.verify("Steve", PASSWORD));
        assertFalse(Files.readString(folder.resolve("admins.json")).contains(PASSWORD));

        Api.Response again = setPassword(ticket, "Steve", "another password");
        assertEquals(403, again.status(), "a link works once");
        assertEquals(steve.id(), passwords.verify("Steve", PASSWORD), "and the password it set stands");
    }

    @Test
    void aLinkRunsOut()
    {
        String ticket = login.link(steve.id(), steve.name());
        clock.addAndGet(AdminLogin.LINK_MINUTES * 60_000L + 1);

        assertEquals(403, setPassword(ticket, "Steve", PASSWORD).status());
        assertNull(passwords.verify("Steve", PASSWORD));
    }

    @Test
    void aLinkIsForOneAccountOnly()
    {
        AdminRules.Account alex = rules.op("Alex");
        String ticket = login.link(steve.id(), steve.name());

        Api.Response response = setPassword(ticket, "Alex", PASSWORD);
        assertEquals(403, response.status());
        assertFalse(passwords.has(alex.id()), "Steve's link did not give Alex a password");
        assertFalse(passwords.has(steve.id()));
        assertEquals(403, setPassword("no-such-ticket", "Steve", PASSWORD).status());
        assertEquals(response.body(), setPassword("no-such-ticket", "Steve", PASSWORD).body(),
                "a ticket for somebody else and one that never was get the same answer");

        assertEquals(200, setPassword(ticket, "Steve", PASSWORD).status(), "and neither used the link up");
    }

    @Test
    void aNewLinkStopsTheOldOne()
    {
        String first = login.link(steve.id(), steve.name());
        String second = login.link(steve.id(), steve.name());

        assertNotEquals(first, second);
        assertEquals(403, setPassword(first, "Steve", PASSWORD).status());
        assertEquals(200, setPassword(second, "Steve", PASSWORD).status());
    }

    @Test
    void aPasswordThatIsTooShortDoesNotUseTheLinkUp()
    {
        String ticket = login.link(steve.id(), steve.name());

        Api.Response tooShort = setPassword(ticket, "Steve", "short");
        assertEquals(400, tooShort.status());
        assertTrue(tooShort.body().toString().contains("at least " + PasswordStore.MIN_LENGTH), tooShort.body().toString());
        assertFalse(passwords.has(steve.id()));

        assertEquals(200, setPassword(ticket, "Steve", PASSWORD).status());
    }

    @Test
    void aLinkIsWorthlessOnceItsAccountMayNotChangeRules()
    {
        String ticket = login.link(steve.id(), steve.name());
        rules.deop("Steve");

        assertEquals(403, setPassword(ticket, "Steve", PASSWORD).status());
        assertFalse(passwords.has(steve.id()));
    }

    @Test
    void guessingAtTicketsIsHeldUp()
    {
        Api.Response last = null;
        for (int i = 0; i <= LoginThrottle.FREE_PER_ADDRESS; i++)
        {
            last = setPassword("guess-" + i, "Steve", PASSWORD);
        }
        assertEquals(429, last.status());
        assertEquals(429, setPassword(login.link(steve.id(), steve.name()), "Steve", PASSWORD).status(),
                "even a real ticket waits, from that address");
    }

    // ── Signing in ──

    @Test
    void theRightNameAndPasswordGiveAnAdminSession()
    {
        steveHasAPassword();
        long before = System.currentTimeMillis();

        Api.Response response = signIn("steve", PASSWORD);
        assertEquals(200, response.status(), response.body().toString());
        JsonObject body = response.body().getAsJsonObject();
        assertEquals("Steve", body.get("user").getAsString());
        assertTrue(body.get("admin").getAsBoolean());

        AuthManager.Session session = auth.validate(body.get("token").getAsString());
        assertNotNull(session, "the token is one the API accepts");
        assertTrue(session.admin(), "and it is marked as an admin's");
        assertEquals(steve.id(), session.owner());
        assertEquals("Steve", session.ownerName());
        long lifetime = session.expiresAt() - before;
        assertTrue(lifetime >= rules.sessionMillis && lifetime < rules.sessionMillis + 60_000L,
                "it lives as long as carpetLogicSessionHours says, not " + lifetime + " ms");
    }

    @Test
    void aLinkFromTheGameIsNotAnAdminSession()
    {
        String token = auth.issue(steve.id(), "Steve", 3_600_000L);
        assertFalse(auth.validate(token).admin());
        assertFalse(new AuthManager.Session(null, "Server", Long.MAX_VALUE).admin());
    }

    @Test
    void everyFailureGetsTheSameAnswer()
    {
        steveHasAPassword();
        rules.op("Alex");                                    // may change rules, never set a password
        AdminRules.Account herobrine = rules.op("Herobrine"); // set a password, then lost the right
        assertEquals(200, setPassword(login.link(herobrine.id(), "Herobrine"), "Herobrine", PASSWORD).status());
        rules.deop("Herobrine");

        for (String[] attempt : new String[][] {
                {"Steve", "not the password"}, {"Nobody", PASSWORD}, {"Alex", PASSWORD}, {"Herobrine", PASSWORD}})
        {
            int looked = rules.lookups;
            Api.Response response = signIn(attempt[0], attempt[1]);
            assertEquals(401, response.status(), attempt[0]);
            assertEquals(WRONG, response.body().toString(), attempt[0]);
            assertEquals(looked + 1, rules.lookups, attempt[0] + ": who may change rules is looked up on every path");
        }
        assertEquals(200, signIn("Steve", PASSWORD).status());
    }

    @Test
    void aRequestThatIsNotANameAndAPasswordIsRefusedBeforeAnyWork()
    {
        steveHasAPassword();
        assertEquals(400, login.login(ADDRESS, body("name", "Steve")).status());
        assertEquals(400, login.login(ADDRESS, body("password", PASSWORD)).status());
        assertEquals(400, signIn("", PASSWORD).status());
        assertEquals(400, signIn("a name with spaces", PASSWORD).status());
        assertEquals(400, signIn("x".repeat(33), PASSWORD).status());
        assertEquals(400, signIn("Steve", "x".repeat(PasswordStore.MAX_LENGTH + 1)).status());
        assertEquals(0, rules.lookups);
    }

    @Test
    void wrongPasswordsAreHeldUpLongerAndLonger()
    {
        steveHasAPassword();
        for (int i = 0; i < LoginThrottle.FREE_PER_ACCOUNT; i++)
        {
            assertEquals(401, signIn("Steve", "wrong guess " + i).status());
        }

        Api.Response held = signIn("Steve", PASSWORD);
        assertEquals(429, held.status(), "even the right password waits");
        assertEquals(LoginThrottle.FIRST_WAIT_MILLIS / 1000, held.body().getAsJsonObject().get("retryAfter").getAsLong());
        assertTrue(held.body().toString().contains("Try again in 5 seconds"), held.body().toString());
        assertNull(held.body().getAsJsonObject().get("token"));

        clock.addAndGet(LoginThrottle.FIRST_WAIT_MILLIS);
        assertEquals(401, signIn("Steve", "one more guess").status());
        held = signIn("Steve", PASSWORD);
        assertEquals(429, held.status());
        assertEquals(2 * LoginThrottle.FIRST_WAIT_MILLIS / 1000, held.body().getAsJsonObject().get("retryAfter").getAsLong(),
                "the wait doubled");

        clock.addAndGet(2 * LoginThrottle.FIRST_WAIT_MILLIS);
        assertEquals(200, signIn("Steve", PASSWORD).status());
        for (int i = 0; i < LoginThrottle.FREE_PER_ACCOUNT - 1; i++)
        {
            assertEquals(401, signIn("Steve", "wrong guess " + i).status(), "signing in gave the free attempts back");
        }
    }

    @Test
    void aNameNobodyHasIsHeldUpTheSameWay()
    {
        for (int i = 0; i < LoginThrottle.FREE_PER_ACCOUNT; i++)
        {
            assertEquals(401, signIn("Nobody", "wrong guess " + i).status());
        }
        assertEquals(429, signIn("Nobody", PASSWORD).status(), "so being held up does not say that a name exists");
    }

    @Test
    void aNewPasswordSignsTheOldSessionsOut()
    {
        steveHasAPassword();
        String admin = signIn("Steve", PASSWORD).body().getAsJsonObject().get("token").getAsString();
        String fromTheGame = auth.issue(steve.id(), "Steve", 3_600_000L);

        assertEquals(200, setPassword(login.link(steve.id(), "Steve"), "Steve", "a different password").status());

        assertNull(auth.validate(admin), "whoever knew the old password is out");
        assertNotNull(auth.validate(fromTheGame), "a link from the game is not a password session");
        assertEquals(401, signIn("Steve", PASSWORD).status());
        assertEquals(200, signIn("Steve", "a different password").status());
    }
}
