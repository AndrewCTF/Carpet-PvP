// Tests of expressions in the web editor's nodes: the general nodes that take one, how the compiler treats a
// number field that holds one, what it says about a mistake, and the inspector that shows it.
// Run with: node --test src/test/js   (Gradle's check does, when node is installed)
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const resources = path.join(__dirname, "..", "..", "main", "resources");
const { LiteGraph, LGraph } = require(path.join(resources, "webui", "lib", "litegraph-0.7.18.core.min.js"));
global.LiteGraph = LiteGraph;
global.Expression = require(path.join(resources, "webui", "js", "expression.js"));
const Nodes = require(path.join(resources, "webui", "js", "nodes.js"));
const NodeCompiler = require(path.join(resources, "webui", "js", "node-compiler.js"));
const GraphBuilder = require(path.join(resources, "webui", "js", "graph-builder.js"));
const Inspector = require(path.join(resources, "webui", "js", "inspector.js"));
const schema = JSON.parse(fs.readFileSync(path.join(resources, "carpetlogic", "actions.json"), "utf8"));

Nodes.register(schema);
NodeCompiler.setSchema(schema);

function newGraph() {
    const graph = new LGraph();
    const add = (type, properties) => {
        const node = LiteGraph.createNode(type);
        assert.ok(node, "node type " + type + " is registered");
        for (const [name, value] of Object.entries(properties || {})) node.setProperty(name, value);
        graph.add(node);
        return node;
    };
    const wire = (from, output, to, input = 0) => assert.ok(from.connect(output, to, input), "link is accepted");
    return { graph, add, wire, start: add("Control/Start") };
}

// ── The general nodes ──

test("If runs one branch or the other by its expression and continues from done", () => {
    const { graph, add, wire, start } = newGraph();
    const branch = add("Control/If", { condition: "health < 6 or food < 6" });
    const low = add("Movement/Jump");
    const fine = add("Movement/Dismount");
    const after = add("Equipment/SwapHands");
    wire(start, 0, branch);
    wire(branch, 0, low);
    wire(branch, 1, fine);
    wire(branch, 2, after);

    assert.deepEqual(NodeCompiler.compile(graph), [
        { type: "IF", params: { condition: "health < 6 or food < 6" },
          children: [{ type: "JUMP", params: { ticks: 1 } }], elseChildren: [{ type: "DISMOUNT" }] },
        { type: "SWAP_HANDS" },
    ]);
});

test("While puts its body inside the loop and continues from done", () => {
    const { graph, add, wire, start } = newGraph();
    const loop = add("Control/While", { condition: "$n < 3" });
    const count = add("Variables/SetTo", { name: "n", value: "$n + 1" });
    const after = add("Control/WaitFor", { condition: "on_ground", timeout: 40 });
    wire(start, 0, loop);
    wire(loop, 0, count);
    wire(loop, 1, after);

    assert.deepEqual(NodeCompiler.compile(graph), [
        { type: "WHILE", params: { condition: "$n < 3" }, children: [{ type: "SET", params: { name: "n", value: "$n + 1" } }] },
        { type: "WAIT_FOR", params: { condition: "on_ground", timeout: 40 } },
    ]);
});

test("the old If / Else takes an Expression node for its condition", () => {
    const { graph, add, wire, start } = newGraph();
    const branch = add("Control/If-Else");
    wire(start, 0, branch);
    wire(add("Conditions/Expression", { expression: "target_distance < 4 and not target_blocking" }), 0, branch, 1);

    assert.deepEqual(NodeCompiler.compile(graph)[0].condition,
        { type: "CONDITION_EXPRESSION", params: { expression: "target_distance < 4 and not target_blocking" } });
});

test("a program with the general nodes is laid out as a graph that compiles back to it", () => {
    const actions = [
        { type: "SET", params: { name: "n", value: "0" } },
        { type: "WHILE", params: { condition: "$n < 3" }, children: [
            { type: "IF", params: { condition: "health < 10" }, children: [{ type: "DISMOUNT" }], elseChildren: [{ type: "SWAP_HANDS" }] },
            { type: "SET", params: { name: "n", value: "$n + 1" } },
        ] },
        { type: "WAIT_FOR", params: { condition: "has_target", timeout: 200 } },
    ];
    const graph = new LGraph();
    GraphBuilder.build(graph, schema, actions);
    assert.deepEqual(NodeCompiler.compile(graph), actions);
});

// ── Expressions where a number goes ──

test("a number field holds a number, a variable or an expression", () => {
    assert.equal(NodeCompiler.make("MOVE", { ticks: "40" }).params.ticks, 40, "a number typed into the field is a number");
    assert.equal(NodeCompiler.make("MOVE", { ticks: " $steps * 2 " }).params.ticks, "$steps * 2");
    assert.equal(NodeCompiler.make("LOOK_AT", { x: "x + 3", y: "max(y, 64)", z: -2.5 }).params.y, "max(y, 64)");
    assert.equal(NodeCompiler.make("MOVE", { ticks: "" }).params.ticks, 20, "an empty field is the default");
});

test("a mistake in a field is found and said in the words the server would use", () => {
    const param = schema.actions.MOVE.params.find(p => p.name === "ticks");
    assert.equal(NodeCompiler.problem(param, "helth / 2"), "Unknown name 'helth' at 0");
    assert.equal(NodeCompiler.problem(param, "health /"), "Unexpected end of the expression");
    assert.equal(NodeCompiler.problem(param, "health < 5"), "Expected a number but got true or false at 0");
    assert.equal(NodeCompiler.problem(param, "held_item"), "Expected a number but got text at 0");
    assert.equal(NodeCompiler.problem(param, "health / 2"), null);
    assert.equal(NodeCompiler.problem(param, 40), null);
    assert.equal(NodeCompiler.problem(param, "40"), null);

    const condition = schema.actions.IF.params[0];
    assert.equal(NodeCompiler.problem(condition, "health + 1"), "Expected true or false but got a number at 0");
    assert.equal(NodeCompiler.problem(condition, "health < 10 and"), "Unexpected end of the expression");
    assert.equal(NodeCompiler.problem(condition, "$anything"), null, "what a variable holds is only known when it runs");
});

test("compiling notes every mistake with the node it is in", () => {
    const { graph, add, wire, start } = newGraph();
    const move = add("Movement/Move", { ticks: "helth * 2" });
    const branch = add("Control/If", { condition: "health +" });
    const stray = add("Movement/Jump", { ticks: "nonsense(" });
    wire(start, 0, move);
    wire(move, 0, branch);

    const found = [];
    const actions = NodeCompiler.compile(graph, found);
    assert.deepEqual(found, [
        { nodeId: move.id, node: "Move", param: "ticks", message: "Unknown name 'helth' at 0" },
        { nodeId: branch.id, node: "If", param: "condition", message: "Unexpected end of the expression" },
    ], "and not the node that is not part of the program");
    assert.equal(actions[0].params.ticks, 20, "what is compiled still fits the schema");
    assert.ok(stray);
});

test("a program that is meant to run does not compile with a mistake in it", () => {
    const { graph, add, wire, start } = newGraph();
    const move = add("Movement/Move", { ticks: "helth * 2" });
    wire(start, 0, move);

    assert.throws(() => NodeCompiler.compileStrictly(graph), (error) => {
        assert.equal(error.message, "Move, ticks: Unknown name 'helth' at 0");
        assert.equal(error.nodeId, move.id, "and says which node");
        return true;
    });
    move.setProperty("ticks", "health * 2");
    assert.deepEqual(NodeCompiler.compileStrictly(graph), [{ type: "MOVE", params: { direction: "forward", ticks: "health * 2" } }]);
});

test("a node that is missing its condition says which node", () => {
    const { graph, add, wire, start } = newGraph();
    const branch = add("Control/If-Else");
    wire(start, 0, branch);
    assert.throws(() => NodeCompiler.compileStrictly(graph), (error) => error.nodeId === branch.id && /no condition connected/.test(error.message));
});

// ── The inspector ──

test("the inspector has a field for every parameter, of the kind the parameter calls for", () => {
    const kinds = (type) => Inspector.fields(schema.actions[type].params, {}).map(field => field.name + ":" + field.kind);
    assert.deepEqual(kinds("MOVE"), ["direction:select", "ticks:expression"]);
    assert.deepEqual(kinds("SPRINT"), ["enabled:switch", "ticks:expression"]);
    assert.deepEqual(kinds("IF"), ["condition:expression"]);
    assert.deepEqual(kinds("SET"), ["name:text", "value:expression"]);
    assert.deepEqual(kinds("EXECUTE_COMMAND"), ["command:text"]);
});

test("a field says what it takes", () => {
    const help = (type, name) => Inspector.fields(schema.actions[type].params, {}).find(field => field.name === name).help;
    assert.equal(help("MOVE", "ticks"), "A whole number from 1 to 6000, a variable such as $count, or an expression.");
    assert.match(help("LOOK_AT", "x"), /^A number, a variable/);
    assert.match(help("IF", "condition"), /true or false/);
    assert.match(help("SET", "value"), /a number, text in quotes/);
    assert.equal(Inspector.labelFor("rangeTicks"), "Range Ticks");
});

test("a field the server sent a list for offers it and still takes anything", () => {
    const fields = Inspector.fields(schema.actions.GIVE_KIT.params, { kit: ["sword", "mace"] });
    assert.equal(fields[0].kind, "text");
    assert.deepEqual(fields[0].options, ["sword", "mace"]);
});

test("the name being typed is found where the caret is", () => {
    assert.deepEqual(Inspector.wordAt("health < tar", 12), { word: "tar", start: 9 });
    assert.deepEqual(Inspector.wordAt("health < tar", 6), { word: "health", start: 0 });
    assert.deepEqual(Inspector.wordAt("$co + 1", 3), { word: "$co", start: 0 });
    assert.deepEqual(Inspector.wordAt("min(he", 6), { word: "he", start: 4 });
    assert.equal(Inspector.wordAt("held_item == 'dia", 17), null, "not inside text");
    assert.equal(Inspector.wordAt("health < 10", 11), null, "not after a number");
    assert.equal(Inspector.wordAt("health < ", 9), null);
    assert.deepEqual(Inspector.wordAt("'a' + na", 8), { word: "na", start: 6 }, "after text that was closed");
});

test("the names the server knows are offered as they are typed", () => {
    const vocabulary = schema.expressions;
    const offered = Inspector.suggestions("tar", vocabulary, []).map(entry => entry.insert);
    assert.deepEqual(offered, ["target_distance", "target_health", "target_held_item", "target_name", "target_blocking"]);
    assert.deepEqual(Inspector.suggestions("cl", vocabulary, []).map(entry => entry.insert), ["clamp("], "a function comes with its bracket");
    assert.deepEqual(Inspector.suggestions("health", vocabulary, []), [], "nothing is offered for a name that is already whole");
    assert.ok(Inspector.suggestions("h", vocabulary, []).length <= 7);
    const entry = Inspector.suggestions("foo", vocabulary, [])[0];
    assert.equal(entry.type, "number");
    assert.ok(entry.description.length > 10, "with what it is");
});

test("after the variable sign the program's own variables are offered", () => {
    const { graph, add } = newGraph();
    add("Variables/SetTo", { name: "kills", value: "0" });
    add("Variables/Set", { name: "count", value: 3 });
    add("Variables/Add", { name: "count", amount: 1 });
    add("Variables/SetTo", { name: "not a name", value: "0" });

    assert.deepEqual(Inspector.variablesOf(graph), ["count", "kills"]);
    assert.deepEqual(Inspector.suggestions("$", schema.expressions, ["count", "kills"]).map(entry => entry.insert), ["$count", "$kills"]);
    assert.deepEqual(Inspector.suggestions("$k", schema.expressions, ["count", "kills"]).map(entry => entry.insert), ["$kills"]);
});
