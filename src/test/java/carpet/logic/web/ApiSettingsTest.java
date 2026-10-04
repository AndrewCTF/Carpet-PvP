package carpet.logic.web;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POST /api/settings, called the way the web server calls it once a token has been checked. The rules are
 * {@link FakeAdminRules}'s two; that a real rule changes, with its validators, is the self-test's to show.
 */
class ApiSettingsTest
{
    private static final String ROUTE = "/api/settings";

    private final FakeAdminRules rules = new FakeAdminRules();
    private final Api api = new Api(null, null, rules);
    private AuthManager.Session admin;

    @BeforeEach
    void signIn()
    {
        admin = new AuthManager.Session(rules.op("Steve").id(), "Steve", Long.MAX_VALUE, true);
    }

    private static String change(String rule, String value)
    {
        JsonObject body = new JsonObject();
        body.addProperty("rule", rule);
        body.addProperty("value", value);
        return body.toString();
    }

    private Api.Response post(AuthManager.Session session, String body)
    {
        return api.handle("POST", ROUTE, session, body);
    }

    @Test
    void anAdminChangesARule()
    {
        Api.Response response = post(admin, change("carpetLogicMaxPrograms", "9"));

        assertEquals(200, response.status(), response.body().toString());
        JsonObject body = response.body().getAsJsonObject();
        assertTrue(body.get("success").getAsBoolean());
        assertEquals("9", body.getAsJsonObject("rule").get("value").getAsString(), "the answer carries the rule as it now is");
        assertEquals("9", rules.values.get("carpetLogicMaxPrograms"));
        assertEquals(List.of("Steve carpetLogicMaxPrograms=9"), rules.changes, "and it was changed in the admin's name");
    }

    @Test
    void aSessionThatIsNotAnAdminsChangesNothing()
    {
        // The session of a link from the console, which may do everything else. One a player opened in game is
        // checked against the game before it gets here, and that half needs a server this test does not have.
        AuthManager.Session fromTheGame = new AuthManager.Session(null, "Server", Long.MAX_VALUE);

        Api.Response response = post(fromTheGame, change("carpetLogicMaxPrograms", "9"));
        assertEquals(403, response.status());
        assertTrue(response.body().toString().contains("Only an admin"), response.body().toString());
        assertEquals("4", rules.values.get("carpetLogicMaxPrograms"));
        assertTrue(rules.changes.isEmpty());
    }

    @Test
    void anAdminWhoLostTheRightChangesNothing()
    {
        rules.deop("Steve");

        Api.Response response = post(admin, change("carpetLogicMaxPrograms", "9"));
        assertEquals(403, response.status());
        assertTrue(response.body().toString().contains("may no longer change Carpet rules"), response.body().toString());
        assertEquals("4", rules.values.get("carpetLogicMaxPrograms"));

        AuthManager.Session stranger = new AuthManager.Session(UUID.randomUUID(), "Nobody", Long.MAX_VALUE, true);
        assertEquals(403, post(stranger, change("carpetLogicMaxPrograms", "9")).status());
    }

    @Test
    void aLockedRuleIsRefusedWithItsReason()
    {
        rules.locked = "Carpet's settings are locked in carpet.conf";

        Api.Response response = post(admin, change("carpetLogicMaxPrograms", "9"));
        assertEquals(409, response.status());
        JsonObject body = response.body().getAsJsonObject();
        assertFalse(body.get("success").getAsBoolean());
        assertEquals(rules.locked, body.get("error").getAsString());
        assertEquals("4", rules.values.get("carpetLogicMaxPrograms"));
    }

    @Test
    void aValueTheRuleDoesNotTakeIsRefusedWithItsReason()
    {
        Api.Response response = post(admin, change("carpetLogicMaxPrograms", "many"));
        assertEquals(400, response.status());
        assertEquals("Wrong value for carpetLogicMaxPrograms: many", response.body().getAsJsonObject().get("error").getAsString());
        assertEquals("4", response.body().getAsJsonObject().getAsJsonObject("rule").get("value").getAsString(),
                "the answer carries the value the rule still has");
        assertEquals("4", rules.values.get("carpetLogicMaxPrograms"));

        assertEquals(404, post(admin, change("noSuchRule", "1")).status());
        assertEquals(400, post(admin, "{\"rule\": \"carpetLogicMaxPrograms\"}").status());
        assertEquals(400, post(admin, "not json").status());
        assertTrue(rules.changes.isEmpty());
    }

    @Test
    void viewerModeDoesNotLockAnAdminOutOfSettings()
    {
        rules.viewer = true;

        assertEquals(200, post(admin, change("carpetLogicMaxPrograms", "9")).status());
        assertEquals(200, post(admin, change("carpetLogicViewerMode", "false")).status(), "viewer mode itself is a setting");
        assertFalse(rules.viewer);
    }

    @Test
    void viewerModeStillStopsEverythingElse()
    {
        rules.viewer = true;
        AuthManager.Session fromTheGame = new AuthManager.Session(null, "Server", Long.MAX_VALUE);

        for (String route : List.of("/api/execute", "/api/stop", "/api/bots/spawn", "/api/bots/remove", "/api/programs"))
        {
            Api.Response response = api.handle("POST", route, admin, "{}");
            assertEquals(403, response.status(), route);
            assertTrue(response.body().toString().contains("viewer mode"), route);
        }
        Api.Response response = post(fromTheGame, change("carpetLogicViewerMode", "false"));
        assertEquals(403, response.status(), "and only an admin changes a setting, in viewer mode as out of it");
        assertTrue(response.body().toString().contains("Only an admin"), response.body().toString());
        assertTrue(rules.viewer);
    }

    @Test
    void theRouteIsNotThereWhileTheRuleIsOff()
    {
        rules.login = false;

        Api.Response response = post(admin, change("carpetLogicMaxPrograms", "9"));
        assertEquals(404, response.status());
        assertEquals("{\"error\":\"Unknown API endpoint\"}", response.body().toString());
        assertEquals("4", rules.values.get("carpetLogicMaxPrograms"));

        rules.viewer = true;
        assertEquals(403, post(admin, change("carpetLogicMaxPrograms", "9")).status(), "and viewer mode has no exception left");
    }
}
