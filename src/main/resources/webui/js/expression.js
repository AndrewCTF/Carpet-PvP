/* ═══════════════════════════════════════════════════════════════
   Expression — the page's side of the bot editor's expression
   language: it parses a source against the vocabulary in
   carpetlogic/actions.json ("expressions") and says what type it has,
   or what is wrong and where. It never evaluates.

   The server's carpet.logic.program.Expression parses the same
   language; both are tested against src/test/resources/carpetlogic/
   expression-cases.json and have to give the same message and position.
   ═══════════════════════════════════════════════════════════════ */
const Expression = (() => {

    const MAX_LENGTH = 1024;
    const MAX_DEPTH = 32;
    const MAX_NODES = 256;

    const WORDS = { number: "a number", bool: "true or false", text: "text", list: "a list", any: "anything" };
    const COMPARISONS = ["<", "<=", ">", ">=", "==", "!="];

    // An error with the place it was found at, or -1 when it has none.
    class Failure {
        constructor(message, position) {
            this.message = message;
            this.position = position;
        }
    }

    const fail = (message, position) => new Failure(message + " at " + position, position);

    const isDigit = c => c >= "0" && c <= "9";
    const isNameStart = c => (c >= "a" && c <= "z") || (c >= "A" && c <= "Z") || c === "_";

    function unexpectedCharacter(source, index) {
        return fail("Unexpected character '" + String.fromCodePoint(source.codePointAt(index)) + "'", index);
    }

    function readText(source, start, tokens) {
        const quote = source[start];
        let content = "";
        let i = start + 1;
        for (;;) {
            if (i >= source.length) throw new Failure("Text that starts at " + start + " is never closed", start);
            const c = source[i];
            if (c === quote) {
                tokens.push({ kind: "text", text: content, start, end: i + 1 });
                return i + 1;
            }
            if (c === "\\") {
                if (i + 1 >= source.length) throw new Failure("Text that starts at " + start + " is never closed", start);
                const escaped = source[i + 1];
                if (escaped !== "\\" && escaped !== "'" && escaped !== '"') throw unexpectedCharacter(source, i);
                content += escaped;
                i += 2;
            } else {
                content += c;
                i++;
            }
        }
    }

    // kind is number, text, name, variable, keyword, symbol or end; keywords and symbols carry their canonical text.
    function tokenize(source, prefix) {
        const tokens = [];
        const n = source.length;
        let i = 0;
        while (i < n) {
            const c = source[i];
            const start = i;
            if (c === " " || c === "\t" || c === "\n" || c === "\r") {
                i++;
            } else if (isDigit(c) || (c === "." && i + 1 < n && isDigit(source[i + 1]))) {
                while (i < n && isDigit(source[i])) i++;
                if (i + 1 < n && source[i] === "." && isDigit(source[i + 1])) {
                    i++;
                    while (i < n && isDigit(source[i])) i++;
                }
                tokens.push({ kind: "number", text: source.slice(start, i), start, end: i });
            } else if (isNameStart(c) || (c === prefix && i + 1 < n && isNameStart(source[i + 1]))) {
                i++;
                while (i < n && (isNameStart(source[i]) || isDigit(source[i]))) i++;
                const word = source.slice(start, i);
                if (["and", "or", "not", "true", "false"].includes(word)) tokens.push({ kind: "keyword", text: word, start, end: i });
                else if (c === prefix) tokens.push({ kind: "variable", text: word.slice(1), start, end: i });
                else tokens.push({ kind: "name", text: word, start, end: i });
            } else if (c === "'" || c === '"') {
                i = readText(source, i, tokens);
            } else {
                const two = source.slice(i, i + 2);
                if (["<=", ">=", "==", "!="].includes(two)) {
                    tokens.push({ kind: "symbol", text: two, start, end: i + 2 });
                    i += 2;
                } else if (two === "&&" || two === "||") {
                    tokens.push({ kind: "keyword", text: two === "&&" ? "and" : "or", start, end: i + 2 });
                    i += 2;
                } else if (c === "!") {
                    tokens.push({ kind: "keyword", text: "not", start, end: i + 1 });
                    i++;
                } else if ("<>+-*/%(),".includes(c)) {
                    tokens.push({ kind: "symbol", text: c, start, end: i + 1 });
                    i++;
                } else {
                    throw unexpectedCharacter(source, i);
                }
            }
        }
        tokens.push({ kind: "end", text: "", start: n, end: n });
        return tokens;
    }

    function index(vocabulary) {
        const values = new Map();
        for (const value of vocabulary.values || []) values.set(value.name, value.type);
        const functions = new Map();
        for (const fn of vocabulary.functions || []) functions.set(fn.name, fn);
        return { values, functions };
    }

    // The type an argument at this place must have; a variadic function's extra arguments repeat the last one.
    function paramType(fn, at) {
        const params = fn.params || [];
        if (at < params.length) return params[at].type;
        return params.length === 0 ? "any" : params[params.length - 1].type;
    }

    // A node is { type, start, pos }: start is where its text begins, which is where a wrong operand is reported.
    function parse(source, vocabulary) {
        const known = index(vocabulary);
        // How a variable is written is the schema's to say; "$" where it does not.
        const tokens = tokenize(source, vocabulary.prefix || "$");
        let at = 0;
        let depth = 0;
        let nodes = 0;

        const peek = () => tokens[at];
        const next = () => {
            const token = tokens[at];
            if (token.kind !== "end") at++;
            return token;
        };
        const isAt = (kind, text) => tokens[at].kind === kind && tokens[at].text === text;
        const atComparison = () => tokens[at].kind === "symbol" && COMPARISONS.includes(tokens[at].text);

        function unexpected(token) {
            if (token.kind === "end") return new Failure("Unexpected end of the expression", -1);
            return fail("Unexpected '" + source.slice(token.start, token.end) + "'", token.start);
        }

        function enter() {
            if (++depth > MAX_DEPTH) throw new Failure("The expression is nested more than " + MAX_DEPTH + " deep", -1);
        }

        function make(type, start, pos) {
            if (++nodes > MAX_NODES) throw new Failure("The expression has more than " + MAX_NODES + " parts", -1);
            return { type, start, pos };
        }

        function expect(operand, wanted) {
            if (operand.type !== "any" && operand.type !== wanted) {
                throw fail("Expected " + WORDS[wanted] + " but got " + WORDS[operand.type], operand.start);
            }
        }

        function addedType(left, right) {
            if (left.type === "text" || right.type === "text") return "text";
            if (left.type === "number" && right.type === "number") return "number";
            if (left.type === "any" || right.type === "any") return "any";
            expect(left.type === "number" ? right : left, "number");
            return "any";
        }

        function binary(op, left, right) {
            let type;
            switch (op.text) {
                case "and":
                case "or":
                    expect(left, "bool");
                    expect(right, "bool");
                    type = "bool";
                    break;
                case "+":
                    type = addedType(left, right);
                    break;
                case "-":
                case "*":
                case "/":
                case "%":
                    expect(left, "number");
                    expect(right, "number");
                    type = "number";
                    break;
                case "==":
                case "!=":
                    if (left.type !== "any" && right.type !== "any" && left.type !== right.type) {
                        throw fail("Cannot compare " + WORDS[left.type] + " with " + WORDS[right.type], op.start);
                    }
                    type = "bool";
                    break;
                default:
                    expect(left, "number");
                    expect(right, "number");
                    type = "bool";
            }
            return make(type, left.start, op.start);
        }

        function parseOr() {
            let left = parseAnd();
            while (isAt("keyword", "or")) {
                const op = next();
                left = binary(op, left, parseAnd());
            }
            return left;
        }

        function parseAnd() {
            let left = parseNot();
            while (isAt("keyword", "and")) {
                const op = next();
                left = binary(op, left, parseNot());
            }
            return left;
        }

        function parseNot() {
            if (isAt("keyword", "not")) {
                const op = next();
                enter();
                const operand = parseNot();
                depth--;
                expect(operand, "bool");
                return make("bool", op.start, op.start);
            }
            return parseCompare();
        }

        function parseCompare() {
            const left = parseSum();
            if (!atComparison()) return left;
            const op = next();
            const node = binary(op, left, parseSum());
            if (atComparison()) throw fail("Comparisons cannot be chained", peek().start);
            return node;
        }

        function parseSum() {
            let left = parseTerm();
            while (isAt("symbol", "+") || isAt("symbol", "-")) {
                const op = next();
                left = binary(op, left, parseTerm());
            }
            return left;
        }

        function parseTerm() {
            let left = parseUnary();
            while (isAt("symbol", "*") || isAt("symbol", "/") || isAt("symbol", "%")) {
                const op = next();
                left = binary(op, left, parseUnary());
            }
            return left;
        }

        function parseUnary() {
            if (isAt("symbol", "-")) {
                const op = next();
                enter();
                const operand = parseUnary();
                depth--;
                expect(operand, "number");
                return make("number", op.start, op.start);
            }
            return parsePrimary();
        }

        function parseCall(name) {
            const fn = known.functions.get(name.text);
            if (!fn) throw fail("Unknown function '" + name.text + "'", name.start);
            next();
            enter();
            const args = [];
            if (!isAt("symbol", ")")) {
                args.push(parseOr());
                while (isAt("symbol", ",")) {
                    next();
                    args.push(parseOr());
                }
            }
            if (!isAt("symbol", ")")) throw unexpected(peek());
            next();
            depth--;
            const required = (fn.params || []).length;
            if (fn.variadic ? args.length < required : args.length !== required) {
                throw fail(name.text + " takes " + (fn.variadic ? "at least " : "") + required + (required === 1 ? " value" : " values")
                    + ", not " + args.length + ",", name.start);
            }
            args.forEach((arg, i) => {
                const wanted = paramType(fn, i);
                if (wanted !== "any") expect(arg, wanted);
            });
            return make(fn.returns, name.start, name.start);
        }

        function parsePrimary() {
            const token = next();
            switch (token.kind) {
                case "number":
                    return make("number", token.start, token.start);
                case "text":
                    return make("text", token.start, token.start);
                case "variable":
                    return make("any", token.start, token.start);
                case "name": {
                    if (isAt("symbol", "(")) return parseCall(token);
                    const type = known.values.get(token.text);
                    if (!type) throw fail("Unknown name '" + token.text + "'", token.start);
                    return make(type, token.start, token.start);
                }
                case "keyword":
                    if (token.text === "true" || token.text === "false") return make("bool", token.start, token.start);
                    throw unexpected(token);
                case "symbol":
                    if (token.text === "(") {
                        enter();
                        const inner = parseOr();
                        if (!isAt("symbol", ")")) throw unexpected(peek());
                        next();
                        depth--;
                        inner.start = token.start;
                        return inner;
                    }
                    throw unexpected(token);
                default:
                    throw unexpected(token);
            }
        }

        const root = parseOr();
        if (peek().kind !== "end") throw unexpected(peek());
        return root;
    }

    /**
     * Whether a source is a valid expression, and what type it has.
     * @param vocabulary the "expressions" object of actions.json
     * @param expected optional: number, bool, text, list or any; a known type that differs is an error
     * @returns {{ok: true, type: string} | {ok: false, error: string, position: number}}
     */
    function check(source, vocabulary, expected) {
        const text = source == null ? "" : String(source);
        try {
            if (text.length > MAX_LENGTH) throw new Failure("The expression is longer than " + MAX_LENGTH + " characters", -1);
            if (/^[ \t\n\r]*$/.test(text)) throw new Failure("The expression is empty", -1);
            const root = parse(text, vocabulary);
            if (expected && expected !== "any" && root.type !== "any" && root.type !== expected) {
                throw fail("Expected " + WORDS[expected] + " but got " + WORDS[root.type], root.start);
            }
            return { ok: true, type: root.type };
        } catch (failure) {
            if (!(failure instanceof Failure)) throw failure;
            return { ok: false, error: failure.message, position: failure.position };
        }
    }

    /** The values and then the functions whose name starts with the prefix, ignoring case, for the editor's completion list. */
    function suggest(vocabulary, prefix) {
        const lower = String(prefix || "").toLowerCase();
        const fits = entry => entry.name.toLowerCase().startsWith(lower);
        const values = (vocabulary.values || []).filter(fits)
            .map(v => ({ name: v.name, kind: "value", type: v.type, description: v.description || "", insert: v.name }));
        const functions = (vocabulary.functions || []).filter(fits)
            .map(f => ({ name: f.name, kind: "function", type: f.returns, description: f.description || "", insert: f.name + "(" }));
        return values.concat(functions);
    }

    return { check, suggest };
})();

if (typeof module !== "undefined") module.exports = Expression;
