// Tests of the web editor's Settings panel: how it groups and filters the rules the server lists, which
// control a rule gets, who may change one, and what a row says once the server has answered.
// Run with: node --test src/test/js   (Gradle's check does, when node is installed)
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.join(__dirname, "..", "..", "main");
const SettingsPanel = require(path.join(root, "resources", "webui", "js", "settings-panel.js"));

// Rules as GET /api/settings lists them, one of every kind.
function rule(overrides) {
    return Object.assign({
        name: "carpetLogicMaxPrograms", group: "editor", type: "int", value: "4", default: "4", strict: false,
        description: "Maximum number of bot programs running at the same time", options: [], extra: []
    }, overrides);
}

const rules = [
    rule({}),
    rule({ name: "carpetLogicViewerMode", type: "boolean", value: "false", default: "false", strict: true, options: ["true", "false"],
        description: "The CarpetLogic web editor can look at bots and programs but not change or run anything" }),
    rule({ name: "carpetLogicBindAddress", type: "string", value: "127.0.0.1", default: "127.0.0.1", options: ["127.0.0.1", "0.0.0.0"],
        description: "Address the CarpetLogic web editor listens on", extra: ["Applied when the server starts"] }),
    rule({ name: "swordBlockHitting", group: "actions", type: "boolean", value: "true", default: "false", strict: true,
        options: ["true", "false"], description: "Players can block with swords" }),
    rule({ name: "botCombatStyle", group: "bots", type: "string", value: "sword", default: "sword", strict: true,
        options: ["sword", "crystal", "mace"], description: "Default bot combat style" }),
    rule({ name: "botSkill", group: "bots", type: "number", value: "0.5", default: "0.5", options: ["0.0", "0.5", "1.0"],
        description: "Default bot skill from 0 (beginner) to 1 (expert)" }),
];
const names = (group) => group.rules.map(each => each.name);

test("the rules are shown in the panel's groups, in its order", () => {
    const groups = SettingsPanel.grouped(rules, "");
    assert.deepEqual(groups.map(group => group.id), ["editor", "actions", "bots"]);
    assert.deepEqual(groups.map(group => group.title), ["Web editor", "Program actions", "Bot defaults"]);
    assert.deepEqual(names(groups[0]), ["carpetLogicMaxPrograms", "carpetLogicViewerMode", "carpetLogicBindAddress"]);
    assert.deepEqual(names(groups[2]), ["botCombatStyle", "botSkill"]);
    assert.equal(groups.reduce((count, group) => count + group.rules.length, 0), rules.length, "no rule is left out");
});

test("the groups of the page are the groups the server puts its rules in", () => {
    const server = fs.readFileSync(path.join(root, "java", "carpet", "logic", "web", "CarpetAdminRules.java"), "utf8");
    const declared = /GROUPS = List\.of\(([^)]*)\)/.exec(server)[1].split(",").map(id => id.trim().replace(/"/g, ""));
    assert.deepEqual(SettingsPanel.GROUPS.map(group => group.id), declared);
});

test("a search finds a rule by its name, by the words in its name and by what it does", () => {
    const found = (query) => SettingsPanel.grouped(rules, query).flatMap(names);
    assert.deepEqual(found("maxprog"), ["carpetLogicMaxPrograms"]);
    assert.deepEqual(found("max programs"), ["carpetLogicMaxPrograms"], "the words of a camel-case name");
    assert.deepEqual(found("VIEWER"), ["carpetLogicViewerMode"], "in any case");
    assert.deepEqual(found("block with swords"), ["swordBlockHitting"], "its description");
    assert.deepEqual(found("server starts"), ["carpetLogicBindAddress"], "its notes");
    assert.deepEqual(found("bot default"), ["botCombatStyle", "botSkill"]);
    assert.deepEqual(found("nothing like this"), []);
});

test("a search leaves out the groups it finds nothing in", () => {
    assert.deepEqual(SettingsPanel.grouped(rules, "bot skill").map(group => group.id), ["bots"]);
    assert.deepEqual(SettingsPanel.grouped(rules, "zzz"), []);
});

test("a rule is edited with the control its type calls for", () => {
    const control = (name) => SettingsPanel.control(rules.find(each => each.name === name));
    assert.equal(control("carpetLogicViewerMode"), "switch");
    assert.equal(control("botCombatStyle"), "select", "one of a fixed set");
    assert.equal(control("carpetLogicMaxPrograms"), "number");
    assert.equal(control("botSkill"), "number", "a number that only suggests values is still typed");
    assert.equal(control("carpetLogicBindAddress"), "text", "text that only suggests values is still typed");
});

test("a rule that is not at its default is marked", () => {
    assert.equal(SettingsPanel.isModified(rules[0]), false);
    assert.equal(SettingsPanel.isModified(rules[3]), true);
});

test("only an admin's session gets to change rules, and the panel says why", () => {
    const admin = SettingsPanel.access({ admin: true, adminLogin: true });
    assert.equal(admin.editable, true);
    assert.equal(admin.signIn, false);

    const visitor = SettingsPanel.access({ admin: false, adminLogin: true });
    assert.equal(visitor.editable, false);
    assert.equal(visitor.signIn, true, "the sign-in is offered where the server has it");
    assert.match(visitor.text, /Read only/);

    const closed = SettingsPanel.access({ admin: false, adminLogin: false });
    assert.equal(closed.editable, false);
    assert.equal(closed.signIn, false);
    assert.match(closed.text, /\/carpet <rule> <value>/, "and how to change a rule where it does not");
});

test("locked settings are read only for an admin too, with the server's reason", () => {
    const locked = SettingsPanel.access({ admin: true, adminLogin: true }, "Carpet's settings are locked in carpet.conf");
    assert.equal(locked.editable, false);
    assert.equal(locked.signIn, false);
    assert.match(locked.text, /locked in carpet\.conf/);
});

test("a row says that its change was saved, and what the rule was before", () => {
    const saved = SettingsPanel.outcome({ ok: true, status: 200, body: { success: true, rule: rule({ value: "9" }) } }, "4");
    assert.equal(saved.state, "saved");
    assert.match(saved.text, /Saved\. It was 4\./);

    const said = SettingsPanel.outcome({ ok: true, status: 200, body: { rule: rule({ value: "9" }), message: "Applied at the next start" } }, "4");
    assert.match(said.text, /Applied at the next start/, "with what the rule itself had to say");

    const same = SettingsPanel.outcome({ ok: true, status: 200, body: { rule: rule({ value: "4" }) } }, "4");
    assert.equal(same.state, "saved");
    assert.match(same.text, /Unchanged/);
});

test("a row says that its change was refused, with the server's reason", () => {
    const refused = SettingsPanel.outcome({ ok: false, status: 400, body: { error: "Wrong value for carpetLogicMaxPrograms: -1" } }, "4");
    assert.equal(refused.state, "refused");
    assert.equal(refused.text, "Not saved: Wrong value for carpetLogicMaxPrograms: -1");

    const locked = SettingsPanel.outcome({ ok: false, status: 409, body: { error: "Carpet's settings are locked in carpet.conf" } }, "4");
    assert.match(locked.text, /Not saved: Carpet's settings are locked/);

    const expired = SettingsPanel.outcome({ ok: false, status: 401, body: { error: "Missing or expired token" } }, "4");
    assert.equal(expired.state, "refused");
    assert.match(expired.text, /session has ended/);

    const silent = SettingsPanel.outcome({ ok: false, status: 502, body: {} }, "4");
    assert.match(silent.text, /the server answered 502/);
});
