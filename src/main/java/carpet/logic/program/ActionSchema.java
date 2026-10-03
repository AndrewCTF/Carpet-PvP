package carpet.logic.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The action schema in carpetlogic/actions.json: which action types exist and which parameters each takes.
 * It is the only place parameter names, types, defaults and ranges are written down. The interpreter reads
 * parameters through {@link Params}, and the web editor is sent the same file to compile against.
 */
public final class ActionSchema
{
    public static final String RESOURCE = "/carpetlogic/actions.json";
    public static final String ACTION = "action";
    public static final String CONTROL = "control";
    public static final String CONDITION = "condition";

    private static final int MAX_DEPTH = 64;
    private static final int MAX_ACTIONS = 10_000;
    private static final int MAX_STRING_LENGTH = 1024;

    public enum ParamType
    {
        INT, NUMBER, BOOL, STRING
    }

    /**
     * @param options the only values a string parameter may take, or empty when it is free text
     */
    public record Param(String name, ParamType type, Object defaultValue, double min, double max, List<String> options)
    {
        boolean accepts(Object value)
        {
            return switch (type)
            {
                case INT, NUMBER -> value instanceof Number number && Double.isFinite(number.doubleValue());
                case BOOL -> value instanceof Boolean;
                case STRING -> value instanceof String string && string.length() <= MAX_STRING_LENGTH
                        && (options.isEmpty() || options.contains(string));
            };
        }

        String expectation()
        {
            return switch (type)
            {
                case INT, NUMBER -> "a number";
                case BOOL -> "true or false";
                case STRING -> options.isEmpty() ? "text" : "one of " + options;
            };
        }
    }

    /**
     * @param requires the boolean carpet rule that must be on for the action to work, or null
     * @param slots which of children, elseChildren and condition an action of this type carries
     */
    public record Definition(String type, String kind, String requires, List<String> slots, Map<String, Param> params)
    {
    }

    /**
     * The parameters of one action. Asking for a parameter the schema does not declare for that action type,
     * or with the wrong type, is a programming error and throws.
     */
    public static final class Params
    {
        private final Definition definition;
        private final Map<String, Object> values;

        private Params(Definition definition, Map<String, Object> values)
        {
            this.definition = definition;
            this.values = values;
        }

        public int integer(String name)
        {
            return (int) Math.round(clamped(declared(name, ParamType.INT)));
        }

        public double number(String name)
        {
            return clamped(declared(name, ParamType.NUMBER));
        }

        public boolean bool(String name)
        {
            Param param = declared(name, ParamType.BOOL);
            return values.get(name) instanceof Boolean value ? value : (Boolean) param.defaultValue();
        }

        public String string(String name)
        {
            Param param = declared(name, ParamType.STRING);
            Object value = values.get(name);
            return param.accepts(value) ? (String) value : (String) param.defaultValue();
        }

        private double clamped(Param param)
        {
            Object value = values.get(param.name());
            double number = param.accepts(value) ? ((Number) value).doubleValue() : ((Number) param.defaultValue()).doubleValue();
            return Math.max(param.min(), Math.min(param.max(), number));
        }

        private Param declared(String name, ParamType type)
        {
            Param param = definition.params().get(name);
            if (param == null || param.type() != type)
            {
                throw new IllegalStateException(definition.type() + " declares no " + type.name().toLowerCase(Locale.ROOT) + " parameter '" + name + "'");
            }
            return param;
        }
    }

    private final JsonObject json;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();

    public static ActionSchema load()
    {
        try (InputStream in = ActionSchema.class.getResourceAsStream(RESOURCE))
        {
            if (in == null)
            {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            return read(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }

    public static ActionSchema read(Reader reader)
    {
        return new ActionSchema(JsonParser.parseReader(reader).getAsJsonObject());
    }

    private ActionSchema(JsonObject json)
    {
        this.json = json;
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("actions").entrySet())
        {
            String type = entry.getKey();
            JsonObject definition = entry.getValue().getAsJsonObject();
            String kind = definition.get("kind").getAsString();
            if (!List.of(ACTION, CONTROL, CONDITION).contains(kind))
            {
                throw new IllegalStateException(type + ": unknown kind '" + kind + "'");
            }
            List<String> slots = new ArrayList<>();
            if (definition.has("slots"))
            {
                definition.getAsJsonArray("slots").forEach(slot -> slots.add(slot.getAsString()));
            }
            Map<String, Param> params = new LinkedHashMap<>();
            for (JsonElement element : definition.getAsJsonArray("params"))
            {
                Param param = readParam(type, element.getAsJsonObject());
                params.put(param.name(), param);
            }
            String requires = definition.has("requires") ? definition.get("requires").getAsString() : null;
            definitions.put(type, new Definition(type, kind, requires, List.copyOf(slots), params));
        }
    }

    private static Param readParam(String action, JsonObject json)
    {
        String name = json.get("name").getAsString();
        ParamType type = ParamType.valueOf(json.get("type").getAsString().toUpperCase(Locale.ROOT));
        JsonElement defaultJson = json.get("default");
        Object defaultValue = switch (type)
        {
            case INT, NUMBER -> defaultJson.getAsDouble();
            case BOOL -> defaultJson.getAsBoolean();
            case STRING -> defaultJson.getAsString();
        };
        List<String> options = new ArrayList<>();
        if (json.has("options"))
        {
            JsonArray array = json.getAsJsonArray("options");
            array.forEach(option -> options.add(option.getAsString()));
        }
        Param param = new Param(name, type, defaultValue,
                json.has("min") ? json.get("min").getAsDouble() : -Double.MAX_VALUE,
                json.has("max") ? json.get("max").getAsDouble() : Double.MAX_VALUE,
                List.copyOf(options));
        if (!param.accepts(defaultValue))
        {
            throw new IllegalStateException(action + "." + name + ": the default is not " + param.expectation());
        }
        return param;
    }

    /**
     * @return the schema as it is written in the resource, for the web editor
     */
    public JsonObject json()
    {
        return json;
    }

    public Collection<Definition> definitions()
    {
        return definitions.values();
    }

    /**
     * @throws IllegalArgumentException when the action's type is not in the schema
     */
    public Definition definition(BotAction action)
    {
        Definition definition = action == null || action.getType() == null ? null : definitions.get(action.getType());
        if (definition == null)
        {
            throw new IllegalArgumentException("Unknown action type '" + (action == null ? null : action.getType()) + "'");
        }
        return definition;
    }

    public Params params(BotAction action)
    {
        return new Params(definition(action), action.getParams());
    }

    /**
     * Checks a whole program against the schema: known types, declared parameters with values of the right
     * type, and children and conditions only where the type has a slot for them.
     *
     * @throws IllegalArgumentException with the first problem found
     */
    public void validate(List<BotAction> actions)
    {
        validateSteps(actions == null ? List.of() : actions, 0, new int[1]);
    }

    private void validateSteps(List<BotAction> actions, int depth, int[] count)
    {
        for (BotAction action : actions)
        {
            Definition definition = validateAction(action, depth, count);
            if (definition.kind().equals(CONDITION))
            {
                throw new IllegalArgumentException(definition.type() + " is a condition, not a step");
            }
        }
    }

    private Definition validateAction(BotAction action, int depth, int[] count)
    {
        if (depth > MAX_DEPTH)
        {
            throw new IllegalArgumentException("Program is nested more than " + MAX_DEPTH + " levels deep");
        }
        if (++count[0] > MAX_ACTIONS)
        {
            throw new IllegalArgumentException("Program has more than " + MAX_ACTIONS + " actions");
        }
        Definition definition = definition(action);
        String type = definition.type();
        for (Map.Entry<String, Object> entry : action.getParams().entrySet())
        {
            Param param = definition.params().get(entry.getKey());
            if (param == null)
            {
                throw new IllegalArgumentException(type + " has no parameter '" + entry.getKey() + "'");
            }
            if (!param.accepts(entry.getValue()))
            {
                throw new IllegalArgumentException(type + "." + param.name() + " must be " + param.expectation());
            }
        }
        if (!action.getChildren().isEmpty() && !definition.slots().contains("children"))
        {
            throw new IllegalArgumentException(type + " cannot have children");
        }
        if (!action.getElseChildren().isEmpty() && !definition.slots().contains("elseChildren"))
        {
            throw new IllegalArgumentException(type + " cannot have elseChildren");
        }
        if (definition.slots().contains("condition"))
        {
            if (action.getCondition() == null)
            {
                throw new IllegalArgumentException(type + " needs a condition");
            }
            if (!validateAction(action.getCondition(), depth + 1, count).kind().equals(CONDITION))
            {
                throw new IllegalArgumentException(action.getCondition().getType() + " is not a condition");
            }
        }
        else if (action.getCondition() != null)
        {
            throw new IllegalArgumentException(type + " cannot have a condition");
        }
        validateSteps(action.getChildren(), depth + 1, count);
        validateSteps(action.getElseChildren(), depth + 1, count);
        return definition;
    }
}
