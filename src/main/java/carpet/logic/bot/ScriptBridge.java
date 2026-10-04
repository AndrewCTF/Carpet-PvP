package carpet.logic.bot;

import carpet.script.value.BooleanValue;
import carpet.script.value.ListValue;
import carpet.script.value.NullValue;
import carpet.script.value.NumericValue;
import carpet.script.value.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Carries a program's variables into a Scarpet snippet and back without reaching into Scarpet: the snippet is
 * given a few lines before it that set the variables, and a line after it that hands back its result together
 * with what the variables then hold.
 */
final class ScriptBridge
{
    private static final String RESULT = "__carpetlogic_result";

    private ScriptBridge() {}

    /**
     * @param started whether a player started the program at all
     * @param owner the name of that player while they are online, or null
     * @param mayScript whether commandScript lets them use /script
     * @param mayRunCode whether commandScriptACE lets them use /script run
     * @return why a snippet does not run for them, or null when it does
     */
    static String refusal(boolean started, String owner, boolean mayScript, boolean mayRunCode)
    {
        if (!started)
        {
            return "SCARPET only runs in programs a player started from the web editor";
        }
        if (owner == null)
        {
            return "SCARPET needs the player who started this program to be online";
        }
        return mayScript && mayRunCode ? null
                : "SCARPET needs " + owner + " to be allowed /script run, which the rules commandScript and commandScriptACE decide";
    }

    /**
     * @return the snippet between the lines that pass the variables in and out
     */
    static String wrap(String code, Map<String, Object> variables)
    {
        StringBuilder script = new StringBuilder();
        variables.forEach((name, value) -> script.append(name).append(" = ").append(literal(value)).append(";\n"));
        // A snippet may end with the semicolon that would go between it and another statement.
        String snippet = code.strip().replaceAll(";+$", "");
        script.append(RESULT).append(" = (\n").append(snippet.isEmpty() ? "null" : snippet).append("\n);\nl(").append(RESULT);
        variables.keySet().forEach(name -> script.append(", ").append(name));
        return script.append(")").toString();
    }

    /**
     * @param expected how many variables were passed in
     * @return the snippet's result and what it left in the variables, as values a program holds. A snippet that
     *         ended itself with exit() hands back what it exited with and leaves the variables as they were.
     */
    static List<Object> unwrap(Value returned, int expected)
    {
        if (returned instanceof ListValue list && list.getItems().size() == expected + 1)
        {
            List<Object> values = new ArrayList<>();
            list.getItems().forEach(item -> values.add(value(item)));
            return values;
        }
        return List.of(value(returned));
    }

    // A value as Scarpet writes it. Text is quoted with its quotes and backslashes escaped.
    static String literal(Object value)
    {
        return switch (value)
        {
            case Boolean bool -> bool ? "true" : "false";
            case Double number -> number.isNaN() ? "0" : number.isInfinite() ? (number > 0 ? "" : "-") + "1.7976931348623157E308"
                    : BigDecimal.valueOf(number).toPlainString();
            case List<?> list ->
            {
                List<String> items = new ArrayList<>();
                list.forEach(item -> items.add(literal(item)));
                yield "l(" + String.join(", ", items) + ")";
            }
            default -> "'" + String.valueOf(value).replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
        };
    }

    // A Scarpet value as a program holds it. What a program has no kind for, an entity or a block, is its text.
    static Object value(Value value)
    {
        if (value instanceof NullValue)
        {
            return 0.0D;
        }
        if (value instanceof BooleanValue)
        {
            return value.getBoolean();
        }
        if (value instanceof NumericValue number)
        {
            return number.getDouble();
        }
        if (value instanceof ListValue list)
        {
            List<Object> items = new ArrayList<>();
            list.getItems().forEach(item -> items.add(value(item)));
            return items;
        }
        return value.getString();
    }
}
