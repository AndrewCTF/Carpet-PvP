// Tests of the web editor's live bot panel: what it renders, and what it does with a server event.
// Run with: node --test src/test/js   (Gradle's check does, when node is installed)
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

// ── A DOM small enough to read, and strict enough to catch HTML ──
// The panel puts everything the server sends into the page through textContent. Assigning innerHTML throws
// here, so a card that rendered a name as markup could not pass.
class Element {
    constructor(tag) {
        this.tagName = String(tag).toUpperCase();
        this.children = [];
        this.style = {};
        this.dataset = {};
        this.className = "";
        this._text = "";
        this._listeners = {};
        this.classList = {
            add: (name) => { if (!this.classList.contains(name)) this.className = (this.className + " " + name).trim(); },
            remove: (name) => { this.className = this.className.split(/\s+/).filter(c => c && c !== name).join(" "); },
            contains: (name) => this.className.split(/\s+/).includes(name),
            toggle: (name, on) => on ? this.classList.add(name) : this.classList.remove(name)
        };
    }
    set textContent(value) { this._text = String(value); this.children = []; }
    get textContent() { return this.children.length ? this.children.map(c => c.textContent).join(" ") : this._text; }
    set innerHTML(value) { throw new Error("the panel must never write HTML: " + value); }
    get innerHTML() { throw new Error("the panel must never read HTML"); }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; this._text = ""; }
    addEventListener(type, callback) { (this._listeners[type] = this._listeners[type] || []).push(callback); }
    click() { for (const callback of this._listeners.click || []) callback({ stopPropagation() {} }); }
    querySelectorAll(tag) {
        const wanted = String(tag).toUpperCase();
        const found = [];
        (function walk(node) {
            if (node.tagName === wanted) found.push(node);
            for (const child of node.children || []) walk(child);
        })(this);
        return found;
    }
    querySelector(tag) { return this.querySelectorAll(tag)[0] || null; }
    byClass(name) {
        const found = [];
        (function walk(node) {
            if (node.classList && node.classList.contains(name)) found.push(node);
            for (const child of node.children || []) walk(child);
        })(this);
        return found;
    }
}

const ids = new Map();
global.document = {
    createElement: (tag) => new Element(tag),
    getElementById: (id) => {
        if (!ids.has(id)) {
            const element = new Element("div");
            element.id = id;
            ids.set(id, element);
        }
        return ids.get(id);
    },
    querySelectorAll: () => []
};
global.Option = class Option {
    constructor(label, value) { this.label = label; this.value = value; this.textContent = label; }
};
// The helpers app.js defines for the page.
global.el = (tag, className, text) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined && text !== null) element.textContent = String(text);
    return element;
};
const printed = [];
global.log = (message, level) => printed.push([level || "info", message]);
global.calls = [];
global.API = {
    on() {},
    hasToken: () => false,
    stopProgram: (name) => { global.calls.push(["stopProgram", name]); return Promise.resolve({ success: true }); },
    setBotConfig: (name, key, value) => { global.calls.push(["setBotConfig", name, key, value]); return Promise.resolve({ success: true }); },
    tpBot: (name) => { global.calls.push(["tpBot", name]); return Promise.resolve({ success: true }); },
    removeBot: (name) => { global.calls.push(["removeBot", name]); return Promise.resolve({ success: true }); }
};
global.ProgramPanel = { runProgram: () => global.calls.push(["runProgram"]) };

const BotPanel = require(path.join(__dirname, "..", "..", "main", "resources", "webui", "js", "bot-panel.js"));

function bot(overrides) {
    return Object.assign({
        name: "PvPBot",
        dimension: "minecraft:overworld",
        x: 1, y: -60, z: 2, yaw: 0, pitch: 0,
        health: 20, maxHealth: 20, absorption: 0, foodLevel: 20, armor: 8,
        alive: true, gamemode: "survival", sprinting: false, sneaking: false,
        equipment: { mainhand: "minecraft:diamond_sword", offhand: "empty", head: "empty" },
        pvp: { combat: true, style: "MELEE" },
        target: "Target", program: null
    }, overrides);
}

function send(message) {
    BotPanel.applyEvent(BotPanel.state, message);
    BotPanel.render();
}

// A panel update as the server sends it four times a second. Anything in it can be replaced per test.
function update(overrides) {
    send(Object.assign({
        type: "botUpdate",
        bots: { PvPBot: bot(overrides) },
        programs: {},
        combatSettings: ["combat", "combatstyle"],
        viewerMode: false
    }, overrides));
}

function cards() { return document.getElementById("bot-list").children; }
function card() { return cards()[0]; }
function buttonLabelled(label) { return card().querySelectorAll("button").find(b => b.textContent.startsWith(label)); }
function fill() { return card().byClass("hp-fill")[0]; }

// ── Rendering ──

test("the panel starts up", () => {
    BotPanel.init();
    assert.match(document.getElementById("bot-list").textContent, /No bots on the server/);
});

test("a card shows the bot as the server describes it", () => {
    update({ health: 12, foodLevel: 15, armor: 4, target: "Steve", program: { name: "Duel", status: "RUNNING", running: true } });
    const text = document.getElementById("bot-list").textContent;

    assert.match(text, /PvPBot/, "the name is on the card");
    assert.match(text, /Running/, "what its program is doing");
    assert.match(text, /12 \/ 20/, "the health it has left");
    assert.match(text, /15 · armor 4/, "what it has eaten and what it wears");
    assert.match(text, /Steve/, "who it is fighting");
    assert.match(text, /MELEE/, "its combat style");
    assert.match(text, /minecraft:diamond_sword/, "what it holds");
});

test("a name that is markup is rendered as text, never as HTML", () => {
    const nasty = "<script>alert('x')</script>";
    send({ type: "botUpdate", bots: { [nasty]: bot({ name: nasty }) }, programs: {} });

    assert.equal(card().textContent.includes(nasty), true, "the name is in the page as text");
    assert.equal(card().querySelectorAll("script").length, 0, "no script element was created");
});

test("a health bar is a width that stays between both of its ends", () => {
    update({ health: 20, maxHealth: 20 });
    assert.equal(fill().style.width, "100.0%");
    assert.equal(fill().classList.contains("low"), false);

    update({ health: 20, maxHealth: 40 });
    assert.equal(fill().style.width, "50.0%");
    assert.equal(fill().classList.contains("low"), false);

    update({ health: 5, maxHealth: 20 });
    assert.equal(fill().style.width, "25.0%");
    assert.equal(fill().classList.contains("low"), true);
});

test("a bot that is gone leaves the panel", () => {
    update({});
    assert.equal(cards().length, 1);

    send({ type: "botUpdate", bots: {}, programs: {} });
    assert.match(document.getElementById("bot-list").textContent, /No bots on the server/);
});

test("the finished fights are listed as text", () => {
    send({
        type: "matchUpdate",
        matches: [{ attacker: "A", defender: "B", winner: "A", ticks: 240, attackerDamage: 30.5, defenderDamage: 4 }]
    });

    const text = document.getElementById("match-list").textContent;
    assert.match(text, /A vs B/);
    assert.match(text, /A won/);
    assert.match(text, /240 ticks/);
    assert.match(text, /30.5 \/ 4 damage/);
});

// ── Events ──

test("a bot update replaces the whole panel state", () => {
    BotPanel.state.bots = { Gone: bot({ name: "Gone" }) };
    BotPanel.state.targetBot = "Gone";

    send({ type: "botUpdate", bots: { Here: bot({ name: "Here" }), Other: bot({ name: "Other" }) }, programs: {} });

    assert.deepEqual(Object.keys(BotPanel.state.bots), ["Here", "Other"]);
    assert.equal(BotPanel.state.targetBot, "", "a bot that is gone is not run on any more");
    assert.equal(cards().length, 2);
    assert.match(cards()[0].textContent, /Here/);
    assert.match(cards()[1].textContent, /Other/);
});

test("a program that starts is shown on the card of the bot it runs on", () => {
    update({ program: null });
    assert.match(card().textContent, /Program —/);
    assert.equal(buttonLabelled("▶").textContent, "▶ Run");

    update({ program: { name: "Duel", status: "RUNNING", action: "ATTACK", running: true } });
    assert.match(card().textContent, /Duel \(running\)/);
    assert.match(card().textContent, /ATTACK/);
    assert.equal(buttonLabelled("■").textContent, "■ Stop");
});

test("a program that failed is shown as an error", () => {
    update({ program: { name: "Duel", status: "ERROR", error: "no such block", running: false } });
    assert.match(card().textContent, /Error/);
    assert.match(card().textContent, /no such block/);
});

test("the only bot on the server is the one the editor runs on", () => {
    send({ type: "botUpdate", bots: { Only: bot({ name: "Only" }) }, programs: {} });

    assert.equal(BotPanel.getTargetBot(), "Only");
    assert.equal(document.getElementById("target-bot-select").value, "Only");
    assert.match(document.getElementById("run-target").textContent, /Only/);
});

test("the combat settings the panel offers are the ones the server sent", () => {
    update({});
    assert.deepEqual(card().querySelectorAll("select")[0].children.map(o => o.value), ["combat", "combatstyle"]);
});

test("losing the connection is shown on the panel", () => {
    // The wording of the notice is in the page; the panel only shows and hides it.
    document.getElementById("panel-stale").textContent = "Not connected";

    send({ type: "connectionChange", connected: true });
    assert.equal(document.getElementById("panel-stale").classList.contains("hidden"), true);

    send({ type: "connectionChange", connected: false });
    assert.equal(document.getElementById("panel-stale").classList.contains("hidden"), false);
    assert.match(document.getElementById("panel-stale").textContent, /Not connected/);
});

// ── Actions ──

test("clicking a card makes it the bot the editor runs on", () => {
    send({ type: "botUpdate", bots: { One: bot({ name: "One" }), Two: bot({ name: "Two" }) }, programs: {} });
    assert.equal(cards().length, 2);

    cards()[1].click();
    assert.equal(BotPanel.getTargetBot(), "Two");
    assert.equal(document.getElementById("bot-name-input").value, "Two");
    assert.equal(cards()[1].classList.contains("selected"), true);
    assert.equal(cards()[0].classList.contains("selected"), false);
});

test("turning the combat AI off is the setting the command takes", async () => {
    global.calls = [];
    update({ pvp: { combat: true, style: "MELEE" } });
    assert.equal(buttonLabelled("Combat").textContent, "Combat: on");

    buttonLabelled("Combat").click();
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(global.calls, [["setBotConfig", "PvPBot", "combat", "false"]]);
});

test("stopping the program of a card stops that bot's", async () => {
    global.calls = [];
    update({ program: { name: "Duel", status: "RUNNING", running: true } });

    buttonLabelled("■ Stop").click();
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(global.calls, [["stopProgram", "PvPBot"]]);
    assert.equal(BotPanel.getTargetBot(), "PvPBot", "the card that was stopped is the one the editor runs on");
});

test("one setting of the card is set by its key and value", async () => {
    global.calls = [];
    update({});
    const form = card().byClass("bot-card-setting")[0];
    form.querySelectorAll("select")[0].value = "combatstyle";
    form.querySelectorAll("input")[0].value = " crystal ";
    form.querySelectorAll("button")[0].click();

    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(global.calls, [["setBotConfig", "PvPBot", "combatstyle", "crystal"]]);
});

test("a setting without a value is not sent at all", async () => {
    global.calls = [];
    update({});
    // A card keeps what has been typed into it between updates, so the field has to be emptied here.
    const form = card().byClass("bot-card-setting")[0];
    form.querySelectorAll("input")[0].value = "";
    form.querySelectorAll("button")[0].click();

    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(global.calls, []);
    assert.ok(printed.some(([, message]) => /needs a value/.test(message)), "and says why");
});

test("a viewer mode panel changes nothing", async () => {
    global.calls = [];
    update({ viewerMode: true });
    buttonLabelled("Bring me").click();
    buttonLabelled("Remove").click();

    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(global.calls, [], "nothing was sent to the server");
    assert.ok(printed.some(([, message]) => /viewer mode/.test(message)), "and says why");
});

test("a card can bring its bot to the player and remove it", async () => {
    global.calls = [];
    update({});

    buttonLabelled("Bring me").click();
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(global.calls, [["tpBot", "PvPBot"]]);

    global.calls = [];
    buttonLabelled("Remove").click();
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(global.calls, [["removeBot", "PvPBot"]]);
});