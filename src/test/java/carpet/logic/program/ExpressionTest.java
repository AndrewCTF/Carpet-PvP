package carpet.logic.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionTest
{
    private static final Path ROOT = findRoot();
    private static final Expression.Vocabulary VOCABULARY = readVocabulary();

    private static Path findRoot()
    {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("src/test/js")))
        {
            directory = directory.getParent();
        }
        assertNotNull(directory, "no directory above the working directory has src/test/js");
        return directory;
    }

    private static JsonElement readJson(String relative)
    {
        try
        {
            return JsonParser.parseString(Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static Expression.Vocabulary readVocabulary()
    {
        return Expression.Vocabulary.read(readJson("src/main/resources/carpetlogic/actions.json").getAsJsonObject().getAsJsonObject("expressions"));
    }

    /** The fixed world of the shared table; every step charged is counted. */
    private static class FixedContext implements Expression.Context
    {
        int steps;
        final List<String> calls = new ArrayList<>();
        final Map<String, Object> values = new HashMap<>();
        final Map<String, Object> overrides = new HashMap<>();

        FixedContext()
        {
            Object[][] numbers = {
                {"health", 14}, {"max_health", 20}, {"food", 18}, {"armor", 8}, {"x", 10.5}, {"y", 64}, {"z", -3}, {"yaw", 90}, {"pitch", 0},
                {"held_count", 1}, {"hotbar_slot", 2}, {"target_distance", 3.5}, {"target_health", 6}, {"tick", 40}, {"random", 0.25}};
            for (Object[] entry : numbers)
            {
                values.put((String) entry[0], ((Number) entry[1]).doubleValue());
            }
            values.put("held_item", "diamond_sword");
            values.put("offhand_item", "shield");
            values.put("target_held_item", "air");
            values.put("target_name", "Alex");
            values.put("bot_name", "Bot1");
            for (String flag : List.of("on_ground", "in_water", "gliding", "blocking", "using_item", "sprinting", "sneaking", "alive", "has_target", "fighting", "target_blocking"))
            {
                values.put(flag, flag.equals("on_ground") || flag.equals("alive") || flag.equals("has_target"));
            }
        }

        @Override
        public Object variable(String name)
        {
            return switch (name)
            {
                case "count" -> 5.0;
                case "name" -> "Steve";
                case "flags" -> List.of(1.0, 2.0, 3.0);
                case "ready" -> true;
                default -> null;
            };
        }

        @Override
        public Object value(String name)
        {
            return values.get(name);
        }

        @Override
        public Object call(String function, List<Object> args)
        {
            calls.add(function);
            if (overrides.containsKey(function))
            {
                return overrides.get(function);
            }
            return switch (function)
            {
                case "distance" -> Math.sqrt(Math.pow((Double) args.get(0) - 10.5, 2) + Math.pow((Double) args.get(1) - 64, 2) + Math.pow((Double) args.get(2) + 3, 2));
                case "player_distance" -> 7.0;
                case "count" -> 3.0;
                case "block" -> "stone";
                case "entities" -> 2.0;
                default -> throw new IllegalStateException("not a world function: " + function);
            };
        }

        @Override
        public void charge(int steps)
        {
            this.steps += steps;
        }
    }

    private static Expression.Type typeNamed(String name)
    {
        return Expression.Type.valueOf(name.toUpperCase());
    }

    private static Object plain(JsonElement element)
    {
        if (element.isJsonArray())
        {
            List<Object> items = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray())
            {
                items.add(plain(item));
            }
            return items;
        }
        if (element.getAsJsonPrimitive().isBoolean())
        {
            return element.getAsBoolean();
        }
        if (element.getAsJsonPrimitive().isNumber())
        {
            return element.getAsDouble();
        }
        return element.getAsString();
    }

    private static void assertValue(Object expected, Object actual, String where)
    {
        if (expected instanceof Double e)
        {
            assertTrue(actual instanceof Double a && Math.abs(e - a) < 1e-9, where + ": expected " + expected + " but got " + actual);
        }
        else if (expected instanceof List<?> e)
        {
            assertTrue(actual instanceof List<?>, where + ": expected a list but got " + actual);
            List<?> a = (List<?>) actual;
            assertEquals(e.size(), a.size(), where + ": list size");
            for (int i = 0; i < e.size(); i++)
            {
                assertValue(e.get(i), a.get(i), where + "[" + i + "]");
            }
        }
        else
        {
            assertEquals(expected, actual, where);
        }
    }

    private static Expression parse(String source)
    {
        return Expression.parse(source, VOCABULARY);
    }

    @TestFactory
    Stream<DynamicTest> sharedTable()
    {
        JsonArray cases = readJson("src/test/resources/carpetlogic/expression-cases.json").getAsJsonArray();
        assertTrue(cases.size() >= 120);
        List<DynamicTest> tests = new ArrayList<>();
        int number = 0;
        for (JsonElement element : cases)
        {
            JsonObject c = element.getAsJsonObject();
            String source = c.get("source").getAsString();
            Expression.Type expected = c.has("expected") ? typeNamed(c.get("expected").getAsString()) : Expression.Type.ANY;
            tests.add(DynamicTest.dynamicTest("case " + number++ + ": " + source, () -> verify(c, source, expected)));
        }
        return tests.stream();
    }

    private void verify(JsonObject c, String source, Expression.Type expected)
    {
        if (c.has("error"))
        {
            ExpressionException e = assertThrows(ExpressionException.class, () -> Expression.parse(source, VOCABULARY, expected));
            assertEquals(c.get("error").getAsString(), e.getMessage());
            assertEquals(c.get("position").getAsInt(), e.position());
            return;
        }
        Expression expression = Expression.parse(source, VOCABULARY, expected);
        assertEquals(source, expression.source());
        assertEquals(typeNamed(c.get("type").getAsString()), expression.type());
        FixedContext context = new FixedContext();
        if (c.has("fails"))
        {
            ExpressionException e = assertThrows(ExpressionException.class, () -> expression.evaluate(context));
            assertEquals(c.get("fails").getAsString(), e.getMessage());
            assertTrue(e.position() >= 0);
            return;
        }
        Object value = expression.evaluate(context);
        assertValue(plain(c.get("value")), value, source);
        if (expression.type() != Expression.Type.ANY)
        {
            assertEquals(expression.type(), Expression.typeOf(value), "static type of " + source);
        }
    }

    // The same limit cases are in the JS test.
    @Test
    void limits()
    {
        assertEquals(Expression.Type.NUMBER, parse("1" + " ".repeat(1023)).type());
        assertMessage("The expression is longer than 1024 characters", "1" + " ".repeat(1024));
        assertEquals(Expression.Type.NUMBER, parse("(".repeat(32) + "1" + ")".repeat(32)).type());
        assertMessage("The expression is nested more than 32 deep", "(".repeat(33) + "1" + ")".repeat(33));
        assertEquals(Expression.Type.NUMBER, parse("-".repeat(32) + "1").type());
        assertMessage("The expression is nested more than 32 deep", "-".repeat(33) + "1");
        assertEquals(Expression.Type.NUMBER, parse("abs(".repeat(32) + "1" + ")".repeat(32)).type());
        assertMessage("The expression is nested more than 32 deep", "abs(".repeat(33) + "1" + ")".repeat(33));
        assertMessage("The expression is nested more than 32 deep", "not ".repeat(33) + "true");
        assertEquals(Expression.Type.NUMBER, parse("1" + "+1".repeat(127)).type());
        assertMessage("The expression has more than 256 parts", "1" + "+1".repeat(128));
        assertMessage("The expression has more than 256 parts", "list(" + "1,".repeat(256) + "1)");
    }

    private static void assertMessage(String message, String source)
    {
        ExpressionException e = assertThrows(ExpressionException.class, () -> parse(source));
        assertEquals(message, e.getMessage());
        assertEquals(-1, e.position());
    }

    @Test
    void hostileNestingNeverOverflowsTheStack()
    {
        // The length limit refuses the longest of these first; the ones that fit are stopped by the depth limit.
        for (String open : List.of("(", "-", "not ", "abs("))
        {
            ExpressionException e = assertThrows(ExpressionException.class, () -> parse(open.repeat(10_000) + "1"));
            assertEquals("The expression is longer than 1024 characters", e.getMessage());
            String fitting = open.repeat(1024 / open.length() - 1) + "1";
            e = assertThrows(ExpressionException.class, () -> parse(fitting));
            assertEquals("The expression is nested more than 32 deep", e.getMessage());
        }
        ExpressionException e = assertThrows(ExpressionException.class, () -> parse("(".repeat(10_000) + "1" + ")".repeat(10_000)));
        assertTrue(e.getMessage().startsWith("The expression is"));
    }

    @Test
    void nullSourceIsEmpty()
    {
        assertEquals("The expression is empty", assertThrows(ExpressionException.class, () -> parse(null)).getMessage());
    }

    private static int steps(String source)
    {
        FixedContext context = new FixedContext();
        parse(source).evaluate(context);
        return context.steps;
    }

    @Test
    void budgetChargesOneStepPerNodeAndTheCostOfEachCall()
    {
        assertEquals(1, steps("5"));
        assertEquals(1, steps("health"));
        assertEquals(1, steps("$count"));
        assertEquals(3, steps("1 + 2"));
        assertEquals(5, steps("1 + 2 * 3"));
        assertEquals(5, steps("(1 + 2) * 3"), "parentheses are not nodes");
        assertEquals(2, steps("-5"));
        assertEquals(2, steps("not true"));
        assertEquals(4, steps("min(1, 2)"), "call, two arguments and cost 1");
        assertEquals(5, steps("clamp(1, 2, 3)"), "call, three arguments and cost 1");
        assertEquals(9, steps("block(x, y, z)"), "call, three arguments and cost 5");
        assertEquals(23, steps("entities('zombie', 5)"), "call, two arguments and cost 20");
        assertEquals(4, steps("player_distance('Alex')"), "call, one argument and cost 2");
        assertEquals(9, steps("player_distance('Alex') + count('x')"));
    }

    @Test
    void budgetIgnoresWhatIsNotEvaluated()
    {
        assertEquals(2, steps("false and 1 / 0 > 0"));
        assertEquals(2, steps("true or entities('zombie', 5) > 0"));
        assertEquals(4, steps("if(true, 1, entities('zombie', 5))"), "call, cost, condition and the branch taken");
        assertEquals(4, steps("if(false, entities('zombie', 5), 2)"));
        assertEquals(5, steps("true and 1 < 2"));
    }

    @Test
    void budgetCountsAFailureUpToTheStepThatFailed()
    {
        FixedContext context = new FixedContext();
        assertThrows(ExpressionException.class, () -> parse("1 / 0").evaluate(context));
        assertEquals(3, context.steps);
    }

    @Test
    void everyVocabularyEntryIsImplementedOrPassedOn()
    {
        Set<String> pure = Set.of("min", "max", "abs", "floor", "ceil", "round", "sqrt", "clamp", "if", "len", "contains", "get", "list", "range", "text", "number");
        Set<String> world = Set.of("distance", "player_distance", "count", "block", "entities");
        Set<String> declared = new HashSet<>(VOCABULARY.functions().keySet());
        Set<String> known = new HashSet<>(pure);
        known.addAll(world);
        assertEquals(known, declared, "the functions in actions.json and the ones this test knows about");

        Map<Expression.Type, String> sample = Map.of(
            Expression.Type.NUMBER, "0", Expression.Type.BOOL, "true", Expression.Type.TEXT, "'0'", Expression.Type.LIST, "list(0)", Expression.Type.ANY, "'0'");
        for (Expression.Vocabulary.Function function : VOCABULARY.functions().values())
        {
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < function.params().size(); i++)
            {
                args.append(i == 0 ? "" : ", ").append(sample.get(function.params().get(i)));
            }
            FixedContext context = new FixedContext();
            Object result = parse(function.name() + "(" + args + ")").evaluate(context);
            assertEquals(function.returns() == Expression.Type.ANY ? Expression.typeOf(result) : function.returns(), Expression.typeOf(result), function.name());
            assertEquals(world.contains(function.name()) ? List.of(function.name()) : List.of(), context.calls, function.name());
        }

        for (Map.Entry<String, Expression.Type> value : VOCABULARY.values().entrySet())
        {
            FixedContext context = new FixedContext();
            Object result = parse(value.getKey()).evaluate(context);
            assertEquals(value.getValue(), Expression.typeOf(result), value.getKey());
        }
    }

    @Test
    void aWorldFunctionThatReturnsTheWrongTypeIsAnError()
    {
        FixedContext context = new FixedContext();
        context.overrides.put("block", 5.0);
        ExpressionException e = assertThrows(ExpressionException.class, () -> parse("block(1, 2, 3)").evaluate(context));
        assertEquals("Expected text but got a number at 0", e.getMessage());
        context.overrides.put("entities", "many");
        e = assertThrows(ExpressionException.class, () -> parse("1 + entities('a', 1)").evaluate(context));
        assertEquals("Expected a number but got text at 4", e.getMessage());
        context.overrides.put("count", List.of());
        e = assertThrows(ExpressionException.class, () -> parse("count('a')").evaluate(context));
        assertEquals("Expected a number but got a list at 0", e.getMessage());
        context.overrides.put("count", new Object());
        assertThrows(ExpressionException.class, () -> parse("count('a')").evaluate(context));
    }

    @Test
    void aNamedValueOfTheWrongTypeIsAnError()
    {
        FixedContext context = new FixedContext();
        context.values.put("health", "full");
        ExpressionException e = assertThrows(ExpressionException.class, () -> parse("1 + health").evaluate(context));
        assertEquals("Expected a number but got text at 4", e.getMessage());
        context.values.remove("alive");
        assertThrows(ExpressionException.class, () -> parse("alive").evaluate(context));
    }

    @Test
    void worldFunctionsReceiveTheirArgumentsAsValues()
    {
        List<Object> seen = new ArrayList<>();
        FixedContext context = new FixedContext()
        {
            @Override
            public Object call(String function, List<Object> args)
            {
                seen.addAll(args);
                return 1;
            }
        };
        assertEquals(1.0, parse("entities('zombie', 2 + 3)").evaluate(context), "an Integer from the context becomes a Double");
        assertEquals(List.of("zombie", 5.0), seen);
    }

    @Test
    void limitsOnEvaluatedValues()
    {
        assertEquals(256, ((List<?>) parse("range(256)").evaluate(new FixedContext())).size());
        assertEquals("A list holds at most 256 items at 0", assertThrows(ExpressionException.class, () -> parse("range(257)").evaluate(new FixedContext())).getMessage());
        String text = "'" + "a".repeat(600) + "'";
        assertEquals(600, ((String) parse(text).evaluate(new FixedContext())).length());
        ExpressionException e = assertThrows(ExpressionException.class, () -> parse(text + " + text(range(200))").evaluate(new FixedContext()));
        assertEquals("A text holds at most 1024 characters at " + (text.length() + 1), e.getMessage());
        e = assertThrows(ExpressionException.class, () -> parse("text(range(256))").evaluate(new FixedContext()));
        assertEquals("A text holds at most 1024 characters at 0", e.getMessage());
        String items = "list(" + String.join(", ", Collections.nCopies(129, "1")) + ")";
        assertEquals(129, ((List<?>) parse(items).evaluate(new FixedContext())).size());
        assertEquals(2, ((List<?>) parse("list(range(100), range(100))").evaluate(new FixedContext())).size());
        e = assertThrows(ExpressionException.class, () -> parse("list(range(200), range(100))").evaluate(new FixedContext()));
        assertEquals("A list holds at most 256 items at 0", e.getMessage(), "what is in the lists inside a list counts too");
    }

    @Test
    void formatting()
    {
        assertEquals("5", Expression.format(5.0));
        assertEquals("-2", Expression.format(-2.0));
        assertEquals("0", Expression.format(-0.0));
        assertEquals("2.5", Expression.format(2.5));
        assertEquals("0.333", Expression.format(1.0 / 3));
        assertEquals("infinity", Expression.format(Double.POSITIVE_INFINITY));
        assertEquals("-infinity", Expression.format(Double.NEGATIVE_INFINITY));
        assertEquals("999999999999999", Expression.format(999999999999999.0));
        assertEquals("1000000000000000", Expression.format(1e15));
        assertEquals("true", Expression.format(true));
        assertEquals("[1, 2, a]", Expression.format(List.of(1.0, 2.0, "a")));
        assertEquals("[[1], true]", Expression.format(List.of(List.of(1.0), true)));
        assertEquals(Expression.Type.LIST, Expression.typeOf(List.of()));
    }

    @Test
    void infinityIsEqualToItselfAndComparesLikeANumber()
    {
        FixedContext context = new FixedContext();
        context.values.put("target_distance", Double.POSITIVE_INFINITY);
        assertEquals(true, parse("target_distance == target_distance").evaluate(context));
        assertEquals(true, parse("target_distance > 1000000").evaluate(context));
        assertEquals("infinity", parse("text(target_distance)").evaluate(context));
        assertEquals("d=infinity", parse("'d=' + target_distance").evaluate(context));
    }

    @Test
    void vocabularyRefusesAMalformedFile()
    {
        JsonObject twice = JsonParser.parseString("{\"values\":[{\"name\":\"a\",\"type\":\"number\"},{\"name\":\"a\",\"type\":\"number\"}],\"functions\":[]}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> Expression.Vocabulary.read(twice));
        JsonObject unknown = JsonParser.parseString("{\"values\":[{\"name\":\"a\",\"type\":\"nope\"}],\"functions\":[]}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> Expression.Vocabulary.read(unknown));
    }
}
