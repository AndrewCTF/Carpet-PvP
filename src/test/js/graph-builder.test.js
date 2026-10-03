// Tests of the graph builder: a program without a saved graph (a preset) must open as a graph that
// compiles back to the same program.
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const resources = path.join(__dirname, "..", "..", "main", "resources");
const { LiteGraph, LGraph } = require(path.join(resources, "webui", "lib", "litegraph-0.7.18.core.min.js"));
global.LiteGraph = LiteGraph;
const Nodes = require(path.join(resources, "webui", "js", "nodes.js"));
const NodeCompiler = require(path.join(resources, "webui", "js", "node-compiler.js"));
const GraphBuilder = require(path.join(resources, "webui", "js", "graph-builder.js"));
const schema = JSON.parse(fs.readFileSync(path.join(resources, "carpetlogic", "actions.json"), "utf8"));
const presets = JSON.parse(fs.readFileSync(path.join(resources, "carpetlogic", "presets.json"), "utf8"));

Nodes.register(schema);
NodeCompiler.setSchema(schema);

// What the compiler emits for a tree: every parameter present, child lists always there, no SEQUENCE.
function normalise(actions) {
    return GraphBuilder.flatten(actions).map(action => {
        const def = schema.actions[action.type];
        const out = { type: action.type };
        if (def.params.length > 0) {
            out.params = {};
            for (const p of def.params) out.params[p.name] = (action.params || {})[p.name] ?? p.default;
        }
        if (action.condition) out.condition = normalise([action.condition])[0];
        const slots = def.slots || [];
        if (slots.includes("children")) out.children = normalise(action.children || []);
        if (slots.includes("elseChildren")) out.elseChildren = normalise(action.elseChildren || []);
        return out;
    });
}

function roundTrip(actions) {
    const graph = new LGraph();
    GraphBuilder.build(graph, schema, actions);
    return { graph, compiled: NodeCompiler.compile(graph) };
}

for (const preset of presets) {
    test("preset '" + preset.name + "' opens as a graph that compiles back to it", () => {
        assert.deepEqual(roundTrip(preset.actions).compiled, normalise(preset.actions));
    });
}

test("a tree with every action type survives build and compile", () => {
    const steps = [];
    const conditions = [];
    for (const [type, def] of Object.entries(schema.actions)) {
        if (def.kind === "condition") conditions.push({ type });
        else if (def.kind === "action") steps.push({ type });
    }
    const branches = conditions.map((condition, i) => ({
        type: "IF_THEN_ELSE", condition,
        children: i % 2 ? [{ type: "JUMP" }] : [],
        elseChildren: i % 3 ? [{ type: "DISMOUNT" }, { type: "SWAP_HANDS" }] : [],
    }));
    const actions = [
        ...steps,
        { type: "LOOP", params: { count: 7 }, children: [{ type: "JUMP", params: { ticks: 4 } }, ...branches] },
        { type: "SEQUENCE", children: [{ type: "DISMOUNT" }, { type: "SEQUENCE", children: [{ type: "JUMP" }] }] },
        { type: "FOREVER", children: [{ type: "LOOP", children: [{ type: "DELAY" }] }, { type: "DELAY", params: { ticks: 3 } }] },
    ];
    assert.deepEqual(roundTrip(actions).compiled, normalise(actions));
});

test("built nodes do not sit on top of each other", () => {
    const wtap = presets.find(p => p.id === "preset_wtap");
    const { graph } = roundTrip([...wtap.actions]);
    const boxes = graph._nodes.map(n => [n.pos[0], n.pos[1], n.pos[0] + n.size[0], n.pos[1] + n.size[1]]);
    for (let i = 0; i < boxes.length; i++) {
        for (let j = i + 1; j < boxes.length; j++) {
            const [a, b] = [boxes[i], boxes[j]];
            const apart = a[2] <= b[0] || b[2] <= a[0] || a[3] <= b[1] || b[3] <= a[1];
            assert.ok(apart, graph._nodes[i].title + " overlaps " + graph._nodes[j].title);
        }
    }
});
