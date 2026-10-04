// Tests of the page's expression checker against the shared case table, which the server's evaluator is tested against too.
// Run with: node --test src/test/js
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.join(__dirname, "..", "..");
const Expression = require(path.join(root, "main", "resources", "webui", "js", "expression.js"));
const vocabulary = JSON.parse(fs.readFileSync(path.join(root, "main", "resources", "carpetlogic", "actions.json"), "utf8")).expressions;
const cases = JSON.parse(fs.readFileSync(path.join(root, "test", "resources", "carpetlogic", "expression-cases.json"), "utf8"));

test("the table is large enough to mean something", () => {
    assert.ok(cases.length >= 120);
});

for (const [number, c] of cases.entries()) {
    test("case " + number + ": " + JSON.stringify(c.source) + (c.expected ? " as " + c.expected : ""), () => {
        const result = Expression.check(c.source, vocabulary, c.expected);
        if (c.error !== undefined) {
            assert.deepEqual(result, { ok: false, error: c.error, position: c.position });
        } else {
            assert.deepEqual(result, { ok: true, type: c.type });
        }
    });
}

// The same limit cases are in the Java test.
const limits = [
    ["a source of 1024 characters", "1" + " ".repeat(1023), { ok: true, type: "number" }],
    ["a source of 1025 characters", "1" + " ".repeat(1024), { ok: false, error: "The expression is longer than 1024 characters", position: -1 }],
    ["32 nested parentheses", "(".repeat(32) + "1" + ")".repeat(32), { ok: true, type: "number" }],
    ["33 nested parentheses", "(".repeat(33) + "1" + ")".repeat(33), { ok: false, error: "The expression is nested more than 32 deep", position: -1 }],
    ["32 minus signs", "-".repeat(32) + "1", { ok: true, type: "number" }],
    ["33 minus signs", "-".repeat(33) + "1", { ok: false, error: "The expression is nested more than 32 deep", position: -1 }],
    ["32 nested calls", "abs(".repeat(32) + "1" + ")".repeat(32), { ok: true, type: "number" }],
    ["33 nested calls", "abs(".repeat(33) + "1" + ")".repeat(33), { ok: false, error: "The expression is nested more than 32 deep", position: -1 }],
    ["33 words not", "not ".repeat(33) + "true", { ok: false, error: "The expression is nested more than 32 deep", position: -1 }],
    ["255 parts", "1" + "+1".repeat(127), { ok: true, type: "number" }],
    ["257 parts", "1" + "+1".repeat(128), { ok: false, error: "The expression has more than 256 parts", position: -1 }],
    ["many arguments are parts too", "list(" + "1,".repeat(256) + "1)", { ok: false, error: "The expression has more than 256 parts", position: -1 }],
];
for (const [name, source, expected] of limits) {
    test("limit: " + name, () => {
        assert.deepEqual(Expression.check(source, vocabulary), expected);
    });
}

test("a missing source is empty", () => {
    assert.equal(Expression.check(undefined, vocabulary).error, "The expression is empty");
    assert.equal(Expression.check(null, vocabulary).error, "The expression is empty");
});

test("every function in the vocabulary can be called with the arity it declares", () => {
    const sample = { number: "0", bool: "true", text: "'a'", list: "list(0)", any: "'a'" };
    for (const fn of vocabulary.functions) {
        const args = fn.params.map(p => sample[p.type]).join(", ");
        const result = Expression.check(fn.name + "(" + args + ")", vocabulary);
        assert.deepEqual(result, { ok: true, type: fn.returns }, fn.name);
    }
});

test("suggest lists the values first and then the functions that start with the prefix", () => {
    const found = Expression.suggest(vocabulary, "t");
    const names = found.map(s => s.name);
    assert.deepEqual(names, ["target_distance", "target_health", "tick", "target_held_item", "target_name", "target_blocking", "text"]);
    assert.deepEqual(found.map(s => s.kind), ["value", "value", "value", "value", "value", "value", "function"]);
});

test("suggest ignores case and fills in every field", () => {
    const [value] = Expression.suggest(vocabulary, "HEALTH");
    assert.deepEqual({ ...value, description: typeof value.description }, {
        name: "health", kind: "value", type: "number", description: "string", insert: "health"
    });
    assert.ok(value.description.length > 0);
    const [fn] = Expression.suggest(vocabulary, "CLA");
    assert.equal(fn.name, "clamp");
    assert.equal(fn.kind, "function");
    assert.equal(fn.type, "number");
    assert.equal(fn.insert, "clamp(");
});

test("suggest with an empty prefix lists everything and an unknown prefix nothing", () => {
    assert.equal(Expression.suggest(vocabulary, "").length, vocabulary.values.length + vocabulary.functions.length);
    assert.equal(Expression.suggest(vocabulary, undefined).length, vocabulary.values.length + vocabulary.functions.length);
    assert.deepEqual(Expression.suggest(vocabulary, "zzz"), []);
});

test("a value and a function that share a prefix keep the values first", () => {
    const found = Expression.suggest(vocabulary, "r");
    assert.deepEqual(found.map(s => s.name), ["random", "round", "range"]);
    assert.deepEqual(found.map(s => s.kind), ["value", "function", "function"]);
});
