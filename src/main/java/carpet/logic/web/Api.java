package carpet.logic.web;

import carpet.CarpetSettings;
import carpet.logic.CarpetLogic;
import carpet.logic.bot.BotManager;
import carpet.logic.program.BotAction;
import carpet.logic.program.BotProgram;
import carpet.logic.program.ProgramStorage;
import carpet.utils.CommandHelper;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.security.SecureRandom;
import java.util.List;

/**
 * The web editor's API. Everything here runs on the server thread, on behalf of the session a request's
 * token belongs to.
 */
public class Api
{
    public record Response(int status, JsonElement body)
    {
    }

    private static final Gson GSON = new Gson();
    private static final int MAX_NAME_LENGTH = 64;

    private final MinecraftServer server;
    private final CarpetLogic logic;
    private final SecureRandom random = new SecureRandom();

    public Api(MinecraftServer server, CarpetLogic logic)
    {
        this.server = server;
        this.logic = logic;
    }

    /**
     * @return why this session may not use the API right now, or null when it may
     */
    public String denied(AuthManager.Session session)
    {
        if (session.owner() == null)
        {
            return null;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(session.owner());
        if (owner == null)
        {
            return "The player this link was issued to is not online";
        }
        if (!CommandHelper.canUseCommand(owner.createCommandSourceStack(), CarpetSettings.commandCarpetLogic))
        {
            return "The player this link was issued to may no longer use /carpetlogic";
        }
        return null;
    }

    public Response handle(String method, String path, AuthManager.Session session, String body)
    {
        String denied = denied(session);
        if (denied != null)
        {
            return error(403, denied);
        }
        if (!"GET".equals(method) && CarpetSettings.carpetLogicViewerMode)
        {
            return error(403, "The web editor is in viewer mode (carpetLogicViewerMode)");
        }
        try
        {
            return switch (method + " " + path)
            {
                case "GET /api/status" -> ok(status(session));
                case "GET /api/settings" -> ok(settings());
                case "GET /api/schema" -> ok(logic.getSchema().json());
                case "GET /api/programs" -> ok(GSON.toJsonTree(logic.getProgramStorage().getAllPrograms()));
                case "GET /api/presets" -> ok(GSON.toJsonTree(logic.getProgramStorage().getPresets()));
                case "POST /api/programs" -> saveProgram(parse(body));
                case "GET /api/bots" -> ok(bots());
                case "POST /api/bots/spawn" -> spawnBot(parse(body), session);
                case "POST /api/bots/kill" -> killBot(parse(body));
                case "POST /api/execute" -> execute(parse(body), session);
                case "POST /api/stop" -> stop(parse(body));
                default ->
                {
                    if ("DELETE".equals(method) && path.startsWith("/api/programs/"))
                    {
                        yield deleteProgram(path.substring("/api/programs/".length()));
                    }
                    yield error(404, "Unknown API endpoint");
                }
            };
        }
        catch (JsonParseException e)
        {
            return error(400, "Request body is not valid JSON for this endpoint");
        }
        catch (IllegalArgumentException e)
        {
            return error(400, e.getMessage());
        }
    }

    public JsonObject botUpdate()
    {
        JsonObject update = new JsonObject();
        update.addProperty("type", "botUpdate");
        update.add("bots", bots());
        update.add("programs", GSON.toJsonTree(logic.getProgramExecutor().getPrograms()));
        return update;
    }

    private JsonObject status(AuthManager.Session session)
    {
        JsonObject status = new JsonObject();
        status.addProperty("version", CarpetSettings.carpetVersion);
        status.addProperty("user", session.ownerName());
        status.addProperty("viewerMode", CarpetSettings.carpetLogicViewerMode);
        status.addProperty("activeBots", logic.getBotManager().getBots().size());
        status.addProperty("runningPrograms", logic.getProgramExecutor().getRunningCount());
        status.addProperty("savedPrograms", logic.getProgramStorage().getCount());
        status.addProperty("maxPrograms", CarpetSettings.carpetLogicMaxPrograms);
        return status;
    }

    private JsonObject settings()
    {
        JsonObject settings = new JsonObject();
        settings.addProperty("commandCarpetLogic", CarpetSettings.commandCarpetLogic);
        settings.addProperty("carpetLogicPort", CarpetSettings.carpetLogicPort);
        settings.addProperty("carpetLogicBindAddress", CarpetSettings.carpetLogicBindAddress);
        settings.addProperty("carpetLogicSessionHours", CarpetSettings.carpetLogicSessionHours);
        settings.addProperty("carpetLogicUpdateInterval", CarpetSettings.carpetLogicUpdateInterval);
        settings.addProperty("carpetLogicMaxPrograms", CarpetSettings.carpetLogicMaxPrograms);
        settings.addProperty("carpetLogicViewerMode", CarpetSettings.carpetLogicViewerMode);
        settings.addProperty("fakePlayerNavigation", CarpetSettings.fakePlayerNavigation);
        settings.addProperty("fakePlayerElytraGlide", CarpetSettings.fakePlayerElytraGlide);
        settings.addProperty("swordBlockHitting", CarpetSettings.swordBlockHitting);
        return settings;
    }

    private JsonObject bots()
    {
        JsonObject bots = new JsonObject();
        for (ServerPlayer bot : logic.getBotManager().getBots())
        {
            bots.add(bot.getGameProfile().name(), BotManager.describe(bot));
        }
        return bots;
    }

    private Response saveProgram(JsonObject request)
    {
        BotProgram program = GSON.fromJson(request, BotProgram.class);
        ProgramStorage storage = logic.getProgramStorage();
        if (program.getId() == null || program.getId().isEmpty())
        {
            program.setId("p" + Long.toHexString(random.nextLong()));
        }
        BotProgram existing = storage.getById(program.getId());
        long now = System.currentTimeMillis();
        program.setCreatedAt(existing != null && !existing.isPreset() ? existing.getCreatedAt() : now);
        program.setUpdatedAt(now);
        program.setPreset(false);
        program.setName(cleanName(program.getName()));
        storage.save(program);

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("id", program.getId());
        return ok(result);
    }

    private Response deleteProgram(String id)
    {
        if (!ProgramStorage.isValidId(id))
        {
            return error(400, "Invalid program id");
        }
        return success(logic.getProgramStorage().delete(id));
    }

    private Response spawnBot(JsonObject request, AuthManager.Session session)
    {
        String name = string(request, "name");
        ServerPlayer owner = session.owner() == null ? null : server.getPlayerList().getPlayer(session.owner());
        ResourceKey<Level> dimension = owner != null ? owner.level().dimension() : Level.OVERWORLD;
        Vec3 pos;
        if (request.has("x") && request.has("y") && request.has("z"))
        {
            pos = new Vec3(number(request, "x"), number(request, "y"), number(request, "z"));
        }
        else if (owner != null)
        {
            pos = owner.position();
        }
        else
        {
            pos = Vec3.atBottomCenterOf(server.getRespawnData().pos());
        }
        String refused = logic.getBotManager().spawn(name, pos, owner != null ? owner.getYRot() : 0, owner != null ? owner.getXRot() : 0, dimension);
        if (refused != null)
        {
            return error(400, refused);
        }
        // The fake player joins a moment later, once its profile has been resolved.
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("name", name);
        result.addProperty("pending", true);
        return ok(result);
    }

    private Response killBot(JsonObject request)
    {
        String name = string(request, "name");
        logic.getProgramExecutor().stopProgram(name);
        return success(logic.getBotManager().kill(name));
    }

    // Programs are run from the action tree the editor sends, never by id: commands in a program run as the
    // token's player, so that player must be the one who had the program in front of them.
    private Response execute(JsonObject request, AuthManager.Session session)
    {
        if (!request.has("actions") || !request.get("actions").isJsonArray())
        {
            return error(400, "'actions' must be an array");
        }
        BotProgram program = new BotProgram("_unsaved", cleanName(string(request, "name")), "");
        program.setActions(GSON.fromJson(request.get("actions"), new TypeToken<List<BotAction>>() {}.getType()));
        logic.getSchema().validate(program.getActions());
        String refused = logic.getProgramExecutor().startProgram(string(request, "botName"), program, session.owner());
        return refused == null ? success(true) : error(409, refused);
    }

    private Response stop(JsonObject request)
    {
        return success(logic.getProgramExecutor().stopProgram(string(request, "botName")));
    }

    private static String cleanName(String name)
    {
        if (name == null || name.isBlank())
        {
            return "Untitled";
        }
        name = name.strip();
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    private static JsonObject parse(String body)
    {
        JsonElement json = JsonParser.parseString(body);
        if (!json.isJsonObject())
        {
            throw new IllegalArgumentException("Request body must be a JSON object");
        }
        return json.getAsJsonObject();
    }

    private static String string(JsonObject object, String key)
    {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static double number(JsonObject object, String key)
    {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
        {
            throw new IllegalArgumentException("'" + key + "' must be a number");
        }
        return value.getAsDouble();
    }

    private static Response ok(JsonElement body)
    {
        return new Response(200, body);
    }

    private static Response success(boolean success)
    {
        JsonObject result = new JsonObject();
        result.addProperty("success", success);
        return ok(result);
    }

    public static Response error(int status, String message)
    {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        return new Response(status, error);
    }
}
