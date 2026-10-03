// Tests of the web editor's combat nodes: what a graph of them compiles to, and what the widgets offer.
// The lists a node cannot know from the schema are the ones the server reports through /api/settings.
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

// What the server sends next to the schema: the lists a parameter names by optionsFrom, the kits this
// server's folder holds and the setting names /bot option takes.
const settings = {
    combatStyles: ["sword", "crystal", "anchor", "ranged", "mace", "smp"],
    difficulties: ["beginner", "casual", "average", "skilled", "expert"],
    kits: ["sword", "axe", "smp", "mace", "crystal"],
    combatOptions: ["combat", "difficulty", "combatstyle", "critical", "wtap"],
};

Nodes.register(schema, settings);
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

const types = (actions) => actions.map(a => a.type);
const widget = (node, property) => LiteGraph.createNode(node).widgets.find(w => w.options.property === property);

test("every combat node has an editor node with the schema's parameters", () => {
    for (const type of ["COMBAT_START", "COMBAT_STOP", "FIGHT", "SET_COMBAT_OPTION", "GIVE_KIT",
        "CONDITION_IS_FIGHTING", "CONDITION_HAS_TARGET", "CONDITION_TARGET_DISTANCE", "CONDITION_TARGET_HEALTH"]) {
        const def = schema.actions[type];
        assert.ok(def, type + " is in the schema");
        const node = LiteGraph.createNode(def.node);
        assert.ok(node, def.node + " is registered");
        assert.deepEqual(Object.keys(node.properties), def.params.map(p => p.name), type + " properties");
        assert.deepEqual((node.widgets || []).map(w => w.options.property), def.params.map(p => p.name), type + " widgets");
        for (const param of def.params) assert.equal(node.properties[param.name], param.default);
    }
});

test("the combat nodes are in the Combat palette group and the conditions next to the others", () => {
    for (const type of ["COMBAT_START", "COMBAT_STOP", "FIGHT", "SET_COMBAT_OPTION", "GIVE_KIT"]) {
        assert.ok(schema.actions[type].node.startsWith("Combat/"), type + " is a Combat node");
    }
    for (const type of ["CONDITION_IS_FIGHTING", "CONDITION_HAS_TARGET", "CONDITION_TARGET_DISTANCE", "CONDITION_TARGET_HEALTH"]) {
        assert.ok(schema.actions[type].node.startsWith("Conditions/"), type + " is a Conditions node");
    }
});

test("a combat sequence compiles to the actions the schema declares", () => {
    const { graph, add, wire, start } = newGraph();
    const begin = add("Combat/CombatStart", { style: "mace", difficulty: "expert", targets: "bots", target: "Steve" });
    const option = add("Combat/CombatOption", { key: "critical", value: "true" });
    const kit = add("Combat/GiveKit", { kit: "mace" });
    const fight = add("Combat/Fight", { targets: "mobs", timeout: 900, range: 24, rangeTicks: 90 });
    const end = add("Combat/CombatStop");
    wire(start, 0, begin);
    wire(begin, 0, option);
    wire(option, 0, kit);
    wire(kit, 0, fight);
    wire(fight, 0, end);

    assert.deepEqual(NodeCompiler.compile(graph), [
        { type: "COMBAT_START", params: { style: "mace", difficulty: "expert", targets: "bots", target: "Steve" } },
        { type: "SET_COMBAT_OPTION", params: { key: "critical", value: "true" } },
        { type: "GIVE_KIT", params: { kit: "mace" } },
        {
            type: "FIGHT",
            params: { style: "sword", difficulty: "average", targets: "mobs", target: "", timeout: 900, range: 24, rangeTicks: 90 },
        },
        { type: "COMBAT_STOP" },
    ]);
});

test("the style and difficulty dropdowns hold the lists the server reported", () => {
    for (const node of ["Combat/CombatStart", "Combat/Fight"]) {
        const style = widget(node, "style");
        const difficulty = widget(node, "difficulty");
        assert.equal(style.type, "combo");
        assert.equal(difficulty.type, "combo");
        assert.deepEqual(style.options.values, settings.combatStyles);
        assert.deepEqual(difficulty.options.values, settings.difficulties);
    }
});

test("the kit dropdown holds the kits the server reported, and the option key the names it accepts", () => {
    const kit = widget("Combat/GiveKit", "kit");
    assert.equal(kit.type, "combo");
    assert.deepEqual(kit.options.values, settings.kits);
    const key = widget("Combat/CombatOption", "key");
    assert.equal(key.type, "combo");
    assert.deepEqual(key.options.values, settings.combatOptions);
    // the value of an option is free text: "true" for a switch, "3.5" for a number
    assert.equal(widget("Combat/CombatOption", "value").type, "text");
});

test("without the server's lists the same fields are plain text, so a name can still be typed", () => {
    try {
        Nodes.register(schema, {});
        assert.equal(widget("Combat/GiveKit", "kit").type, "text");
        assert.equal(widget("Combat/CombatOption", "key").type, "text");
        assert.equal(widget("Combat/CombatStart", "style").type, "text");
    } finally {
        Nodes.register(schema, settings);
    }
});

test("a kit the schema cannot check is passed on, and the server is the one to refuse it", () => {
    assert.deepEqual(NodeCompiler.make("GIVE_KIT", { kit: "no-such-kit" }),
        { type: "GIVE_KIT", params: { kit: "no-such-kit" } });
    assert.deepEqual(NodeCompiler.make("COMBAT_START", { targets: "everyone" }),
        { type: "COMBAT_START", params: { style: "sword", difficulty: "average", targets: "players", target: "" } });
});

test("the new conditions can be tested in If/Else and WaitUntil", () => {
    for (const [node, params, condition] of [
        ["Conditions/IsFighting", undefined, { type: "CONDITION_IS_FIGHTING" }],
        ["Conditions/HasTarget", undefined, { type: "CONDITION_HAS_TARGET" }],
        ["Conditions/TargetDistance", { operator: "<", value: 3.5 },
            { type: "CONDITION_TARGET_DISTANCE", params: { operator: "<", value: 3.5 } }],
        ["Conditions/TargetHealth", { operator: "<=", value: 6 },
            { type: "CONDITION_TARGET_HEALTH", params: { operator: "<=", value: 6 } }],
    ]) {
        const { graph, add, wire, start } = newGraph();
        const branch = add("Control/If-Else");
        wire(start, 0, branch);
        wire(add(node, params), 0, branch, 1);
        wire(branch, 0, add("Combat/Fight"));
        wire(branch, 2, add("Combat/CombatStop"));

        assert.deepEqual(NodeCompiler.compile(graph), [{
            type: "IF_THEN_ELSE",
            condition,
            children: [{ type: "FIGHT", params: { style: "sword", difficulty: "average", targets: "players", target: "", timeout: 600, range: 16, rangeTicks: 60 } }],
            elseChildren: [],
        }, { type: "COMBAT_STOP" }]);
    }
});

test("WaitUntil holds the sequence on a combat condition", () => {
    const { graph, add, wire, start } = newGraph();
    const wait = add("Control/WaitUntil", { timeout: 400 });
    const after = add("Combat/CombatStop");
    wire(start, 0, wait);
    wire(add("Conditions/HasTarget"), 0, wait, 1);
    wire(wait, 0, after);

    assert.deepEqual(NodeCompiler.compile(graph), [
        { type: "WAIT_UNTIL", params: { timeout: 400 }, condition: { type: "CONDITION_HAS_TARGET" } },
        { type: "COMBAT_STOP" },
    ]);
});

test("the new events are offered on the OnEvent node and compile like the others", () => {
    const event = widget("Events/OnEvent", "event");
    for (const name of ["when_kill", "when_totem_pop", "when_target_acquired", "when_hit"]) {
        assert.ok(event.options.values.includes(name), "OnEvent offers " + name);
    }
    const { graph, add, wire, start } = newGraph();
    const node = add("Events/OnEvent", { event: "when_kill" });
    wire(start, 0, node);
    wire(node, 0, add("Combat/CombatOption", { key: "difficulty", value: "skilled" }));
    wire(node, 1, add("Combat/CombatStop"));

    assert.deepEqual(NodeCompiler.compile(graph), [
        {
            type: "ON_EVENT",
            params: { event: "when_kill", target: "", value: 5 },
            children: [{ type: "SET_COMBAT_OPTION", params: { key: "difficulty", value: "skilled" } }],
        },
        { type: "COMBAT_STOP" },
    ]);
});

test("a fight node survives being saved and reopened", () => {
    const { graph, add, wire, start } = newGraph();
    const kit = add("Combat/GiveKit", { kit: "crystal" });
    const fight = add("Combat/Fight", { style: "crystal", difficulty: "skilled", targets: "players", timeout: "$rounds", range: 32 });
    wire(start, 0, kit);
    wire(kit, 0, fight);

    const reopened = new LGraph();
    reopened.configure(JSON.parse(JSON.stringify(graph.serialize())));
    assert.deepEqual(NodeCompiler.compile(reopened), NodeCompiler.compile(graph));
    assert.deepEqual(types(NodeCompiler.compile(reopened)), ["GIVE_KIT", "FIGHT"]);
    assert.equal(NodeCompiler.compile(reopened)[1].params.timeout, "$rounds");
});