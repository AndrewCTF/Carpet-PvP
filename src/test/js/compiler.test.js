// Tests of the web editor's graph compiler, against the bundled LiteGraph and the real action schema.
// Run with: node --test src/test/js   (Gradle's check does, when node is installed)
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const resources = path.join(__dirname, "..", "..", "main", "resources");
const { LiteGraph, LGraph } = require(path.join(resources, "webui", "lib", "litegraph-0.7.18.core.min.js"));
global.LiteGraph = LiteGraph;
const Nodes = require(path.join(resources, "webui", "js", "nodes.js"));
const NodeCompiler = require(path.join(resources, "webui", "js", "node-compiler.js"));
const schema = JSON.parse(fs.readFileSync(path.join(resources, "carpetlogic", "actions.json"), "utf8"));

Nodes.register(schema);
NodeCompiler.setSchema(schema);

// A graph with a Start node; add(type, properties) adds a node, wire(from, output, to) connects a flow output.
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

const types = (actions) => actions.map(a => a.type);

test("every action in the schema has an editor node whose properties are its parameters", () => {
    for (const [type, def] of Object.entries(schema.actions)) {
        const node = LiteGraph.createNode(def.node);
        assert.ok(node, type + " has the editor node " + def.node);
        assert.deepEqual(Object.keys(node.properties), def.params.map(p => p.name), def.node + " properties");
        assert.deepEqual((node.widgets || []).map(w => w.options.property), def.params.map(p => p.name), def.node + " widgets");
        for (const param of def.params) assert.equal(node.properties[param.name], param.default);
    }
    assert.equal(new Set(Object.values(schema.actions).map(d => d.node)).size, Object.keys(schema.actions).length, "one node per action");
});

test("a chain compiles to its actions in order, with the node settings as parameters", () => {
    const { graph, add, wire, start } = newGraph();
    const sprint = add("Movement/Sprint", { enabled: false });
    const move = add("Movement/Move", { direction: "backward", ticks: 40 });
    const turn = add("Look/Turn", { yaw: -45, pitch: 10 });
    wire(start, 0, sprint);
    wire(sprint, 0, move);
    wire(move, 0, turn);

    assert.deepEqual(NodeCompiler.compile(graph), [
        { type: "SPRINT", params: { enabled: false, ticks: 0 } },
        { type: "MOVE", params: { direction: "backward", ticks: 40 } },
        { type: "TURN", params: { yaw: -45, pitch: 10 } },
    ]);
});

test("settings outside what the schema allows fall back to the default or the nearest allowed value", () => {
    const { graph, add, wire, start } = newGraph();
    const hotbar = add("Equipment/Hotbar", { slot: 0 });
    const move = add("Movement/Move", { direction: "sideways", ticks: "soon" });
    const jump = add("Movement/Jump", { ticks: 2.6 });
    wire(start, 0, hotbar);
    wire(hotbar, 0, move);
    wire(move, 0, jump);

    assert.deepEqual(NodeCompiler.compile(graph), [
        { type: "HOTBAR", params: { slot: 1 } },
        { type: "MOVE", params: { direction: "forward", ticks: 20 } },
        { type: "JUMP", params: { ticks: 3 } },
    ]);
});

test("Repeat puts its body inside the loop, once, and continues from done", () => {
    const { graph, add, wire, start } = newGraph();
    const repeat = add("Control/Repeat", { count: 3 });
    const body1 = add("Movement/Jump");
    const body2 = add("Movement/Dismount");
    const after = add("Equipment/SwapHands");
    wire(start, 0, repeat);
    wire(repeat, 0, body1);
    wire(body1, 0, body2);
    wire(repeat, 1, after);

    assert.deepEqual(NodeCompiler.compile(graph), [
        { type: "LOOP", params: { count: 3 }, children: [{ type: "JUMP", params: { ticks: 1 } }, { type: "DISMOUNT" }] },
        { type: "SWAP_HANDS" },
    ]);
});

test("Repeat with nothing on done ends the chain after the loop", () => {
    const { graph, add, wire, start } = newGraph();
    const repeat = add("Control/Repeat");
    wire(start, 0, repeat);
    wire(repeat, 0, add("Movement/Dismount"));

    assert.deepEqual(NodeCompiler.compile(graph), [
        { type: "LOOP", params: { count: 3 }, children: [{ type: "DISMOUNT" }] },
    ]);
});

test("If/Else puts then and else in their own branches and continues from done", () => {
    const { graph, add, wire, start } = newGraph();
    const branch = add("Control/If-Else");
    const condition = add("Conditions/Health", { operator: ">=", value: 6 });
    const thenNode = add("Movement/Jump");
    const elseNode = add("Movement/Dismount");
    const after = add("Equipment/SwapHands");
    wire(start, 0, branch);
    wire(condition, 0, branch, 1);
    wire(branch, 0, thenNode);
    wire(branch, 1, elseNode);
    wire(branch, 2, after);

    assert.deepEqual(NodeCompiler.compile(graph), [
        {
            type: "IF_THEN_ELSE",
            condition: { type: "CONDITION_HEALTH", params: { operator: ">=", value: 6 } },
            children: [{ type: "JUMP", params: { ticks: 1 } }],
            elseChildren: [{ type: "DISMOUNT" }],
        },
        { type: "SWAP_HANDS" },
    ]);
});

test("If/Else with only an else branch has an empty then", () => {
    const { graph, add, wire, start } = newGraph();
    const branch = add("Control/If-Else");
    wire(start, 0, branch);
    wire(add("Conditions/IsSprinting"), 0, branch, 1);
    wire(branch, 1, add("Movement/Dismount"));

    const [action] = NodeCompiler.compile(graph);
    assert.deepEqual(action.children, []);
    assert.deepEqual(types(action.elseChildren), ["DISMOUNT"]);
});

test("If/Else without a condition is a compile error", () => {
    const { graph, add, wire, start } = newGraph();
    const branch = add("Control/If-Else");
    wire(start, 0, branch);
    wire(branch, 0, add("Movement/Jump"));

    assert.throws(() => NodeCompiler.compile(graph), /no condition/);
});

test("control nodes nest: an If/Else inside a Repeat inside a Forever", () => {
    const { graph, add, wire, start } = newGraph();
    const forever = add("Control/Forever");
    const repeat = add("Control/Repeat", { count: 2 });
    const branch = add("Control/If-Else");
    const afterBranch = add("Control/Delay", { ticks: 5 });
    const afterRepeat = add("Movement/StopMovement");
    wire(start, 0, forever);
    wire(forever, 0, repeat);
    wire(repeat, 0, branch);
    wire(add("Conditions/IsInWater"), 0, branch, 1);
    wire(branch, 0, add("Movement/Jump"));
    wire(branch, 2, afterBranch);
    wire(repeat, 1, afterRepeat);

    assert.deepEqual(NodeCompiler.compile(graph), [{
        type: "FOREVER",
        children: [
            {
                type: "LOOP", params: { count: 2 },
                children: [
                    {
                        type: "IF_THEN_ELSE",
                        condition: { type: "CONDITION_IS_IN_WATER" },
                        children: [{ type: "JUMP", params: { ticks: 1 } }],
                        elseChildren: [],
                    },
                    { type: "DELAY", params: { ticks: 5 } },
                ],
            },
            { type: "STOP_MOVEMENT" },
        ],
    }]);
});

test("Sequence runs each of its outputs once, in order", () => {
    const { graph, add, wire, start } = newGraph();
    const sequence = add("Control/Sequence");
    wire(start, 0, sequence);
    wire(sequence, 0, add("Movement/Jump"));
    wire(sequence, 2, add("Movement/Dismount"));
    wire(sequence, 1, add("Equipment/SwapHands"));

    const [action, ...rest] = NodeCompiler.compile(graph);
    assert.deepEqual(types(action.children), ["JUMP", "SWAP_HANDS", "DISMOUNT"]);
    assert.deepEqual(rest, []);
});

test("an output wired to two nodes is a compile error", () => {
    const { graph, add, wire, start } = newGraph();
    const jump = add("Movement/Jump");
    wire(start, 0, jump);
    wire(jump, 0, add("Movement/Dismount"));
    wire(jump, 0, add("Equipment/SwapHands"));

    assert.throws(() => NodeCompiler.compile(graph), /only lead to one/);
});

test("macro nodes expand to actions the schema declares", () => {
    const { graph, add, wire, start } = newGraph();
    const combo = add("Crystal/CrystalCombo", { obsidianSlot: 4, crystalSlot: 5, swordSlot: 6 });
    const auto = add("Crystal/AutoCrystal", { crystalSlot: 5, ticks: 40, speed: 4 });
    wire(start, 0, combo);
    wire(combo, 0, auto);

    const [sequence, loop] = NodeCompiler.compile(graph);
    assert.equal(sequence.type, "SEQUENCE");
    assert.deepEqual(sequence.children.filter(a => a.type === "HOTBAR").map(a => a.params.slot), [4, 5, 6]);
    assert.deepEqual(types(sequence.children).filter(t => t !== "HOTBAR" && t !== "DELAY"), ["PLACE_BLOCK", "PLACE_CRYSTAL", "DETONATE_CRYSTAL"]);
    assert.deepEqual(loop.params, { count: 10 });
    assert.deepEqual(loop.children, [
        { type: "HOTBAR", params: { slot: 5 } },
        { type: "PLACE_CRYSTAL", params: { ticks: 1 } },
        { type: "DETONATE_CRYSTAL", params: { ticks: 3 } },
    ]);
});

test("a variable reference survives being saved and reopened", () => {
    const { graph, add, wire, start } = newGraph();
    const set = add("Variables/Set", { name: "steps", value: 3 });
    const move = add("Movement/Move", { ticks: "$steps" });
    const event = add("Events/OnEvent", { event: "when_hit" });
    wire(start, 0, set);
    wire(set, 0, move);
    wire(move, 0, event);
    wire(event, 0, add("Combat/Attack", { mode: "continuous" }));

    const reopened = new LGraph();
    reopened.configure(JSON.parse(JSON.stringify(graph.serialize())));
    assert.deepEqual(NodeCompiler.compile(reopened), NodeCompiler.compile(graph));
});

test("make refuses a parameter name the schema does not declare", () => {
    assert.throws(() => NodeCompiler.make("MOVE", { duration: 40 }), /no parameter 'duration'/);
    assert.throws(() => NodeCompiler.make("TELEPORT", {}), /Unknown action type/);
});

test("a number field may hold a variable instead of a number", () => {
    const { graph, add, wire, start } = newGraph();
    const set = add("Variables/Set", { name: "steps", value: 3 });
    const add2 = add("Variables/Add", { name: "steps", amount: 2 });
    const move = add("Movement/Move", { ticks: "$steps" });
    const check = add("Conditions/Variable", { name: "steps", operator: "<", value: 9 });
    const branch = add("Control/If-Else");
    wire(start, 0, set);
    wire(set, 0, add2);
    wire(add2, 0, move);
    wire(move, 0, branch);
    wire(check, 0, branch, 1);

    assert.deepEqual(NodeCompiler.compile(graph), [
        { type: "SET_VARIABLE", params: { name: "steps", value: 3 } },
        { type: "ADD_VARIABLE", params: { name: "steps", amount: 2 } },
        { type: "MOVE", params: { direction: "forward", ticks: "$steps" } },
        {
            type: "IF_THEN_ELSE",
            condition: { type: "CONDITION_VARIABLE", params: { name: "steps", operator: "<", value: 9 } },
            children: [],
            elseChildren: [],
        },
    ]);
});

test("a variable the schema does not name that way falls back to the default", () => {
    assert.deepEqual(NodeCompiler.make("MOVE", { ticks: "$two words" }),
        { type: "MOVE", params: { direction: "forward", ticks: 20 } });
    assert.deepEqual(NodeCompiler.make("MOVE", { ticks: "$" }),
        { type: "MOVE", params: { direction: "forward", ticks: 20 } });
    assert.deepEqual(NodeCompiler.make("LOOK_AT", { x: "$x" }),
        { type: "LOOK_AT", params: { x: "$x", y: 64, z: 0 } });
});

test("the variable prefix is the one the schema declares, not one written into the compiler", () => {
    const other = JSON.parse(JSON.stringify(schema));
    other.variables.referencePrefix = "%";
    NodeCompiler.setSchema(other);
    try {
        assert.equal(NodeCompiler.make("MOVE", { ticks: "$steps" }).params.ticks, 20);
        assert.equal(NodeCompiler.make("MOVE", { ticks: "%steps" }).params.ticks, "%steps");
    } finally {
        NodeCompiler.setSchema(schema);
    }
});

test("WaitUntil holds the sequence until its condition holds or its timeout runs out", () => {
    const { graph, add, wire, start } = newGraph();
    const wait = add("Control/WaitUntil", { timeout: 40 });
    const move = add("Movement/Move");
    wire(start, 0, wait);
    wire(add("Conditions/Health", { operator: "<", value: 6 }), 0, wait, 1);
    wire(wait, 0, move);

    assert.deepEqual(NodeCompiler.compile(graph), [
        {
            type: "WAIT_UNTIL",
            params: { timeout: 40 },
            condition: { type: "CONDITION_HEALTH", params: { operator: "<", value: 6 } },
        },
        { type: "MOVE", params: { direction: "forward", ticks: 20 } },
    ]);
});

test("WaitUntil without a condition is a compile error", () => {
    const { graph, add, wire, start } = newGraph();
    const wait = add("Control/WaitUntil");
    wire(start, 0, wait);
    wire(wait, 0, add("Movement/Jump"));

    assert.throws(() => NodeCompiler.compile(graph), /no condition/);
});

test("OnEvent registers a reaction, and the sequence carries on from next", () => {
    const { graph, add, wire, start } = newGraph();
    const event = add("Events/OnEvent", { event: "when_health_below", target: "Steve", value: 8 });
    const reaction = add("Combat/Attack", { mode: "continuous", ticks: 10 });
    const after = add("Equipment/SwapHands");
    wire(start, 0, event);
    wire(event, 0, reaction);
    wire(event, 1, after);

    assert.deepEqual(NodeCompiler.compile(graph), [
        {
            type: "ON_EVENT",
            params: { event: "when_health_below", target: "Steve", value: 8 },
            children: [{ type: "ATTACK", params: { mode: "continuous", interval: 10, ticks: 10 } }],
        },
        { type: "SWAP_HANDS" },
    ]);
});

test("an OnEvent body may hold a whole reaction of its own", () => {
    const { graph, add, wire, start } = newGraph();
    const event = add("Events/OnEvent", { event: "when_hit" });
    const loop = add("Control/Repeat", { count: 2 });
    const stop = add("Navigation/NavStop");
    wire(start, 0, event);
    wire(event, 0, loop);
    wire(loop, 0, stop);
    wire(event, 1, add("Movement/StopMovement"));

    const [onEvent, ...rest] = NodeCompiler.compile(graph);
    assert.equal(onEvent.type, "ON_EVENT");
    assert.deepEqual(onEvent.children, [{
        type: "LOOP", params: { count: 2 }, children: [{ type: "NAV_STOP" }],
    }]);
    assert.deepEqual(types(rest), ["STOP_MOVEMENT"]);
});
