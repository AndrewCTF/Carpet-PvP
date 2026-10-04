package carpet.logic.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import carpet.pvp.BotPvpConfig;

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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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
    /** The actions whose children run more than once, which is what BREAK and CONTINUE act on. */
    public static final Set<String> LOOPS = Set.of("LOOP", "FOREVER", "WHILE", "FOR_EACH");

    public enum ParamType
    {
        INT, NUMBER, BOOL, STRING, EXPR
    }

    // A number written as text is not an expression: it is a number that should have been written as one.
    private static final Pattern NUMERAL = Pattern.compile("-?(\\d+(\\.\\d+)?|\\.\\d+)");
    private static final int MAX_PARSED = 4096;

    /**
     * How a number parameter names a variable, and how many variables one program may hold. Written down in the
     * schema, so the interpreter and the web editor agree on what a reference looks like.
     *
     * @param names what may follow the prefix
     */
    public record Variables(String prefix, Pattern names, int limit)
    {
        public boolean isReference(Object value)
        {
            return value instanceof String string && string.startsWith(prefix);
        }

        public boolean isName(String name)
        {
            return names.matcher(name).matches();
        }
    }

    /**
     * The parameters a string may take, where they do not depend on the running server: the combat styles
     * {@code /bot spawn} takes and the difficulty presets it applies, both generated from the enums rather
     * than written down, so that a style added to the bot appears here without anyone touching this file.
     */
    private static final Map<String, Supplier<List<String>>> GENERATED_OPTIONS = Map.of(
            "combatStyles", () -> List.of(BotPvpConfig.styles()),
            "difficulties", () -> List.of(BotPvpConfig.difficulties()));

    /**
     * @param options the only values a string parameter may take, or empty when it is free text
     * @param returns what an expr parameter's expression has to give, or null for the other types
     */
    public record Param(String name, ParamType type, Object defaultValue, double min, double max, List<String> options, Expression.Type returns)
    {
        String expectation()
        {
            return switch (type)
            {
                case INT, NUMBER -> "a number or an expression";
                case BOOL -> "true or false";
                case STRING -> options.isEmpty() ? "text" : "one of " + options;
                case EXPR -> "an expression";
            };
        }
    }

    /**
     * @param requires the boolean carpet rule that must be on for the action to work, or null
     * @param drivesBody whether the action moves the bot or clicks for it, which a combat node does not allow
     * @param slots which of children, elseChildren and condition an action of this type carries
     */
    public record Definition(String type, String kind, String requires, boolean drivesBody, List<String> slots, Map<String, Param> params)
    {
    }

    /**
     * The parameters of one action. Asking for a parameter the schema does not declare for that action type,
     * or with the wrong type, is a programming error and throws. A parameter that holds an expression is
     * evaluated when it is asked for; one that cannot be evaluated throws {@link BotActionException}.
     */
    public final class Params
    {
        private final Definition definition;
        private final Map<String, Object> values;
        private final Expression.Context context;

        private Params(Definition definition, Map<String, Object> values, Expression.Context context)
        {
            this.definition = definition;
            this.values = values;
            this.context = context;
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
            return problem(param, value) == null ? (String) value : (String) param.defaultValue();
        }

        /** What an expr parameter's expression gives: a Double, a Boolean, a String or a List. */
        public Object value(String name)
        {
            Param param = declared(name, ParamType.EXPR);
            Object value = values.get(name);
            String source = problem(param, value) == null ? (String) value : (String) param.defaultValue();
            return evaluate(param, source, param.returns());
        }

        /** Whether an expr parameter that gives true or false holds. */
        public boolean truth(String name)
        {
            return (Boolean) value(name);
        }

        private double clamped(Param param)
        {
            Object value = values.get(param.name());
            double number;
            if (value instanceof String source && problem(param, value) == null)
            {
                number = (Double) evaluate(param, source, Expression.Type.NUMBER);
            }
            else
            {
                number = problem(param, value) == null ? ((Number) value).doubleValue() : ((Number) param.defaultValue()).doubleValue();
            }
            return Math.max(param.min(), Math.min(param.max(), number));
        }

        private Object evaluate(Param param, String source, Expression.Type expected)
        {
            try
            {
                Object result = expression(source, expected).evaluate(context);
                Expression.Type type = Expression.typeOf(result);
                if (expected != Expression.Type.ANY && type != expected)
                {
                    throw new ExpressionException("Expected " + expected.words() + " but got " + type.words(), -1);
                }
                if (result instanceof Double number && number.isNaN())
                {
                    throw new ExpressionException("The result is not a number", -1);
                }
                return result;
            }
            catch (ExpressionException e)
            {
                throw new BotActionException(definition.type() + "." + param.name() + ": " + e.getMessage());
            }
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

    /** What an expression can read where no program is running: the variables it is handed, and nothing of a bot. */
    private record Outside(Map<String, ?> store) implements Expression.Context
    {
        @Override
        public Object variable(String name)
        {
            return store.get(name);
        }

        @Override
        public Object value(String name)
        {
            throw new ExpressionException("'" + name + "' can only be read while a program runs", -1);
        }

        @Override
        public Object call(String function, List<Object> args)
        {
            throw new ExpressionException("'" + function + "' can only be used while a program runs", -1);
        }

        @Override
        public void charge(int steps)
        {
        }
    }

    private final JsonObject json;
    private final Variables variables;
    private final Expression.Vocabulary vocabulary;
    // Expressions are parsed once, when a program is checked, and found again every time it runs them.
    private final Map<String, Expression> parsed = new ConcurrentHashMap<>();
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
        JsonObject variables = json.getAsJsonObject("variables");
        this.variables = new Variables(variables.get("referencePrefix").getAsString(),
                Pattern.compile(variables.get("namePattern").getAsString()), variables.get("maxVariables").getAsInt());
        this.vocabulary = Expression.Vocabulary.read(json.getAsJsonObject("expressions"), this.variables.prefix().charAt(0));
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
            boolean drivesBody = definition.has("drivesBody") && definition.get("drivesBody").getAsBoolean();
            definitions.put(type, new Definition(type, kind, requires, drivesBody, List.copyOf(slots), params));
        }
    }

    private Param readParam(String action, JsonObject json)
    {
        String name = json.get("name").getAsString();
        ParamType type = ParamType.valueOf(json.get("type").getAsString().toUpperCase(Locale.ROOT));
        JsonElement defaultJson = json.get("default");
        Object defaultValue = switch (type)
        {
            case INT, NUMBER -> defaultJson.getAsDouble();
            case BOOL -> defaultJson.getAsBoolean();
            case STRING, EXPR -> defaultJson.getAsString();
        };
        List<String> options = new ArrayList<>();
        if (json.has("options"))
        {
            JsonArray array = json.getAsJsonArray("options");
            array.forEach(option -> options.add(option.getAsString()));
        }
        if (json.has("optionsFrom"))
        {
            String from = json.get("optionsFrom").getAsString();
            Supplier<List<String>> generated = GENERATED_OPTIONS.get(from);
            if (generated == null)
            {
                throw new IllegalStateException(action + "." + name + ": nothing generates the options of '" + from + "'");
            }
            options.addAll(generated.get());
            // what the web editor is sent has to be the same list the interpreter checks against
            JsonArray resolved = new JsonArray();
            options.forEach(resolved::add);
            json.add("options", resolved);
        }
        Expression.Type returns = type != ParamType.EXPR ? null
                : Expression.Type.valueOf((json.has("returns") ? json.get("returns").getAsString() : "any").toUpperCase(Locale.ROOT));
        Param param = new Param(name, type, defaultValue,
                json.has("min") ? json.get("min").getAsDouble() : -Double.MAX_VALUE,
                json.has("max") ? json.get("max").getAsDouble() : Double.MAX_VALUE,
                List.copyOf(options), returns);
        if (problem(param, defaultValue) != null)
        {
            throw new IllegalStateException(action + "." + name + ": the default is not " + param.expectation());
        }
        return param;
    }

    /**
     * @return what is wrong with a value for the parameter, as it follows the parameter's name in a message,
     *         or null when the parameter takes it
     */
    private String problem(Param param, Object value)
    {
        return switch (param.type())
        {
            case INT, NUMBER ->
            {
                if (value instanceof Number number && Double.isFinite(number.doubleValue()))
                {
                    yield null;
                }
                yield value instanceof String source && !NUMERAL.matcher(source.strip()).matches()
                        ? expressionProblem(source, Expression.Type.NUMBER) : " must be " + param.expectation();
            }
            case BOOL -> value instanceof Boolean ? null : " must be " + param.expectation();
            case STRING -> value instanceof String string && string.length() <= MAX_STRING_LENGTH
                    && (param.options().isEmpty() || param.options().contains(string)) ? null : " must be " + param.expectation();
            case EXPR -> value instanceof String source ? expressionProblem(source, param.returns()) : " must be " + param.expectation();
        };
    }

    private String expressionProblem(String source, Expression.Type expected)
    {
        try
        {
            expression(source, expected);
            return null;
        }
        catch (ExpressionException e)
        {
            return ": " + e.getMessage();
        }
    }

    /**
     * @throws ExpressionException when the source is not an expression, or cannot give what is expected
     */
    private Expression expression(String source, Expression.Type expected)
    {
        String key = expected.name() + " " + source;
        Expression expression = parsed.get(key);
        if (expression == null)
        {
            expression = Expression.parse(source, vocabulary, expected);
            if (parsed.size() >= MAX_PARSED)
            {
                parsed.clear();
            }
            parsed.put(key, expression);
        }
        return expression;
    }

    /** The names and functions an expression may use. */
    public Expression.Vocabulary vocabulary()
    {
        return vocabulary;
    }

    /**
     * @return the schema as it is written in the resource, for the web editor
     */
    public JsonObject json()
    {
        return json;
    }

    public Variables variables()
    {
        return variables;
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
        return params(action, Map.of());
    }

    /**
     * @param store the variables an expression may read; nothing about a bot can be read through this one
     */
    public Params params(BotAction action, Map<String, ?> store)
    {
        return params(action, new Outside(store));
    }

    /**
     * @param context what the expressions among the parameters read: the running program's variables and its bot
     */
    public Params params(BotAction action, Expression.Context context)
    {
        return new Params(definition(action), action.getParams(), context);
    }

    /**
     * Checks a whole program against the schema: known types, declared parameters with values of the right
     * type, and children and conditions only where the type has a slot for them.
     *
     * @throws IllegalArgumentException with the first problem found
     */
    public void validate(List<BotAction> actions)
    {
        validateSteps(actions == null ? List.of() : actions, 0, new int[1], false);
    }

    private void validateSteps(List<BotAction> actions, int depth, int[] count, boolean inLoop)
    {
        for (BotAction action : actions)
        {
            Definition definition = validateAction(action, depth, count, inLoop);
            if (definition.kind().equals(CONDITION))
            {
                throw new IllegalArgumentException(definition.type() + " is a condition, not a step");
            }
            if (!inLoop && (definition.type().equals("BREAK") || definition.type().equals("CONTINUE")))
            {
                throw new IllegalArgumentException(definition.type() + " is not inside a loop");
            }
        }
    }

    private void validateCondition(BotAction condition, int depth, int[] count)
    {
        if (!validateAction(condition, depth, count, false).kind().equals(CONDITION))
        {
            throw new IllegalArgumentException(condition.getType() + " is not a condition");
        }
    }

    private Definition validateAction(BotAction action, int depth, int[] count, boolean inLoop)
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
            String problem = problem(param, entry.getValue());
            if (problem != null)
            {
                throw new IllegalArgumentException(type + "." + param.name() + problem);
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
            validateCondition(action.getCondition(), depth + 1, count);
        }
        else if (action.getCondition() != null)
        {
            throw new IllegalArgumentException(type + " cannot have a condition");
        }
        if (!action.getConditions().isEmpty() && !definition.slots().contains("conditions"))
        {
            throw new IllegalArgumentException(type + " cannot have conditions");
        }
        for (BotAction condition : action.getConditions())
        {
            validateCondition(condition, depth + 1, count);
        }
        // A loop's body may break out of it; what an event sets off is a sequence of its own and may not.
        boolean loops = LOOPS.contains(type) || inLoop && !type.equals("ON_EVENT");
        validateSteps(action.getChildren(), depth + 1, count, loops);
        validateSteps(action.getElseChildren(), depth + 1, count, loops);
        return definition;
    }
}
