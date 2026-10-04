// Tests of the web editor's node library: what it lists and what a search finds, against the bundled
// LiteGraph and the real action schema.
// Run with: node --test src/test/js   (Gradle's check does, when node is installed)
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const resources = path.join(__dirname, "..", "..", "main", "resources");
const { LiteGraph } = require(path.join(resources, "webui", "lib", "litegraph-0.7.18.core.min.js"));
global.LiteGraph = LiteGraph;
const Nodes = require(path.join(resources, "webui", "js", "nodes.js"));
const NodeLibrary = require(path.join(resources, "webui", "js", "node-library.js"));
const schema = JSON.parse(fs.readFileSync(path.join(resources, "carpetlogic", "actions.json"), "utf8"));

Nodes.register(schema);
const entries = NodeLibrary.index(LiteGraph.registered_node_types, Nodes.CATEGORIES);
const titles = (found) => found.map(entry => entry.title);

test("every node the editor has can be found in the library", () => {
    const listed = entries.map(entry => entry.type).sort();
    assert.deepEqual(listed, Object.keys(LiteGraph.registered_node_types).sort());
    assert.ok(listed.includes("Control/Start"), "Start too: a program that lost it needs it back");
    for (const entry of entries) {
        assert.ok(entry.title, entry.type + " has a title");
        assert.ok(entry.desc, entry.type + " says what it does");
    }
});

test("the library lists its nodes category by category, in the order of the categories", () => {
    const order = [];
    for (const entry of entries) {
        if (order[order.length - 1] !== entry.category) order.push(entry.category);
    }
    assert.deepEqual(order, Object.keys(Nodes.CATEGORIES), "no category is split up or left out");
    assert.equal(order[0], "Control", "what a program is built from comes first");
});

test("an empty search keeps every node", () => {
    assert.deepEqual(NodeLibrary.search(entries, ""), entries);
    assert.deepEqual(NodeLibrary.search(entries, "   "), entries);
    assert.notEqual(NodeLibrary.search(entries, ""), entries, "as a list of its own");
});

test("a search puts the node whose name starts with the word first", () => {
    assert.equal(titles(NodeLibrary.search(entries, "att"))[0], "Attack");
    assert.deepEqual(titles(NodeLibrary.search(entries, "att")).slice(0, 2), ["Attack", "Critical Attack"]);
    assert.equal(titles(NodeLibrary.search(entries, "FOREVER"))[0], "Forever", "in any case");
});

test("a search finds a node by what it does, after the ones named so", () => {
    const found = titles(NodeLibrary.search(entries, "health"));
    assert.equal(found[0], "Health Check");
    assert.ok(found.includes("Target Health Check"));
    // Fight says nothing of health in its name: it ends when the target or the bot is gone.
    const waiting = titles(NodeLibrary.search(entries, "ticks"));
    assert.ok(waiting.includes("Delay"), "Delay waits for a number of ticks");
    assert.ok(waiting.length > 5);
});

test("a search finds every node of a category by the category's name", () => {
    const found = NodeLibrary.search(entries, "elytra");
    const elytra = entries.filter(entry => entry.category === "Elytra");
    for (const entry of elytra) assert.ok(found.includes(entry), entry.title);
});

test("every word of a search has to fit", () => {
    assert.deepEqual(titles(NodeLibrary.search(entries, "look player")), ["Look At Player"]);
    assert.deepEqual(NodeLibrary.search(entries, "look zzz"), []);
    assert.deepEqual(NodeLibrary.search(entries, "no such node anywhere"), []);
});

// ── The colours of the categories ──

function luminance(hex) {
    const channel = (offset) => {
        const value = parseInt(hex.slice(offset, offset + 2), 16) / 255;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    };
    return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5);
}

function contrast(a, b) {
    const [lighter, darker] = [luminance(a), luminance(b)].sort((x, y) => y - x);
    return (lighter + 0.05) / (darker + 0.05);
}

test("a node's title can be read on its category's colour", () => {
    for (const [name, category] of Object.entries(Nodes.CATEGORIES)) {
        assert.ok(contrast(category.color, category.ink) >= 4.5,
            name + ": " + category.ink + " on " + category.color + " is " + contrast(category.color, category.ink).toFixed(2) + ":1");
    }
});

test("no two categories share a colour", () => {
    const colors = Object.values(Nodes.CATEGORIES).map(category => category.color);
    assert.equal(new Set(colors).size, colors.length);
});

test("a node is made in its category's colour, with the ink that reads on it", () => {
    const node = LiteGraph.createNode("Combat/Attack");
    assert.equal(node.color, Nodes.CATEGORIES.Combat.color);
    assert.equal(node.constructor.title_text_color, Nodes.CATEGORIES.Combat.ink);
    // One that was saved in another dress gets today's when it is loaded.
    node.color = "#123456";
    node.configure(node.serialize());
    assert.equal(node.color, Nodes.CATEGORIES.Combat.color);
});

test("the library marks the nodes the budget cannot interrupt, and no others", () => {
    const marked = entries.filter(entry => entry.unbudgeted).map(entry => entry.type).sort();
    assert.deepEqual(marked, ["Conditions/Scarpet", "Scarpet/Run"]);
});
