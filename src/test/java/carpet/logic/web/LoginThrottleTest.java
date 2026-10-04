package carpet.logic.web;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginThrottleTest
{
    private static final long START = 1_000_000L;

    /** Uses up the free attempts of a name, each from an address of its own, and answers the time it then is. */
    private static long exhaust(LoginThrottle throttle, String name)
    {
        for (int i = 0; i < LoginThrottle.FREE_PER_ACCOUNT; i++)
        {
            assertEquals(0, throttle.admit("10.0.0." + i, name, START + i), "free attempt " + (i + 1));
        }
        return START + LoginThrottle.FREE_PER_ACCOUNT - 1;
    }

    @Test
    void aNameGetsAFewAttemptsAndThenHasToWait()
    {
        LoginThrottle throttle = new LoginThrottle();
        long now = exhaust(throttle, "Steve");

        // Wherever the next guess comes from: the account is what is being guessed at.
        assertEquals(LoginThrottle.FIRST_WAIT_MILLIS, throttle.admit("10.9.9.9", "Steve", now));
        assertEquals(LoginThrottle.FIRST_WAIT_MILLIS, throttle.admit("10.9.9.9", "STEVE", now), "a name is one name in any case");
        assertEquals(1, throttle.admit("10.9.9.9", "Steve", now + LoginThrottle.FIRST_WAIT_MILLIS - 1));
        assertEquals(0, throttle.admit("10.9.9.9", "Alex", now), "another name is not held up by it");
    }

    @Test
    void theWaitDoublesWithEveryFurtherAttemptUpToALimit()
    {
        LoginThrottle throttle = new LoginThrottle();
        long now = exhaust(throttle, "Steve");

        long expected = LoginThrottle.FIRST_WAIT_MILLIS;
        for (int attempt = 0; attempt < 12; attempt++)
        {
            assertEquals(expected, throttle.admit("10.9.9.9", "Steve", now), "the wait before attempt " + attempt);
            now += expected;
            assertEquals(0, throttle.admit("10.9.9." + attempt, "Steve", now), "once it has been waited out");
            expected = Math.min(expected * 2, LoginThrottle.LONGEST_WAIT_MILLIS);
        }
        assertEquals(LoginThrottle.LONGEST_WAIT_MILLIS, expected, "twelve doublings reach the longest wait");
    }

    @Test
    void anAttemptThatIsRefusedIsNotCounted()
    {
        LoginThrottle throttle = new LoginThrottle();
        long now = exhaust(throttle, "Steve");

        for (int i = 0; i < 50; i++)
        {
            assertTrue(throttle.admit("10.9.9.9", "Steve", now + 1) > 0);
        }
        assertEquals(0, throttle.admit("10.9.9.9", "Steve", now + LoginThrottle.FIRST_WAIT_MILLIS),
                "knocking while it was shut did not make the wait longer");
    }

    @Test
    void anAddressIsHeldUpAcrossNames()
    {
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < LoginThrottle.FREE_PER_ADDRESS; i++)
        {
            assertEquals(0, throttle.admit("10.0.0.1", "name" + i, START));
        }
        assertEquals(LoginThrottle.FIRST_WAIT_MILLIS, throttle.admit("10.0.0.1", "another", START));
        assertEquals(LoginThrottle.FIRST_WAIT_MILLIS, throttle.blocked("10.0.0.1", START));
        assertEquals(0, throttle.admit("10.0.0.2", "another", START), "somebody else is not");
        assertTrue(LoginThrottle.FREE_PER_ADDRESS > LoginThrottle.FREE_PER_ACCOUNT,
                "an address may stand for many people behind a proxy, a name for one");
    }

    @Test
    void failuresWithoutANameCountAgainstTheAddress()
    {
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < LoginThrottle.FREE_PER_ADDRESS; i++)
        {
            assertEquals(0, throttle.blocked("10.0.0.1", START));
            throttle.failed("10.0.0.1", START);
        }
        assertEquals(LoginThrottle.FIRST_WAIT_MILLIS, throttle.blocked("10.0.0.1", START));
    }

    @Test
    void gettingItRightStartsAgain()
    {
        LoginThrottle throttle = new LoginThrottle();
        for (int i = 0; i < LoginThrottle.FREE_PER_ACCOUNT - 1; i++)
        {
            assertEquals(0, throttle.admit("10.0.0.1", "Steve", START));
        }
        throttle.succeeded("10.0.0.1", "Steve");
        assertEquals(0, throttle.size());
        exhaust(throttle, "Steve");
    }

    @Test
    void aRecordIsForgottenAfterAnHourWithoutAttempts()
    {
        LoginThrottle throttle = new LoginThrottle();
        long now = exhaust(throttle, "Steve");
        assertTrue(throttle.admit("10.9.9.9", "Steve", now) > 0);

        long later = now + LoginThrottle.FORGET_MILLIS + 1;
        assertEquals(0, throttle.admit("10.9.9.9", "Steve", later));
        assertEquals(0, throttle.admit("10.9.9.9", "Steve", later), "and it has its free attempts back");
        assertEquals(2, throttle.size(), "the addresses that tried an hour ago are gone from the table");
    }

    @Test
    void theTableIsBoundedAndNothingIsThrownOutToMakeRoom()
    {
        LoginThrottle throttle = new LoginThrottle();
        long now = exhaust(throttle, "Steve");
        for (int i = 0; throttle.size() < LoginThrottle.MAX_KEYS; i++)
        {
            throttle.admit("172.16." + (i / 250) + "." + (i % 250), "filler" + i, now);
        }
        assertEquals(LoginThrottle.MAX_KEYS, throttle.size());

        assertTrue(throttle.admit("192.168.0.1", "newcomer", now) > 0, "a full table takes no new keys");
        assertEquals(LoginThrottle.MAX_KEYS, throttle.size());
        assertEquals(LoginThrottle.FIRST_WAIT_MILLIS, throttle.admit("192.168.0.1", "Steve", now),
                "and the name that was being guessed at is still held up");

        long later = now + LoginThrottle.FORGET_MILLIS + 1;
        assertEquals(0, throttle.admit("192.168.0.1", "newcomer", later), "until the old keys have been forgotten");
        assertEquals(2, throttle.size());
    }

    @Test
    void anIpv6AddressIsCountedByItsNetwork() throws UnknownHostException
    {
        String one = LoginThrottle.key(InetAddress.getByName("2001:db8:1:2:aaaa:bbbb:cccc:1"));
        String sameNetwork = LoginThrottle.key(InetAddress.getByName("2001:db8:1:2:ffff:0:0:2"));
        String otherNetwork = LoginThrottle.key(InetAddress.getByName("2001:db8:1:3:aaaa:bbbb:cccc:1"));

        assertEquals(one, sameNetwork);
        assertNotEquals(one, otherNetwork);
        assertEquals("203.0.113.7", LoginThrottle.key(InetAddress.getByName("203.0.113.7")));
    }
}
