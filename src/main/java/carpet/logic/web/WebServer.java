package carpet.logic.web;

import carpet.CarpetSettings;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The web editor's HTTP server, on the JDK's own com.sun.net.httpserver.
 * Static files are public; every /api/ route needs a token from {@link AuthManager}.
 * Request threads never touch the game: API calls are handed to the server thread.
 */
public class WebServer
{
    private static final Logger LOG = LogManager.getLogger("CarpetLogic");
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_STREAMS = 16;
    private static final int STREAM_QUEUE_SIZE = 64;
    private static final int SERVER_THREAD_TIMEOUT_SECONDS = 5;
    private static final int KEEP_ALIVE_SECONDS = 15;
    private static final Pattern STATIC_PATH = Pattern.compile("/[A-Za-z0-9_./-]+");
    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
            + "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "txt", "text/plain; charset=utf-8",
            "png", "image/png",
            "svg", "image/svg+xml");

    private interface Handler
    {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static class EventStream
    {
        final AuthManager.Session session;
        final BlockingQueue<String> queue = new ArrayBlockingQueue<>(STREAM_QUEUE_SIZE);
        volatile boolean closed;

        EventStream(AuthManager.Session session)
        {
            this.session = session;
        }

        void close()
        {
            closed = true;
            // Wakes the request thread if it is waiting for a message.
            queue.offer("");
        }
    }

    private final MinecraftServer server;
    private final AuthManager auth;
    private final Api api;
    private final HttpServer http;
    private final ExecutorService executor;
    private final String url;
    private final List<EventStream> streams = new CopyOnWriteArrayList<>();
    private int ticksSinceUpdate;
    private int matchRevision = -1;

    public WebServer(MinecraftServer server, AuthManager auth, Api api, String bindAddress, int port) throws IOException
    {
        this.server = server;
        this.auth = auth;
        this.api = api;
        InetAddress address = InetAddress.getByName(bindAddress);
        http = HttpServer.create(new InetSocketAddress(address, port), 0);
        http.createContext("/api/", exchange -> respond(exchange, this::handleApi));
        http.createContext("/", exchange -> respond(exchange, this::handleStatic));
        // One thread per request: an open event stream must not hold up other requests.
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("CarpetLogic-http-", 0).factory());
        http.setExecutor(executor);
        http.start();
        String host = address.isAnyLocalAddress() ? "localhost" : bindAddress.contains(":") ? "[" + bindAddress + "]" : bindAddress;
        url = "http://" + host + ":" + http.getAddress().getPort();
    }

    public String url()
    {
        return url;
    }

    public void stop()
    {
        streams.forEach(EventStream::close);
        http.stop(0);
        executor.shutdownNow();
    }

    /**
     * Called on the server thread every tick.
     */
    public void tick()
    {
        // Nothing is gathered while no editor is watching: the tick loop does not pay for a panel nobody has open.
        if (streams.isEmpty())
        {
            ticksSinceUpdate = 0;
            return;
        }
        if (++ticksSinceUpdate >= CarpetSettings.carpetLogicUpdateInterval)
        {
            broadcastBotUpdate();
        }
    }

    /**
     * Called on the server thread.
     */
    public void broadcastBotUpdate()
    {
        ticksSinceUpdate = 0;
        if (streams.isEmpty())
        {
            return;
        }
        for (EventStream stream : streams)
        {
            if (api.denied(stream.session) != null)
            {
                stream.close();
            }
        }
        streams.removeIf(stream -> stream.closed);
        broadcast(api.botUpdate());
        // The match list only travels when it changed: the panel fetches it when it opens.
        int revision = api.matchRevision();
        if (revision != matchRevision)
        {
            matchRevision = revision;
            broadcast(api.matchUpdate());
        }
    }

    public void log(String level, String message)
    {
        JsonObject log = new JsonObject();
        log.addProperty("type", "log");
        log.addProperty("level", level);
        log.addProperty("message", message);
        log.addProperty("timestamp", System.currentTimeMillis());
        broadcast(log);
    }

    // Only queues the message: the bytes are written by each stream's own request thread.
    private void broadcast(JsonObject message)
    {
        String json = message.toString();
        for (EventStream stream : streams)
        {
            if (!stream.queue.offer(json))
            {
                // A client that cannot keep up loses its backlog rather than holding up the server thread: it gets
                // the newest state instead and stays connected.
                stream.queue.poll();
                stream.queue.offer(json);
            }
        }
    }

    private void respond(HttpExchange exchange, Handler handler)
    {
        try
        {
            handler.handle(exchange);
        }
        catch (IOException e)
        {
            // the client went away
        }
        catch (RuntimeException e)
        {
            LOG.error("Web editor request {} {} failed", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), e);
            try
            {
                send(exchange, Api.error(500, "Internal error"));
            }
            catch (IOException | RuntimeException ignored)
            {
            }
        }
        finally
        {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange) throws IOException
    {
        String token = AuthManager.bearerToken(exchange.getRequestHeaders().getFirst("Authorization"));
        AuthManager.Session session = auth.validate(token);
        if (session == null)
        {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            send(exchange, Api.error(401, "Missing or expired token. Run /carpetlogic open in game for a link."));
            return;
        }
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) && "/api/events".equals(path))
        {
            streamEvents(exchange, session, token);
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES)
        {
            send(exchange, Api.error(413, "Request body is too large"));
            return;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        send(exchange, onServerThread(() -> api.handle(method, path, session, text)));
    }

    private void streamEvents(HttpExchange exchange, AuthManager.Session session, String token) throws IOException
    {
        EventStream stream = new EventStream(session);
        Api.Response refused = onServerThread(() ->
        {
            String denied = api.denied(session);
            if (denied != null)
            {
                return Api.error(403, denied);
            }
            if (streams.size() >= MAX_STREAMS)
            {
                return Api.error(503, "Too many editors are connected");
            }
            stream.queue.add(api.botUpdate().toString());
            streams.add(stream);
            return null;
        });
        if (refused != null)
        {
            send(exchange, refused);
            return;
        }
        try
        {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            while (true)
            {
                String message = stream.queue.poll(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
                if (stream.closed || auth.validate(token) == null)
                {
                    break;
                }
                String event = message == null ? ": keep-alive\n\n" : "data: " + message + "\n\n";
                out.write(event.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            streams.remove(stream);
        }
    }

    private Api.Response onServerThread(Supplier<Api.Response> work)
    {
        CompletableFuture<Api.Response> future = server.submit(work);
        try
        {
            return future.get(SERVER_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (TimeoutException e)
        {
            future.cancel(false);
            return Api.error(503, "The server did not answer in time");
        }
        catch (InterruptedException e)
        {
            future.cancel(false);
            Thread.currentThread().interrupt();
            return Api.error(503, "The server is shutting down");
        }
        catch (ExecutionException e)
        {
            LOG.error("Web editor request failed on the server thread", e.getCause());
            return Api.error(500, "Internal error");
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException
    {
        if (!"GET".equals(exchange.getRequestMethod()))
        {
            sendStatic(exchange, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path))
        {
            path = "/index.html";
        }
        // Only plain file names with a known extension, so nothing outside webui/ and no directory is ever served.
        String contentType = CONTENT_TYPES.get(path.substring(path.lastIndexOf('.') + 1));
        byte[] content = null;
        if (contentType != null && !path.contains("..") && STATIC_PATH.matcher(path).matches())
        {
            try (InputStream in = WebServer.class.getResourceAsStream("/webui" + path))
            {
                content = in == null ? null : in.readAllBytes();
            }
        }
        if (content == null)
        {
            sendStatic(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        sendStatic(exchange, 200, contentType, content);
    }

    private static void sendStatic(HttpExchange exchange, int status, String contentType, byte[] content) throws IOException
    {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        // The page only ever needs its own files. This keeps anything injected into it from running or phoning home.
        exchange.getResponseHeaders().set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content);
    }

    private static void send(HttpExchange exchange, Api.Response response) throws IOException
    {
        byte[] content = response.body().toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(response.status(), content.length);
        exchange.getResponseBody().write(content);
    }
}
