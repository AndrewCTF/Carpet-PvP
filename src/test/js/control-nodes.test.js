// Tests of the control flow nodes the editor shares with scripts: for-each, break, continue and stop, and
// conditions made of conditions.
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

test("For Each puts its body inside the loop and continues from done", () => {
    const { graph, add, wire, start } = newGraph();
    const each = add("Control/ForEach", { variable: "slot", list: "list(1, 2, 3)" });
    const body = add("Equipment/Hotbar", { slot: "$slot" });
    const after = add("Equipment/SwapHands");
    wire(start, 0, each);
    wire(each, 0, body);
    wire(each, 1, after);

    assert.deepEqual(NodeCompiler.compile(graph), [
        { type: "FOR_EACH", params: { variable: "slot", list: "list(1, 2, 3)" }, children: [{ type: "HOTBAR", params: { slot: "$slot" } }] },
        { type: "SWAP_HANDS" },
    ]);
});

test("Break, Continue and Stop Program end the chain they are in", () => {
    for (const [node, type] of [["Control/Break", "BREAK"], ["Control/Continue", "CONTINUE"], ["Control/StopProgram", "STOP_PROGRAM"]]) {
        const { graph, add, wire, start } = newGraph();
        const loop = add("Control/Forever");
        const branch = add("Control/If", { condition: "health < 4" });
        const end = add(node);
        wire(start, 0, loop);
        wire(loop, 0, branch);
        wire(branch, 0, end);
        assert.equal((end.outputs || []).length, 0, node + " has nothing to wire on from");
        assert.deepEqual(NodeCompiler.compile(graph), [
            { type: "FOREVER", children: [{ type: "IF", params: { condition: "health < 4" }, children: [{ type }], elseChildren: [] }] },
        ]);
    }
});

test("All Of, Any Of and Not are made of the conditions wired into them", () => {
    const { graph, add, wire, start } = newGraph();
    const branch = add("Control/If-Else");
    const all = add("Conditions/All");
    const not = add("Conditions/Not");
    const any = add("Conditions/Any");
    wire(start, 0, branch);
    wire(all, 0, branch, 1);
    wire(add("Conditions/IsSprinting"), 0, all, 0);
    wire(not, 0, all, 2);
    wire(any, 0, not, 0);
    wire(add("Conditions/IsInWater"), 0, any, 1);
    wire(add("Conditions/Health", { operator: "<", value: 6 }), 0, any, 3);

    assert.deepEqual(NodeCompiler.compile(graph)[0].condition, {
        type: "CONDITION_ALL",
        conditions: [
            { type: "CONDITION_IS_SPRINTING" },
            { type: "CONDITION_NOT", condition: {
                type: "CONDITION_ANY",
                conditions: [{ type: "CONDITION_IS_IN_WATER" }, { type: "CONDITION_HEALTH", params: { operator: "<", value: 6 } }],
            } },
        ],
    }, "the sockets left empty are passed over");
});

test("a condition with nothing wired into it says which node", () => {
    for (const type of ["Conditions/All", "Conditions/Any", "Conditions/Not"]) {
        const { graph, add, wire, start } = newGraph();
        const branch = add("Control/If-Else");
        const empty = add(type);
        wire(start, 0, branch);
        wire(empty, 0, branch, 1);
        assert.throws(() => NodeCompiler.compileStrictly(graph), (error) => error.nodeId === empty.id && /has no condition connected/.test(error.message), type);
    }
});

test("a program with these nodes is laid out as a graph that compiles back to it", () => {
    const actions = [
        { type: "FOR_EACH", params: { variable: "i", list: "range(4)" }, children: [
            { type: "IF_THEN_ELSE",
              condition: { type: "CONDITION_ALL", conditions: [
                  { type: "CONDITION_IS_SNEAKING" },
                  { type: "CONDITION_NOT", condition: { type: "CONDITION_EXPRESSION", params: { expression: "$i == 2" } } }] },
              children: [{ type: "CONTINUE" }], elseChildren: [{ type: "BREAK" }] },
        ] },
        { type: "STOP_PROGRAM" },
    ];
    const graph = new LGraph();
    GraphBuilder.build(graph, schema, actions);
    assert.deepEqual(NodeCompiler.compile(graph), actions);
});
