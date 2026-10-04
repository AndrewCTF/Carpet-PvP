package carpet.logic.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The web server is started for real, on a port the operating system hands out, so that what a browser could
 * reach is what is checked here. No request gets past the token check: that is the only thing the editor's
 * routes do without a game to talk to.
 */
class WebServerTest
{
    /** The address carpetLogicBindAddress defaults to: only the machine the server runs on. */
    private static final String LOOPBACK = "127.0.0.1";

    private static final List<String> ROUTES = List.of("/api/status", "/api/settings", "/api/schema", "/api/programs",
            "/api/presets", "/api/bots", "/api/matches", "/api/events", "/api/execute", "/api/stop", "/api/anything-else",
            "/api/login", "/api/password", "/api/logout");

    /**
     * The routes that change something, and therefore have to be a POST with a token. The two of the admin
     * sign-in are among them here, where there is no sign-in: without one they are routes like any other.
     */
    private static final List<String> WRITE_ROUTES = List.of("/api/bots/spawn", "/api/bots/remove", "/api/bots/config",
            "/api/bots/tp", "/api/execute", "/api/stop", "/api/programs", "/api/settings", "/api/login", "/api/password",
            "/api/logout");

    private final AuthManager auth = new AuthManager();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private WebServer web;
    private int port;

    @AfterEach
    void stop()
    {
        if (web != null)
        {
            web.stop();
        }
    }

    private void listen(String address) throws IOException
    {
        web = new WebServer(null, auth, new Api(null, null), address, 0);
        String url = web.url();
        port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1));
    }

    private HttpResponse<String> request(String address, String path, String token) throws IOException, InterruptedException
    {
        return request(address, path, token, "GET");
    }

    private HttpResponse<String> post(String path, String token) throws IOException, InterruptedException
    {
        return request(LOOPBACK, path, token, "POST");
    }

    private HttpResponse<String> request(String address, String path, String token, String method) throws IOException, InterruptedException
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://" + address + ":" + port + path))
                .timeout(Duration.ofSeconds(5))
                // What a page on another origin would send.
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "POST");
        if (token != null)
        {
            request.header("Authorization", "Bearer " + token);
        }
        HttpRequest.BodyPublisher body = "POST".equals(method) ? HttpRequest.BodyPublishers.ofString("{}")
                : HttpRequest.BodyPublishers.noBody();
        return client.send(request.method(method, body).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everyRouteNeedsAToken() throws IOException, InterruptedException
    {
        listen(LOOPBACK);
        for (String route : ROUTES)
        {
            HttpResponse<String> response = request(LOOPBACK, route, null);
            assertEquals(401, response.statusCode(), route);
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(null), route);
            assertTrue(response.body().contains("Missing or expired token"), route);
        }
    }

    @Test
    void everyWriteRouteNeedsATokenToo() throws IOException, InterruptedException
    {
        listen(LOOPBACK);
        for (String route : WRITE_ROUTES)
        {
            HttpResponse<String> response = post(route, null);
            assertEquals(401, response.statusCode(), route);
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(null), route);
            assertTrue(response.body().contains("Missing or expired token"), route);
        }
    }

    @Test
    void aTokenTheServerDidNotIssueIsRefused() throws IOException, InterruptedException
    {
        listen(LOOPBACK);
        // An expired one, and one from a session store that only existed before a restart.
        assertEquals(401, request(LOOPBACK, "/api/status", auth.issue(UUID.randomUUID(), "Steve", -1)).statusCode());
        assertEquals(401, request(LOOPBACK, "/api/status",
                new AuthManager().issue(UUID.randomUUID(), "Steve", Duration.ofHours(1).toMillis())).statusCode());
        assertEquals(401, request(LOOPBACK, "/api/status", "not-a-token").statusCode());
    }

    @Test
    void aSessionThatBelongsToAPlayerIsCheckedAgainstTheGame()
    {
        // A link from the console stands for the server itself, which may use everything. Whether the player a
        // link was issued to is still online and may still use /carpetlogic is decided by the running game, so
        // that half of the check needs a server this test does not have.
        assertNull(new Api(null, null).denied(new AuthManager.Session(null, "Server", Long.MAX_VALUE)));
    }

    @Test
    void nothingIsAnsweredWithCorsHeaders() throws IOException, InterruptedException
    {
        listen(LOOPBACK);
        for (String path : List.of("/api/status", "/api/login", "/api/password", "/index.html", "/js/node-compiler.js", "/nowhere"))
        {
            HttpResponse<String> response = request(LOOPBACK, path, null);
            assertTrue(response.headers().map().keySet().stream()
                            .noneMatch(name -> name.toLowerCase(Locale.ROOT).startsWith("access-control")),
                    path + " answered with " + response.headers().map());
        }
    }

    @Test
    void theDefaultBindAddressKeepsTheEditorOffTheNetwork() throws IOException, InterruptedException
    {
        List<InetAddress> elsewhere = otherLocalAddresses();
        assertFalse(elsewhere.isEmpty(), "this machine has no other address to reach the server from");
        listen(LOOPBACK);
        assertEquals(401, request(LOOPBACK, "/api/status", null).statusCode());
        for (InetAddress address : elsewhere)
        {
            assertRefused(address);
        }
        // The same check stops passing as soon as the address is not loopback: this is what the default is for.
        web.stop();
        listen("0.0.0.0");
        for (InetAddress address : elsewhere)
        {
            try (Socket socket = new Socket())
            {
                socket.connect(new InetSocketAddress(address, port), 1000);
            }
        }
    }

    private void assertRefused(InetAddress address)
    {
        try (Socket socket = new Socket())
        {
            assertThrows(ConnectException.class, () -> socket.connect(new InetSocketAddress(address, port), 1000),
                    address + ":" + port + " should not be listening");
        }
        catch (IOException e)
        {
            throw new AssertionError(e);
        }
    }

    private static List<InetAddress> otherLocalAddresses() throws IOException
    {
        List<InetAddress> elsewhere = new ArrayList<>();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces()))
        {
            for (InetAddress address : Collections.list(network.getInetAddresses()))
            {
                if (!address.isLoopbackAddress() && !address.isAnyLocalAddress())
                {
                    elsewhere.add(address);
                }
            }
        }
        return elsewhere;
    }
}