package carpet.logic.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin sign-in as a browser reaches it: the web server is started for real, and the game behind it is
 * {@link FakeAdminRules}. What these routes do once a request is past the door is in {@link AdminLoginTest}.
 */
class AdminRoutesTest
{
    private static final String PASSWORD = "correct horse battery";
    private static final String NO_TOKEN = "{\"error\":\"Missing or expired token. Run /carpetlogic open in game for a link.\"}";

    @TempDir
    Path folder;

    private final FakeAdminRules rules = new FakeAdminRules();
    private final AuthManager auth = new AuthManager();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private AdminLogin login;
    private WebServer web;
    private String url;
    private AdminRules.Account steve;

    @BeforeEach
    void listen() throws IOException
    {
        login = new AdminLogin(rules, auth, new PasswordStore(folder.resolve("admins.json"), 1_000));
        web = new WebServer(null, auth, new Api(null, null, rules), login, "127.0.0.1", 0);
        url = web.url();
        steve = rules.op("Steve");
    }

    @AfterEach
    void stop()
    {
        web.stop();
    }

    private HttpResponse<String> send(String method, String path, String token, String contentType, String body)
            throws IOException, InterruptedException
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(10))
                // What a page on another site would send.
                .header("Origin", "http://evil.example");
        if (token != null)
        {
            request.header("Authorization", "Bearer " + token);
        }
        if (contentType != null)
        {
            request.header("Content-Type", contentType);
        }
        HttpRequest.BodyPublisher content = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return client.send(request.method(method, content).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException
    {
        return send("POST", path, null, "application/json", json);
    }

    private static String json(String... pairs)
    {
        JsonObject body = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2)
        {
            body.addProperty(pairs[i], pairs[i + 1]);
        }
        return body.toString();
    }

    private static JsonObject parsed(HttpResponse<String> response)
    {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /** Sets Steve's password through a link and signs him in, all over HTTP. */
    private String signedIn() throws IOException, InterruptedException
    {
        String ticket = login.link(steve.id(), steve.name());
        assertEquals(200, post("/api/password", json("ticket", ticket, "name", "Steve", "password", PASSWORD)).statusCode());
        HttpResponse<String> response = post("/api/login", json("name", "Steve", "password", PASSWORD));
        assertEquals(200, response.statusCode(), response.body());
        return parsed(response).get("token").getAsString();
    }

    @Test
    void aPasswordSetThroughALinkSignsItsAdminIn() throws IOException, InterruptedException
    {
        String token = signedIn();

        AuthManager.Session session = auth.validate(token);
        assertNotNull(session);
        assertTrue(session.admin());
        assertEquals(steve.id(), session.owner());
    }

    @Test
    void aWrongPasswordGetsNoToken() throws IOException, InterruptedException
    {
        signedIn();
        for (String[] attempt : new String[][] {{"Steve", "not the password"}, {"Nobody", PASSWORD}})
        {
            HttpResponse<String> response = post("/api/login", json("name", attempt[0], "password", attempt[1]));
            assertEquals(401, response.statusCode());
            assertEquals("{\"error\":\"Wrong name or password\"}", response.body());
        }
    }

    @Test
    void theRoutesAreNotThereWhileTheRuleIsOff() throws IOException, InterruptedException
    {
        String ticket = login.link(steve.id(), steve.name());
        rules.login = false;

        for (String body : List.of(json("name", "Steve", "password", PASSWORD), json("ticket", ticket, "name", "Steve", "password", PASSWORD)))
        {
            for (String path : List.of("/api/login", "/api/password"))
            {
                HttpResponse<String> response = post(path, body);
                assertEquals(401, response.statusCode(), path);
                assertEquals(NO_TOKEN, response.body(), path + " is answered like any path that needs a token");
            }
        }
        assertEquals(NO_TOKEN, send("GET", "/api/status", null, null, null).body());
        assertFalse(new PasswordStore(folder.resolve("admins.json"), 1_000).has(steve.id()), "and nothing was set");
    }

    @Test
    void aPageWithoutATokenIsToldThatItCanOfferTheSignIn() throws IOException, InterruptedException
    {
        HttpResponse<String> response = send("GET", "/api/status", null, null, null);
        assertEquals(401, response.statusCode());
        assertTrue(parsed(response).get("adminLogin").getAsBoolean());
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(null));
    }

    @Test
    void anAdminSessionEndsWithTheRule() throws IOException, InterruptedException
    {
        String token = signedIn();
        rules.login = false;

        HttpResponse<String> response = send("GET", "/api/status", token, null, null);
        assertEquals(401, response.statusCode());
        assertEquals(NO_TOKEN, response.body());
        assertNull(auth.validate(token), "the session is gone for good, not only refused while the rule is off");
    }

    @Test
    void signingOutEndsTheSessionOnTheServer() throws IOException, InterruptedException
    {
        String token = signedIn();
        String fromTheGame = auth.issue(null, "Server", 3_600_000L);

        for (String each : List.of(token, fromTheGame))
        {
            HttpResponse<String> response = send("POST", "/api/logout", each, null, null);
            assertEquals(200, response.statusCode(), response.body());
            assertNull(auth.validate(each));
            assertEquals(401, send("POST", "/api/logout", each, null, null).statusCode(), "a token that was signed out is no token");
        }
    }

    @Test
    void signingOutWorksInViewerMode() throws IOException, InterruptedException
    {
        String token = signedIn();
        rules.viewer = true;

        assertEquals(200, send("POST", "/api/logout", token, null, null).statusCode());
        assertNull(auth.validate(token));
    }

    @Test
    void onlyJsonIsTaken() throws IOException, InterruptedException
    {
        // The content types a form on another site can send without the browser asking first.
        String body = json("name", "Steve", "password", PASSWORD);
        for (String type : List.of("text/plain", "application/x-www-form-urlencoded", "multipart/form-data"))
        {
            assertEquals(415, send("POST", "/api/login", null, type, body).statusCode(), type);
            assertEquals(415, send("POST", "/api/password", null, type, body).statusCode(), type);
        }
        assertEquals(415, send("POST", "/api/login", null, null, body).statusCode(), "no content type at all");
        assertEquals(400, post("/api/login", "not json").statusCode());
        assertEquals(400, post("/api/login", "[]").statusCode());
        assertEquals(413, post("/api/login", json("name", "Steve", "password", "x".repeat(5000))).statusCode());
        assertEquals(0, rules.lookups, "none of them got as far as a password check");
    }

    @Test
    void nothingIsAnsweredWithCorsHeaders() throws IOException, InterruptedException
    {
        String token = signedIn();
        List<HttpResponse<String>> responses = List.of(
                post("/api/login", json("name", "Steve", "password", PASSWORD)),
                post("/api/login", json("name", "Steve", "password", "not the password")),
                post("/api/password", json("ticket", "none", "name", "Steve", "password", PASSWORD)),
                // What a browser asks before it lets another site send JSON here: the answer allows nothing.
                client.send(HttpRequest.newBuilder(URI.create(url + "/api/login")).header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()),
                send("POST", "/api/logout", token, null, null));
        for (HttpResponse<String> response : responses)
        {
            assertTrue(response.headers().map().keySet().stream()
                            .noneMatch(name -> name.toLowerCase(Locale.ROOT).startsWith("access-control")),
                    response.uri() + " answered with " + response.headers().map());
        }
        assertEquals(401, responses.get(3).statusCode(), "the preflight is refused like any request without a token");
    }

    @Test
    void beingHeldUpSaysForHowLong() throws IOException, InterruptedException
    {
        HttpResponse<String> response = null;
        for (int i = 0; i <= LoginThrottle.FREE_PER_ACCOUNT; i++)
        {
            response = post("/api/login", json("name", "Steve", "password", "wrong guess " + i));
        }
        assertEquals(429, response.statusCode());
        assertEquals("5", response.headers().firstValue("Retry-After").orElse(null));
        assertEquals(5, parsed(response).get("retryAfter").getAsInt());
    }
}
