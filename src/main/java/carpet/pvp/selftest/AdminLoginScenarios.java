package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The admin sign-in of the CarpetLogic web editor against a real server, over HTTP the way a browser uses it:
 * an operator sets a password through the link the console hands out, signs in and changes a rule from the
 * Settings route, and everything that must not work does not.
 */
final class AdminLoginScenarios
{
    private static final String RULE = "carpetLogicMaxPrograms";
    private static final String SWITCH = "carpetLogicAdminLogin";
    private static final String PASSWORD = "self-test web password";
    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");
    private static final Pattern SETUP = Pattern.compile("#setup=([A-Za-z0-9_-]+)&name=([A-Za-z0-9_]+)");
    /** Ticks the scenario may take. While an answer is on its way a tick is two milliseconds at least. */
    private static final int TIMEOUT = 15_000;

    private AdminLoginScenarios() {}

    static Scenario adminLogin(String a, String b, String c, Vec3 origin)
    {
        Run run = new Run(a + "Op");
        return new Scenario(TIMEOUT, List.of(), List.of(), run::step);
    }

    private static final class Run
    {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final String operator;
        private int phase;
        private CompletableFuture<HttpResponse<String>> answer;
        private String url;
        private String ruleBefore;
        private String switchBefore;
        private String changed;
        private String fromTheGame;
        private String ticket;
        private String account;
        private String admin;
        private Probe result;

        Run(String operator)
        {
            this.operator = operator;
        }

        Probe step(MinecraftServer server)
        {
            if (result != null) return result;
            if (answer != null && !answer.isDone())
            {
                // A sprinting server would spend its ticks before the answer is back: HTTP takes real time.
                pause();
                return SelfTest.pending("waiting for the web editor to answer, step " + phase);
            }
            try
            {
                return advance(server);
            }
            catch (RuntimeException e)
            {
                return fail(server, "step " + phase + " threw " + e);
            }
        }

        private Probe advance(MinecraftServer server)
        {
            if (answer != null && answer.isCompletedExceptionally())
            {
                return fail(server, "the request of step " + phase + " failed: " + answer.exceptionNow());
            }
            HttpResponse<String> reply = answer == null ? null : answer.resultNow();
            switch (phase)
            {
                case 0:
                    url = SelfTest.webEditorUrl == null ? null : SelfTest.webEditorUrl.get();
                    if (url == null) return fail(server, "the web editor is not running");
                    ruleBefore = rule(RULE);
                    switchBefore = rule(SWITCH);
                    changed = ruleBefore.equals("7") ? "8" : "7";
                    SelfTest.run(server, "carpet " + SWITCH + " false");
                    fromTheGame = find(TOKEN, said(server, "carpetlogic open"), 1);
                    if (fromTheGame == null) return fail(server, "the console got no link from carpetlogic open");
                    return next(post("/api/login", null, json("name", operator, "password", PASSWORD)));
                case 1:
                    if (reply.statusCode() != 401 || reply.body().contains("adminLogin"))
                        return fail(server, "with the rule off /api/login answered " + shown(reply));
                    return next(post("/api/password", null, json("ticket", "none", "name", operator, "password", PASSWORD)));
                case 2:
                    if (reply.statusCode() != 401) return fail(server, "with the rule off /api/password answered " + shown(reply));
                    return next(post("/api/settings", fromTheGame, json("rule", RULE, "value", changed)));
                case 3:
                {
                    if (reply.statusCode() != 404 || !rule(RULE).equals(ruleBefore))
                        return fail(server, "with the rule off /api/settings answered " + shown(reply) + " and " + RULE + " is " + rule(RULE));
                    SelfTest.run(server, "carpet " + SWITCH + " true");
                    SelfTest.run(server, "op " + operator);
                    String console = said(server, "carpetlogic password " + operator);
                    ticket = find(SETUP, console, 1);
                    account = find(SETUP, console, 2);
                    if (ticket == null) return fail(server, "the console got no password link for " + operator + ", it was told: " + console);
                    return next(post("/api/password", null, json("ticket", ticket, "name", account, "password", PASSWORD)));
                }
                case 4:
                    if (reply.statusCode() != 200) return fail(server, "the link did not set a password: " + shown(reply));
                    return next(post("/api/password", null, json("ticket", ticket, "name", account, "password", PASSWORD + "2")));
                case 5:
                    if (reply.statusCode() != 403) return fail(server, "the link worked a second time: " + shown(reply));
                    return next(post("/api/login", null, json("name", account, "password", PASSWORD + "2")));
                case 6:
                    if (reply.statusCode() != 401 || reply.body().contains("token"))
                        return fail(server, "a wrong password was answered " + shown(reply));
                    return next(post("/api/login", null, json("name", account, "password", PASSWORD)));
                case 7:
                {
                    JsonObject body = reply.statusCode() == 200 ? parse(reply) : null;
                    if (body == null || !body.has("token") || !body.get("admin").getAsBoolean())
                        return fail(server, "the right password was answered " + shown(reply));
                    admin = body.get("token").getAsString();
                    return next(post("/api/settings", fromTheGame, json("rule", RULE, "value", changed)));
                }
                case 8:
                    if (reply.statusCode() != 403 || !rule(RULE).equals(ruleBefore))
                        return fail(server, "a link from carpetlogic open changed a rule: " + shown(reply) + ", " + RULE + " is " + rule(RULE));
                    return next(post("/api/settings", admin, json("rule", RULE, "value", changed)));
                case 9:
                    if (reply.statusCode() != 200 || !rule(RULE).equals(changed))
                        return fail(server, "the admin's change was answered " + shown(reply) + " and " + RULE + " is " + rule(RULE));
                    return next(post("/api/settings", admin, json("rule", RULE, "value", "-1")));
                case 10:
                    if (reply.statusCode() != 400 || !rule(RULE).equals(changed) || parse(reply).get("error").getAsString().isBlank())
                        return fail(server, "a value the rule refuses was answered " + shown(reply) + " and " + RULE + " is " + rule(RULE));
                    return next(request("/api/settings", admin).GET().build());
                case 11:
                {
                    String listed = reply.statusCode() == 200 ? listing(parse(reply)) : null;
                    if (listed == null) return fail(server, "the rules the panel shows are not as the server has them: " + shown(reply));
                    SelfTest.run(server, "deop " + account);
                    return next(post("/api/settings", admin, json("rule", RULE, "value", ruleBefore)));
                }
                default:
                {
                    if (reply.statusCode() != 403 || !rule(RULE).equals(changed))
                        return fail(server, "an operator who was deopped still changed a rule: " + shown(reply));
                    tidy(server);
                    boolean restored = rule(RULE).equals(ruleBefore) && rule(SWITCH).equals(switchBefore);
                    result = new Probe(restored, SelfTest.fmt(
                            "%s set a password through the console's link, signed in and changed %s from %s to %s; a second use of "
                                    + "the link, a wrong password, a link from carpetlogic open, a value the rule refuses and the "
                                    + "operator once deopped were all refused, as were the routes while %s was off; "
                                    + "the rules are back at %s and %s",
                            account, RULE, ruleBefore, changed, SWITCH, rule(RULE), rule(SWITCH)));
                    return result;
                }
            }
        }

        // The rule the panel was asked to change has to be in its list as the registry describes it, next to
        // the bots' defaults.
        private String listing(JsonObject settings)
        {
            if (!settings.has("rules")) return null;
            boolean changedRule = false;
            boolean botDefault = false;
            for (JsonElement element : settings.getAsJsonArray("rules"))
            {
                JsonObject rule = element.getAsJsonObject();
                String name = rule.get("name").getAsString();
                if (name.equals(RULE))
                {
                    changedRule = rule.get("value").getAsString().equals(changed) && rule.get("type").getAsString().equals("int")
                            && rule.get("group").getAsString().equals("editor") && !rule.get("description").getAsString().isBlank();
                }
                if (name.equals("botCombat"))
                {
                    botDefault = rule.get("group").getAsString().equals("bots") && rule.get("type").getAsString().equals("boolean");
                }
            }
            return changedRule && botDefault ? "listed" : null;
        }

        private Probe next(HttpRequest request)
        {
            answer = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            phase++;
            return SelfTest.pending("asked the web editor, step " + phase);
        }

        private HttpRequest.Builder request(String path, String token)
        {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(15));
            if (token != null) request.header("Authorization", "Bearer " + token);
            return request;
        }

        private HttpRequest post(String path, String token, String body)
        {
            return request(path, token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        }

        // Whatever went wrong, the operator and the rules go back before the scenario reports it.
        private Probe fail(MinecraftServer server, String detail)
        {
            tidy(server);
            result = new Probe(false, detail);
            return result;
        }

        private void tidy(MinecraftServer server)
        {
            SelfTest.run(server, "deop " + operator);
            if (ruleBefore != null) SelfTest.run(server, "carpet " + RULE + " " + ruleBefore);
            if (switchBefore != null) SelfTest.run(server, "carpet " + SWITCH + " " + switchBefore);
        }

        private static void pause()
        {
            try
            {
                Thread.sleep(2);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String rule(String name)
    {
        return String.valueOf(SelfTest.ruleSnapshot.get().get(name));
    }

    /** Runs a command as the console and answers what the console was told. */
    private static String said(MinecraftServer server, String command)
    {
        return SelfTest.consoleSays.apply(server, command);
    }

    private static String find(Pattern pattern, String text, int group)
    {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(group) : null;
    }

    private static String json(String... pairs)
    {
        JsonObject body = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) body.addProperty(pairs[i], pairs[i + 1]);
        return body.toString();
    }

    private static JsonObject parse(HttpResponse<String> reply)
    {
        return JsonParser.parseString(reply.body()).getAsJsonObject();
    }

    private static String shown(HttpResponse<String> reply)
    {
        String body = reply.body();
        return reply.statusCode() + " " + (body.length() > 160 ? body.substring(0, 160) + "..." : body);
    }
}
