package carpet.logic.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The small expression language of the bot editor: numbers, true or false, text and lists, with the names and
 * functions listed under "expressions" in carpetlogic/actions.json. The page checks a source with
 * webui/js/expression.js, which has to give the same verdict and the same message as {@link #parse} does.
 */
public final class Expression
{
    public enum Type
    {
        NUMBER("a number"),
        BOOL("true or false"),
        TEXT("text"),
        LIST("a list"),
        ANY("anything");

        private final String words;

        Type(String words)
        {
            this.words = words;
        }

        /** The type as it is written in a message: "a number", "true or false". */
        public String words()
        {
            return words;
        }
    }

    public interface Context
    {
        /** The variable's value, or null when the program never set it, which reads as the number 0. */
        Object variable(String name);

        /** A named value: Double, Boolean or String. */
        Object value(String name);

        /** One of the functions about the world. */
        Object call(String function, List<Object> args);

        /** Called once per node evaluated (1) and once per function call (its cost). */
        void charge(int steps);
    }

    public static final class Vocabulary
    {
        public record Function(String name, List<Type> params, boolean variadic, Type returns, int cost)
        {
            /** The type an argument at this place must have; the extra arguments of a variadic function repeat the last one. */
            Type paramType(int index)
            {
                if (index < params.size())
                {
                    return params.get(index);
                }
                return params.isEmpty() ? Type.ANY : params.get(params.size() - 1);
            }
        }

        private final Map<String, Type> values = new LinkedHashMap<>();
        private final Map<String, Function> functions = new LinkedHashMap<>();
        private char prefix = '$';

        private Vocabulary()
        {
        }

        public static Vocabulary read(JsonObject expressions)
        {
            return read(expressions, '$');
        }

        /**
         * @param prefix the character a variable's name is written after
         */
        public static Vocabulary read(JsonObject expressions, char prefix)
        {
            Vocabulary vocabulary = new Vocabulary();
            vocabulary.prefix = prefix;
            for (JsonElement element : expressions.getAsJsonArray("values"))
            {
                JsonObject entry = element.getAsJsonObject();
                String name = entry.get("name").getAsString();
                if (vocabulary.values.put(name, typeNamed(entry.get("type").getAsString())) != null)
                {
                    throw new IllegalArgumentException("Value '" + name + "' is listed twice");
                }
            }
            for (JsonElement element : expressions.getAsJsonArray("functions"))
            {
                JsonObject entry = element.getAsJsonObject();
                String name = entry.get("name").getAsString();
                List<Type> params = new ArrayList<>();
                JsonArray declared = entry.getAsJsonArray("params");
                for (JsonElement param : declared)
                {
                    params.add(typeNamed(param.getAsJsonObject().get("type").getAsString()));
                }
                boolean variadic = entry.has("variadic") && entry.get("variadic").getAsBoolean();
                int cost = entry.has("cost") ? entry.get("cost").getAsInt() : 1;
                Function function = new Function(name, List.copyOf(params), variadic, typeNamed(entry.get("returns").getAsString()), cost);
                if (vocabulary.functions.put(name, function) != null)
                {
                    throw new IllegalArgumentException("Function '" + name + "' is listed twice");
                }
            }
            return vocabulary;
        }

        public Map<String, Type> values()
        {
            return Collections.unmodifiableMap(values);
        }

        public Map<String, Function> functions()
        {
            return Collections.unmodifiableMap(functions);
        }

        private static Type typeNamed(String name)
        {
            return switch (name)
            {
                case "number" -> Type.NUMBER;
                case "bool" -> Type.BOOL;
                case "text" -> Type.TEXT;
                case "list" -> Type.LIST;
                case "any" -> Type.ANY;
                default -> throw new IllegalArgumentException("Unknown type '" + name + "'");
            };
        }
    }

    public static final int MAX_LENGTH = 1024;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_NODES = 256;
    public static final int MAX_LIST = 256;
    public static final int MAX_TEXT = 1024;

    private static final double SAME = 1e-9;
    private static final Pattern NUMBER_TEXT = Pattern.compile("-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)");

    private enum Kind { LITERAL, VARIABLE, VALUE, NEGATE, NOT, BINARY, CALL }

    /** start is where the node's text begins, pos is where a failure of the node itself is reported (its operator or name). */
    private static final class Node
    {
        final Kind kind;
        final String text;
        final Object literal;
        final List<Node> args;
        final Vocabulary.Function function;
        final int pos;
        final Type type;
        int start;

        Node(Kind kind, String text, Object literal, List<Node> args, Vocabulary.Function function, int start, int pos, Type type)
        {
            this.kind = kind;
            this.text = text;
            this.literal = literal;
            this.args = args;
            this.function = function;
            this.start = start;
            this.pos = pos;
            this.type = type;
        }
    }

    private final String source;
    private final Node root;

    private Expression(String source, Node root)
    {
        this.source = source;
        this.root = root;
    }

    public static Expression parse(String source, Vocabulary vocabulary)
    {
        return parse(source, vocabulary, Type.ANY);
    }

    /** Like {@link #parse(String, Vocabulary)}, and an expression whose known type is not the expected one is an error. */
    public static Expression parse(String source, Vocabulary vocabulary, Type expected)
    {
        String text = source == null ? "" : source;
        if (text.length() > MAX_LENGTH)
        {
            throw new ExpressionException("The expression is longer than " + MAX_LENGTH + " characters", -1);
        }
        if (text.chars().allMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == '\r'))
        {
            throw new ExpressionException("The expression is empty", -1);
        }
        Node root = new Parser(text, vocabulary).parseAll();
        if (expected != Type.ANY && root.type != Type.ANY && root.type != expected)
        {
            throw fail("Expected " + expected.words + " but got " + root.type.words, root.start);
        }
        return new Expression(text, root);
    }

    public String source()
    {
        return source;
    }

    public Type type()
    {
        return root.type;
    }

    public Object evaluate(Context context)
    {
        return eval(root, context);
    }

    public static Type typeOf(Object value)
    {
        return switch (value)
        {
            case Number n -> Type.NUMBER;
            case Boolean b -> Type.BOOL;
            case String s -> Type.TEXT;
            case List<?> l -> Type.LIST;
            default -> throw new IllegalArgumentException("Not a value of the expression language: " + value);
        };
    }

    public static String format(Object value)
    {
        return switch (value)
        {
            case Number n -> formatNumber(n.doubleValue());
            case Boolean b -> b.toString();
            case String s -> s;
            case List<?> list -> list.stream().map(Expression::format).collect(Collectors.joining(", ", "[", "]"));
            default -> throw new IllegalArgumentException("Not a value of the expression language: " + value);
        };
    }

    private static String formatNumber(double number)
    {
        if (Double.isNaN(number))
        {
            return "NaN";
        }
        if (Double.isInfinite(number))
        {
            return number > 0 ? "infinity" : "-infinity";
        }
        if (number == Math.rint(number) && Math.abs(number) < 1e15)
        {
            return Long.toString((long) number);
        }
        return BigDecimal.valueOf(number).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static ExpressionException fail(String message, int position)
    {
        return new ExpressionException(position < 0 ? message : message + " at " + position, position);
    }

    // ── Parsing ──────────────────────────────────────────────────

    private enum TokenKind { NUMBER, TEXT, NAME, VARIABLE, KEYWORD, SYMBOL, END }

    /** text is the number's digits, the text's content, the name, or the canonical keyword or symbol. */
    private record Token(TokenKind kind, String text, int start, int end)
    {
    }

    private static boolean digit(char c)
    {
        return c >= '0' && c <= '9';
    }

    private static boolean nameStart(char c)
    {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_';
    }

    private static List<Token> tokenize(String s, char prefix)
    {
        List<Token> tokens = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n)
        {
            char c = s.charAt(i);
            int start = i;
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r')
            {
                i++;
            }
            else if (digit(c) || c == '.' && i + 1 < n && digit(s.charAt(i + 1)))
            {
                while (i < n && digit(s.charAt(i)))
                {
                    i++;
                }
                if (i + 1 < n && s.charAt(i) == '.' && digit(s.charAt(i + 1)))
                {
                    i++;
                    while (i < n && digit(s.charAt(i)))
                    {
                        i++;
                    }
                }
                tokens.add(new Token(TokenKind.NUMBER, s.substring(start, i), start, i));
            }
            else if (nameStart(c) || c == prefix && i + 1 < n && nameStart(s.charAt(i + 1)))
            {
                i++;
                while (i < n && (nameStart(s.charAt(i)) || digit(s.charAt(i))))
                {
                    i++;
                }
                String word = s.substring(start, i);
                switch (word)
                {
                    case "and", "or", "not", "true", "false" -> tokens.add(new Token(TokenKind.KEYWORD, word, start, i));
                    default -> tokens.add(c == prefix
                        ? new Token(TokenKind.VARIABLE, word.substring(1), start, i)
                        : new Token(TokenKind.NAME, word, start, i));
                }
            }
            else if (c == '\'' || c == '"')
            {
                i = readText(s, i, tokens);
            }
            else
            {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                switch (two)
                {
                    case "<=", ">=", "==", "!=" ->
                    {
                        tokens.add(new Token(TokenKind.SYMBOL, two, start, i + 2));
                        i += 2;
                    }
                    case "&&", "||" ->
                    {
                        tokens.add(new Token(TokenKind.KEYWORD, two.equals("&&") ? "and" : "or", start, i + 2));
                        i += 2;
                    }
                    default ->
                    {
                        if (c == '!')
                        {
                            tokens.add(new Token(TokenKind.KEYWORD, "not", start, i + 1));
                        }
                        else if ("<>+-*/%(),".indexOf(c) >= 0)
                        {
                            tokens.add(new Token(TokenKind.SYMBOL, String.valueOf(c), start, i + 1));
                        }
                        else
                        {
                            throw unexpectedCharacter(s, i);
                        }
                        i++;
                    }
                }
            }
        }
        tokens.add(new Token(TokenKind.END, "", n, n));
        return tokens;
    }

    private static int readText(String s, int start, List<Token> tokens)
    {
        char quote = s.charAt(start);
        StringBuilder content = new StringBuilder();
        int i = start + 1;
        while (true)
        {
            if (i >= s.length())
            {
                throw new ExpressionException("Text that starts at " + start + " is never closed", start);
            }
            char c = s.charAt(i);
            if (c == quote)
            {
                tokens.add(new Token(TokenKind.TEXT, content.toString(), start, i + 1));
                return i + 1;
            }
            if (c == '\\')
            {
                if (i + 1 >= s.length())
                {
                    throw new ExpressionException("Text that starts at " + start + " is never closed", start);
                }
                char escaped = s.charAt(i + 1);
                if (escaped != '\\' && escaped != '\'' && escaped != '"')
                {
                    throw unexpectedCharacter(s, i);
                }
                content.append(escaped);
                i += 2;
            }
            else
            {
                content.append(c);
                i++;
            }
        }
    }

    private static ExpressionException unexpectedCharacter(String s, int index)
    {
        return fail("Unexpected character '" + new String(Character.toChars(s.codePointAt(index))) + "'", index);
    }

    private static final class Parser
    {
        private final String source;
        private final Vocabulary vocabulary;
        private final List<Token> tokens;
        private int index;
        private int depth;
        private int nodes;

        Parser(String source, Vocabulary vocabulary)
        {
            this.source = source;
            this.vocabulary = vocabulary;
            this.tokens = tokenize(source, vocabulary.prefix);
        }

        Node parseAll()
        {
            Node root = parseOr();
            if (peek().kind() != TokenKind.END)
            {
                throw unexpected(peek());
            }
            return root;
        }

        private Token peek()
        {
            return tokens.get(index);
        }

        private Token next()
        {
            Token token = tokens.get(index);
            if (token.kind() != TokenKind.END)
            {
                index++;
            }
            return token;
        }

        private boolean at(TokenKind kind, String text)
        {
            Token token = peek();
            return token.kind() == kind && token.text().equals(text);
        }

        private boolean atComparison()
        {
            Token token = peek();
            return token.kind() == TokenKind.SYMBOL && List.of("<", "<=", ">", ">=", "==", "!=").contains(token.text());
        }

        private ExpressionException unexpected(Token token)
        {
            if (token.kind() == TokenKind.END)
            {
                return new ExpressionException("Unexpected end of the expression", -1);
            }
            return fail("Unexpected '" + source.substring(token.start(), token.end()) + "'", token.start());
        }

        private void enter()
        {
            if (++depth > MAX_DEPTH)
            {
                throw new ExpressionException("The expression is nested more than " + MAX_DEPTH + " deep", -1);
            }
        }

        private Node make(Kind kind, String text, Object literal, List<Node> args, Vocabulary.Function function, int start, int pos, Type type)
        {
            if (++nodes > MAX_NODES)
            {
                throw new ExpressionException("The expression has more than " + MAX_NODES + " parts", -1);
            }
            return new Node(kind, text, literal, args, function, start, pos, type);
        }

        private void expect(Node operand, Type wanted)
        {
            if (operand.type != Type.ANY && operand.type != wanted)
            {
                throw fail("Expected " + wanted.words + " but got " + operand.type.words, operand.start);
            }
        }

        private Node parseOr()
        {
            Node left = parseAnd();
            while (at(TokenKind.KEYWORD, "or"))
            {
                Token op = next();
                left = binary(op, left, parseAnd());
            }
            return left;
        }

        private Node parseAnd()
        {
            Node left = parseNot();
            while (at(TokenKind.KEYWORD, "and"))
            {
                Token op = next();
                left = binary(op, left, parseNot());
            }
            return left;
        }

        private Node parseNot()
        {
            if (at(TokenKind.KEYWORD, "not"))
            {
                Token op = next();
                enter();
                Node operand = parseNot();
                depth--;
                expect(operand, Type.BOOL);
                return make(Kind.NOT, "not", null, List.of(operand), null, op.start(), op.start(), Type.BOOL);
            }
            return parseCompare();
        }

        private Node parseCompare()
        {
            Node left = parseSum();
            if (!atComparison())
            {
                return left;
            }
            Token op = next();
            Node node = binary(op, left, parseSum());
            if (atComparison())
            {
                throw fail("Comparisons cannot be chained", peek().start());
            }
            return node;
        }

        private Node parseSum()
        {
            Node left = parseTerm();
            while (at(TokenKind.SYMBOL, "+") || at(TokenKind.SYMBOL, "-"))
            {
                Token op = next();
                left = binary(op, left, parseTerm());
            }
            return left;
        }

        private Node parseTerm()
        {
            Node left = parseUnary();
            while (at(TokenKind.SYMBOL, "*") || at(TokenKind.SYMBOL, "/") || at(TokenKind.SYMBOL, "%"))
            {
                Token op = next();
                left = binary(op, left, parseUnary());
            }
            return left;
        }

        private Node parseUnary()
        {
            if (at(TokenKind.SYMBOL, "-"))
            {
                Token op = next();
                enter();
                Node operand = parseUnary();
                depth--;
                expect(operand, Type.NUMBER);
                return make(Kind.NEGATE, "-", null, List.of(operand), null, op.start(), op.start(), Type.NUMBER);
            }
            return parsePrimary();
        }

        private Node parsePrimary()
        {
            Token token = next();
            switch (token.kind())
            {
                case NUMBER:
                    return make(Kind.LITERAL, null, Double.parseDouble(token.text()), null, null, token.start(), token.start(), Type.NUMBER);
                case TEXT:
                    return make(Kind.LITERAL, null, token.text(), null, null, token.start(), token.start(), Type.TEXT);
                case VARIABLE:
                    return make(Kind.VARIABLE, token.text(), null, null, null, token.start(), token.start(), Type.ANY);
                case NAME:
                    return at(TokenKind.SYMBOL, "(") ? parseCall(token) : parseValue(token);
                case KEYWORD:
                    if (token.text().equals("true") || token.text().equals("false"))
                    {
                        return make(Kind.LITERAL, null, token.text().equals("true"), null, null, token.start(), token.start(), Type.BOOL);
                    }
                    throw unexpected(token);
                case SYMBOL:
                    if (token.text().equals("("))
                    {
                        enter();
                        Node inner = parseOr();
                        if (!at(TokenKind.SYMBOL, ")"))
                        {
                            throw unexpected(peek());
                        }
                        next();
                        depth--;
                        inner.start = token.start();
                        return inner;
                    }
                    throw unexpected(token);
                default:
                    throw unexpected(token);
            }
        }

        private Node parseValue(Token name)
        {
            Type type = vocabulary.values.get(name.text());
            if (type == null)
            {
                throw fail("Unknown name '" + name.text() + "'", name.start());
            }
            return make(Kind.VALUE, name.text(), null, null, null, name.start(), name.start(), type);
        }

        private Node parseCall(Token name)
        {
            Vocabulary.Function function = vocabulary.functions.get(name.text());
            if (function == null)
            {
                throw fail("Unknown function '" + name.text() + "'", name.start());
            }
            next();
            enter();
            List<Node> args = new ArrayList<>();
            if (!at(TokenKind.SYMBOL, ")"))
            {
                args.add(parseOr());
                while (at(TokenKind.SYMBOL, ","))
                {
                    next();
                    args.add(parseOr());
                }
            }
            if (!at(TokenKind.SYMBOL, ")"))
            {
                throw unexpected(peek());
            }
            next();
            depth--;
            int required = function.params().size();
            if (function.variadic() ? args.size() < required : args.size() != required)
            {
                throw fail(name.text() + " takes " + (function.variadic() ? "at least " : "") + required + (required == 1 ? " value" : " values")
                    + ", not " + args.size() + ",", name.start());
            }
            for (int i = 0; i < args.size(); i++)
            {
                Type wanted = function.paramType(i);
                if (wanted != Type.ANY)
                {
                    expect(args.get(i), wanted);
                }
            }
            return make(Kind.CALL, name.text(), null, args, function, name.start(), name.start(), function.returns());
        }

        private Node binary(Token op, Node left, Node right)
        {
            String symbol = op.text();
            Type type;
            switch (symbol)
            {
                case "and", "or" ->
                {
                    expect(left, Type.BOOL);
                    expect(right, Type.BOOL);
                    type = Type.BOOL;
                }
                case "+" -> type = addedType(left, right);
                case "-", "*", "/", "%" ->
                {
                    expect(left, Type.NUMBER);
                    expect(right, Type.NUMBER);
                    type = Type.NUMBER;
                }
                case "==", "!=" ->
                {
                    if (left.type != Type.ANY && right.type != Type.ANY && left.type != right.type)
                    {
                        throw fail("Cannot compare " + left.type.words + " with " + right.type.words, op.start());
                    }
                    type = Type.BOOL;
                }
                default ->
                {
                    expect(left, Type.NUMBER);
                    expect(right, Type.NUMBER);
                    type = Type.BOOL;
                }
            }
            return make(Kind.BINARY, symbol, null, List.of(left, right), null, left.start, op.start(), type);
        }

        private Type addedType(Node left, Node right)
        {
            if (left.type == Type.TEXT || right.type == Type.TEXT)
            {
                return Type.TEXT;
            }
            if (left.type == Type.NUMBER && right.type == Type.NUMBER)
            {
                return Type.NUMBER;
            }
            if (left.type == Type.ANY || right.type == Type.ANY)
            {
                return Type.ANY;
            }
            expect(left.type == Type.NUMBER ? right : left, Type.NUMBER);
            return Type.ANY;
        }
    }

    // ── Evaluation ───────────────────────────────────────────────

    private static ExpressionException wrongType(Object value, String wanted, int position)
    {
        return fail("Expected " + wanted + " but got " + typeOf(value).words, position);
    }

    private static ExpressionException wrongType(Object value, Type wanted, int position)
    {
        return wrongType(value, wanted.words, position);
    }

    /** A Number becomes a Double and a list's items are converted too; null when it is not something the language holds. */
    private static Object normalize(Object value)
    {
        if (value instanceof Double || value instanceof Boolean || value instanceof String)
        {
            return value;
        }
        if (value instanceof Number number)
        {
            return number.doubleValue();
        }
        if (value instanceof List<?> list)
        {
            List<Object> items = new ArrayList<>(list.size());
            for (Object item : list)
            {
                Object normalized = normalize(item);
                if (normalized == null)
                {
                    return null;
                }
                items.add(normalized);
            }
            return items;
        }
        return null;
    }

    /**
     * Checks a value that did not come out of an expression against the limits values have.
     *
     * @throws ExpressionException when it is a text or a list larger than an expression could have made
     */
    public static Object limited(Object value)
    {
        return sized(value, -1);
    }

    private static Object sized(Object value, int position)
    {
        if (value instanceof String text && text.length() > MAX_TEXT)
        {
            throw fail("A text holds at most " + MAX_TEXT + " characters", position);
        }
        if (value instanceof List<?> list && items(list, 0) > MAX_LIST)
        {
            throw fail("A list holds at most " + MAX_LIST + " items", position);
        }
        return value;
    }

    // A list inside a list counts as an item and so does everything in it: a list of lists cannot grow past
    // the limit by nesting, and is never deeper than it is long.
    private static int items(List<?> list, int counted)
    {
        for (Object item : list)
        {
            counted++;
            if (item instanceof List<?> inner)
            {
                counted = items(inner, counted);
            }
            if (counted > MAX_LIST)
            {
                break;
            }
        }
        return counted;
    }

    private static boolean same(Object a, Object b)
    {
        if (a instanceof Double x && b instanceof Double y)
        {
            return x.doubleValue() == y.doubleValue() || Math.abs(x - y) < SAME;
        }
        if (a instanceof List<?> x && b instanceof List<?> y)
        {
            if (x.size() != y.size())
            {
                return false;
            }
            for (int i = 0; i < x.size(); i++)
            {
                if (!same(x.get(i), y.get(i)))
                {
                    return false;
                }
            }
            return true;
        }
        return a.getClass() == b.getClass() && a.equals(b);
    }

    private Object eval(Node node, Context context)
    {
        context.charge(1);
        return switch (node.kind)
        {
            case LITERAL -> node.literal;
            case VARIABLE -> variable(node, context);
            case VALUE -> named(node, context);
            case NEGATE -> Double.valueOf(-number(node.args.get(0), context));
            case NOT -> Boolean.valueOf(!bool(node.args.get(0), context));
            case BINARY -> binary(node, context);
            case CALL -> call(node, context);
        };
    }

    private double number(Node node, Context context)
    {
        Object value = eval(node, context);
        if (value instanceof Double number)
        {
            return number;
        }
        throw wrongType(value, Type.NUMBER, node.start);
    }

    private boolean bool(Node node, Context context)
    {
        Object value = eval(node, context);
        if (value instanceof Boolean bool)
        {
            return bool;
        }
        throw wrongType(value, Type.BOOL, node.start);
    }

    private Object variable(Node node, Context context)
    {
        Object value = context.variable(node.text);
        if (value == null)
        {
            return 0.0;
        }
        Object normalized = normalize(value);
        if (normalized == null)
        {
            throw fail("Variable '" + node.text + "' holds something an expression cannot read", node.pos);
        }
        return normalized;
    }

    private Object named(Node node, Context context)
    {
        Object value = context.value(node.text);
        Object normalized = value == null ? null : normalize(value);
        if (normalized == null || normalized instanceof List<?>)
        {
            throw fail("The value '" + node.text + "' is not available", node.pos);
        }
        if (typeOf(normalized) != node.type)
        {
            throw wrongType(normalized, node.type, node.pos);
        }
        return normalized;
    }

    private Object binary(Node node, Context context)
    {
        Node left = node.args.get(0);
        Node right = node.args.get(1);
        switch (node.text)
        {
            case "and":
                return bool(left, context) && bool(right, context);
            case "or":
                return bool(left, context) || bool(right, context);
            case "==":
                return same(eval(left, context), eval(right, context));
            case "!=":
                return !same(eval(left, context), eval(right, context));
            case "+":
                return added(node, context);
            default:
                break;
        }
        double a = number(left, context);
        double b = number(right, context);
        return switch (node.text)
        {
            case "-" -> Double.valueOf(a - b);
            case "*" -> Double.valueOf(a * b);
            case "/", "%" ->
            {
                if (b == 0)
                {
                    throw fail("Division by zero", node.pos);
                }
                yield Double.valueOf(node.text.equals("/") ? a / b : a % b);
            }
            case "<" -> Boolean.valueOf(a < b);
            case "<=" -> Boolean.valueOf(a <= b);
            case ">" -> Boolean.valueOf(a > b);
            default -> Boolean.valueOf(a >= b);
        };
    }

    private Object added(Node node, Context context)
    {
        Node left = node.args.get(0);
        Node right = node.args.get(1);
        Object a = eval(left, context);
        Object b = eval(right, context);
        if (a instanceof String || b instanceof String)
        {
            return sized(format(a) + format(b), node.pos);
        }
        if (a instanceof Double x && b instanceof Double y)
        {
            return x + y;
        }
        boolean leftBad = !(a instanceof Double);
        throw wrongType(leftBad ? a : b, Type.NUMBER, leftBad ? left.start : right.start);
    }

    private Object call(Node node, Context context)
    {
        Vocabulary.Function function = node.function;
        context.charge(function.cost());
        List<Node> operands = node.args;
        if (function.name().equals("if"))
        {
            return eval(operands.get(bool(operands.get(0), context) ? 1 : 2), context);
        }
        List<Object> args = new ArrayList<>(operands.size());
        for (int i = 0; i < operands.size(); i++)
        {
            Object value = eval(operands.get(i), context);
            Type wanted = function.paramType(i);
            if (wanted != Type.ANY && typeOf(value) != wanted)
            {
                throw wrongType(value, wanted, operands.get(i).start);
            }
            args.add(value);
        }
        Object result = pure(node, args);
        if (result != null)
        {
            return sized(result, node.pos);
        }
        Object returned = normalize(context.call(function.name(), args));
        if (returned == null)
        {
            throw fail("The function '" + function.name() + "' gave back something an expression cannot read", node.pos);
        }
        if (function.returns() != Type.ANY && typeOf(returned) != function.returns())
        {
            throw wrongType(returned, function.returns(), node.pos);
        }
        return sized(returned, node.pos);
    }

    /** The functions the evaluator answers itself; null for any other, which belongs to the context. */
    private Object pure(Node node, List<Object> args)
    {
        List<Node> operands = node.args;
        switch (node.function.name())
        {
            case "min", "max" ->
            {
                boolean smallest = node.function.name().equals("min");
                double best = (Double) args.get(0);
                for (Object arg : args)
                {
                    best = smallest ? Math.min(best, (Double) arg) : Math.max(best, (Double) arg);
                }
                return best;
            }
            case "abs" -> { return Math.abs((Double) args.get(0)); }
            case "floor" -> { return Math.floor((Double) args.get(0)); }
            case "ceil" -> { return Math.ceil((Double) args.get(0)); }
            case "round" -> { return Math.floor((Double) args.get(0) + 0.5); }
            case "sqrt" ->
            {
                double n = (Double) args.get(0);
                if (n < 0)
                {
                    throw fail("sqrt of a negative number", node.pos);
                }
                return Math.sqrt(n);
            }
            case "clamp" -> { return Math.min(Math.max((Double) args.get(0), (Double) args.get(1)), (Double) args.get(2)); }
            case "len" ->
            {
                return switch (args.get(0))
                {
                    case String text -> (double) text.length();
                    case List<?> list -> (double) list.size();
                    default -> throw wrongType(args.get(0), "text or a list", operands.get(0).start);
                };
            }
            case "contains" ->
            {
                switch (args.get(0))
                {
                    case String text ->
                    {
                        if (!(args.get(1) instanceof String part))
                        {
                            throw wrongType(args.get(1), Type.TEXT, operands.get(1).start);
                        }
                        return text.contains(part);
                    }
                    case List<?> list ->
                    {
                        for (Object item : list)
                        {
                            if (same(item, args.get(1)))
                            {
                                return true;
                            }
                        }
                        return false;
                    }
                    default -> throw wrongType(args.get(0), "text or a list", operands.get(0).start);
                }
            }
            case "get" ->
            {
                List<?> list = (List<?>) args.get(0);
                double at = (Double) args.get(1);
                if (at != Math.rint(at) || at < 0 || at >= list.size())
                {
                    throw fail("Index " + format(at) + " is outside a list of " + list.size(), node.pos);
                }
                return list.get((int) at);
            }
            case "list" -> { return new ArrayList<>(args); }
            case "range" ->
            {
                double n = Math.floor((Double) args.get(0));
                if (n > MAX_LIST)
                {
                    throw fail("A list holds at most " + MAX_LIST + " items", node.pos);
                }
                List<Object> numbers = new ArrayList<>();
                for (int i = 0; i < n; i++)
                {
                    numbers.add((double) i);
                }
                return numbers;
            }
            case "text" -> { return format(args.get(0)); }
            case "number" ->
            {
                Object value = args.get(0);
                if (value instanceof Double)
                {
                    return value;
                }
                if (value instanceof String text && NUMBER_TEXT.matcher(text).matches())
                {
                    return Double.parseDouble(text);
                }
                throw fail("'" + format(value) + "' is not a number", node.pos);
            }
            default -> { return null; }
        }
    }
}
