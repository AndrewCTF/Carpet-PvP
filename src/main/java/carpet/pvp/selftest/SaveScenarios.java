package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saving from the CarpetLogic web editor against a real server, over HTTP the way the page does it: a program
 * that does not compile yet is kept as a draft in a file named after it, comes back unchanged after the folder
 * is read again, is not run, and runs once a version that compiles has been saved over it.
 */
final class SaveScenarios
{
    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");
    private static final String REASON = "An If / Else node has no condition connected";
    private static final String WALK = "[{\"type\": \"MOVE\", \"params\": {\"direction\": \"forward\", \"ticks\": 40}}, {\"type\": \"STOP_MOVEMENT\"}]";
    /** Ticks the scenario may take. While an answer is on its way a tick is two milliseconds at least. */
    private static final int TIMEOUT = 15_000;

    private SaveScenarios() {}

    static Scenario saveDraftAndAutosave(String a, String b, String c, Vec3 origin)
    {
        Run run = new Run(a, origin);
        return new Scenario(TIMEOUT, List.of(new Bot(a, origin)), List.of(), run::step);
    }

    private static final class Run
    {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final String bot;
        private final Vec3 origin;
        private final String draftName;
        private final String readyName;
        private final JsonObject graph = new JsonObject();
        private final List<Path> files = new ArrayList<>();
        private int phase;
        private CompletableFuture<HttpResponse<String>> answer;
        private String url;
        private String token;
        private String id;
        private long updatedAt;
        private long modified;
        private Path draftFile;
        private Path readyFile;
        private Probe result;

        Run(String bot, Vec3 origin)
        {
            this.bot = bot;
            this.origin = origin;
            this.draftName = "Self save: " + bot + "/draft";
            this.readyName = "Self save " + bot + " ready";
            graph.addProperty("marker", "graph of " + bot);
            graph.add("nodes", new JsonArray());
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
            catch (RuntimeException | IOException e)
            {
                return fail("step " + phase + " threw " + e);
            }
        }

        private Probe advance(MinecraftServer server) throws IOException
        {
            if (answer != null && answer.isCompletedExceptionally())
            {
                return fail("the request of step " + phase + " failed: " + answer.exceptionNow());
            }
            HttpResponse<String> reply = answer == null ? null : answer.resultNow();
            Path folder = server.getWorldPath(LevelResource.ROOT).resolve("carpetlogic").resolve("programs").toAbsolutePath().normalize();
            switch (phase)
            {
                case 0:
                {
                    url = SelfTest.webEditorUrl == null ? null : SelfTest.webEditorUrl.get();
                    if (url == null) return fail("the web editor is not running");
                    Matcher link = TOKEN.matcher(SelfTest.consoleSays.apply(server, "carpetlogic open"));
                    if (!link.find()) return fail("the console got no link from carpetlogic open");
                    token = link.group(1);
                    // A graph that does not compile: the page sends it with the reason and no actions.
                    JsonObject draft = program(draftName, null);
                    draft.addProperty("error", REASON);
                    return next(post("/api/programs", draft));
                }
                case 1:
                {
                    JsonObject saved = reply.statusCode() == 200 ? parse(reply) : null;
                    if (saved == null || !saved.get("draft").getAsBoolean()) return fail("the draft was answered " + shown(reply));
                    id = saved.get("id").getAsString();
                    updatedAt = saved.get("updatedAt").getAsLong();
                    draftFile = Path.of(saved.get("file").getAsString()).toAbsolutePath().normalize();
                    files.add(draftFile);
                    // The name holds a colon, a space and a slash: none of them may reach the file system.
                    Path expected = folder.resolve("Self-save-" + bot + "-draft.json");
                    if (!draftFile.equals(expected) || !Files.isRegularFile(draftFile))
                        return fail("the draft is said to be in " + draftFile + ", expected the file " + expected);
                    String written = Files.readString(draftFile);
                    if (!written.contains("graph of " + bot) || !written.contains(REASON))
                        return fail(draftFile + " does not hold the graph and the reason");
                    modified = Files.getLastModifiedTime(draftFile).toMillis();
                    // What a server start does: everything is read from the folder again.
                    SelfTest.reloadPrograms.run();
                    return next(request("/api/programs").GET().build());
                }
                case 2:
                {
                    JsonObject listed = reply.statusCode() == 200 ? find(reply) : null;
                    if (listed == null) return fail("after the folder was read again the draft is not listed: " + shown(reply));
                    boolean same = graph.equals(listed.get("graphData")) && listed.get("draft").getAsBoolean()
                            && REASON.equals(listed.get("error").getAsString()) && draftName.equals(listed.get("name").getAsString())
                            && listed.getAsJsonArray("actions").isEmpty();
                    if (!same) return fail("the draft did not come back as it was saved: " + listed);
                    String refusal = SelfTest.consoleSays.apply(server, "carpetlogic programs run \"" + draftName + "\" " + bot);
                    if (!refusal.contains("does not run yet") || !refusal.contains(REASON) || !SelfTest.status(bot).equals("gone"))
                        return fail("running the draft was answered '" + refusal + "' and the program is " + SelfTest.status(bot));
                    // Saving what is already there, as an editor that saves by itself does, writes nothing.
                    JsonObject again = program(draftName, updatedAt);
                    again.addProperty("error", REASON);
                    return next(post("/api/programs", again));
                }
                case 3:
                {
                    JsonObject saved = reply.statusCode() == 200 ? parse(reply) : null;
                    boolean untouched = saved != null && saved.get("unchanged").getAsBoolean() && saved.get("updatedAt").getAsLong() == updatedAt
                            && Files.getLastModifiedTime(draftFile).toMillis() == modified;
                    if (!untouched) return fail("saving the same draft again was answered " + shown(reply) + " or rewrote the file");
                    // A tab that still holds an older copy.
                    JsonObject stale = program(readyName, updatedAt - 1);
                    stale.add("actions", JsonParser.parseString(WALK));
                    return next(post("/api/programs", stale));
                }
                case 4:
                {
                    boolean refused = reply.statusCode() == 409 && parse(reply).get("conflict").getAsBoolean()
                            && parse(reply).get("updatedAt").getAsLong() == updatedAt && Files.readString(draftFile).contains(REASON);
                    if (!refused) return fail("a save from an older copy was answered " + shown(reply));
                    JsonObject ready = program(readyName, updatedAt);
                    ready.add("actions", JsonParser.parseString(WALK));
                    return next(post("/api/programs", ready));
                }
                case 5:
                {
                    JsonObject saved = reply.statusCode() == 200 ? parse(reply) : null;
                    if (saved == null || saved.get("draft").getAsBoolean() || saved.get("updatedAt").getAsLong() <= updatedAt
                            || !saved.get("id").getAsString().equals(id))
                        return fail("the version that compiles was answered " + shown(reply));
                    readyFile = Path.of(saved.get("file").getAsString()).toAbsolutePath().normalize();
                    files.add(readyFile);
                    // The program was renamed in the same save, and its file with it.
                    if (!readyFile.equals(folder.resolve("Self-save-" + bot + "-ready.json")) || !Files.isRegularFile(readyFile) || Files.exists(draftFile))
                        return fail("after the rename the program is in " + readyFile + " and the old file " + (Files.exists(draftFile) ? "is still there" : "is gone"));
                    String started = SelfTest.consoleSays.apply(server, "carpetlogic programs run \"" + readyName + "\" " + bot);
                    if (!started.contains("Started")) return fail("running the saved program was answered '" + started + "'");
                    phase++;
                    answer = null;
                    return SelfTest.pending(bot + " is running the saved program");
                }
                case 6:
                {
                    double walked = SelfTest.player(server, bot).position().distanceTo(origin);
                    String status = SelfTest.status(bot);
                    if (walked < 3.0D || !status.equals("COMPLETED"))
                        return SelfTest.pending(SelfTest.fmt("%s walked %.1f blocks, its program is %s", bot, walked, status));
                    return next(request("/api/programs/" + id).DELETE().build());
                }
                default:
                {
                    boolean gone = reply.statusCode() == 200 && parse(reply).get("success").getAsBoolean() && !Files.exists(readyFile);
                    tidy();
                    result = new Probe(gone, SelfTest.fmt(
                            "a draft was saved to %s with its graph and the reason, came back the same after the folder was read again "
                                    + "and was refused by programs run; saving it again wrote nothing and a save from an older copy was "
                                    + "refused; the version that compiles was saved over it as %s, ran on %s, which walked %.1f blocks, "
                                    + "and deleting it %s",
                            draftFile.getFileName(), readyFile.getFileName(), bot,
                            SelfTest.player(server, bot).position().distanceTo(origin), gone ? "removed the file" : "left the file behind"));
                    return result;
                }
            }
        }

        private JsonObject program(String name, Long base)
        {
            JsonObject program = new JsonObject();
            if (id != null) program.addProperty("id", id);
            program.addProperty("name", name);
            program.add("graphData", graph);
            program.add("actions", new JsonArray());
            if (base != null) program.addProperty("baseUpdatedAt", base);
            return program;
        }

        private JsonObject find(HttpResponse<String> reply)
        {
            for (JsonElement element : JsonParser.parseString(reply.body()).getAsJsonArray())
            {
                if (element.getAsJsonObject().get("id").getAsString().equals(id)) return element.getAsJsonObject();
            }
            return null;
        }

        private Probe next(HttpRequest request)
        {
            answer = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            phase++;
            return SelfTest.pending("asked the web editor, step " + phase);
        }

        private HttpRequest.Builder request(String path)
        {
            return HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token);
        }

        private HttpRequest post(String path, JsonObject body)
        {
            return request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        }

        // Whatever went wrong, the files this scenario made are gone before it reports it.
        private Probe fail(String detail)
        {
            tidy();
            result = new Probe(false, detail);
            return result;
        }

        private void tidy()
        {
            for (Path file : files)
            {
                try
                {
                    Files.deleteIfExists(file);
                }
                catch (IOException e)
                {
                    // reported by the next scenario that finds it, not worth failing this one twice
                }
            }
            SelfTest.reloadPrograms.run();
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

    private static JsonObject parse(HttpResponse<String> reply)
    {
        return JsonParser.parseString(reply.body()).getAsJsonObject();
    }

    private static String shown(HttpResponse<String> reply)
    {
        String body = reply.body();
        return reply.statusCode() + " " + (body.length() > 200 ? body.substring(0, 200) + "..." : body);
    }
}
