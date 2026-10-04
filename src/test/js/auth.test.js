// Tests of the screen a page without a session shows: what it decides and says, and what its two forms do
// with the server's answers.
// Run with: node --test src/test/js   (Gradle's check does, when node is installed)
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.join(__dirname, "..", "..", "main");

// ── A page small enough to read ──
class Element {
    constructor(id) {
        this.id = id;
        this.value = "";
        this.textContent = "";
        this.disabled = false;
        this.dataset = {};
        this.listeners = {};
        const classes = new Set();
        this.classList = {
            add: (name) => classes.add(name),
            remove: (name) => classes.delete(name),
            contains: (name) => classes.has(name),
            toggle: (name, on) => (on === undefined ? !classes.has(name) : on) ? classes.add(name) : classes.delete(name)
        };
    }
    addEventListener(type, callback) { (this.listeners[type] = this.listeners[type] || []).push(callback); }
    fire(type) {
        const event = { prevented: false, preventDefault() { this.prevented = true; } };
        for (const callback of this.listeners[type] || []) callback(event);
        return event;
    }
    focus() { focused = this.id; }
}

let focused = null;
let elements = new Map();
const $ = (id) => {
    if (!elements.has(id)) elements.set(id, new Element(id));
    return elements.get(id);
};
global.document = { getElementById: $, body: new Element("body") };
global.window = { location: { hash: "", pathname: "/", host: "play.example:9876", hostname: "play.example", protocol: "http:" } };
let addressBar = null;
global.history = { replaceState: (state, title, url) => { addressBar = url; } };

// The server, as far as this screen talks to it.
let server;
global.API = {
    offersSignIn: async () => { server.calls.push(["offersSignIn"]); if (server.down) throw new Error("down"); return server.offers; },
    signIn: async (name, password) => { server.calls.push(["signIn", name, password]); return server.signIn; },
    setPassword: async (ticket, name, password) => { server.calls.push(["setPassword", ticket, name, password]); return server.setPassword; }
};

const Auth = require(path.join(root, "resources", "webui", "js", "auth.js"));
const settle = async () => { for (let i = 0; i < 6; i++) await new Promise(resolve => setImmediate(resolve)); };
const shown = (view) => !$("auth-" + view).classList.contains("hidden");

// A page freshly loaded with the given fragment; entered counts how often it was let into the editor.
function page(hash) {
    elements = new Map();
    focused = null;
    addressBar = null;
    window.location.hash = hash || "";
    server = { calls: [], offers: true, down: false, signIn: { ok: true, status: 200, body: { token: "t" } },
        setPassword: { ok: true, status: 200, body: { success: true, name: "Steve" } } };
    $("auth-screen").classList.add("hidden");
    const state = { entered: 0 };
    Auth.init(() => state.entered++);
    return state;
}

// ── Deciding and wording ──

test("a set-password link is read from the fragment", () => {
    assert.deepEqual(Auth.parseSetup("#setup=AbC_12-x&name=Steve"), { ticket: "AbC_12-x", name: "Steve" });
    assert.deepEqual(Auth.parseSetup("#setup=t&name=.Bedrock%20Steve"), { ticket: "t", name: ".Bedrock Steve" }, "a name is decoded");
    assert.equal(Auth.parseSetup("#token=AbC"), null, "a link from /carpetlogic open is not one");
    assert.equal(Auth.parseSetup("#setup=AbC"), null, "it names the account it is for");
    assert.equal(Auth.parseSetup("#setup=t&name=%E0%A4%A"), null, "a name that cannot be decoded");
    assert.equal(Auth.parseSetup(""), null);
});

test("the screen shows the view the page's situation calls for", () => {
    assert.equal(Auth.viewFor({ setup: { ticket: "t", name: "Steve" }, offersSignIn: false }), "setup");
    assert.equal(Auth.viewFor({ setup: null, offersSignIn: true }), "signin");
    assert.equal(Auth.viewFor({ setup: null, offersSignIn: false }), "none", "how to get a link, where there is no sign-in");
});

test("a new password is checked before it is sent", () => {
    assert.match(Auth.passwordProblem("short", "short"), /at least 10 characters\. This one has 5/);
    assert.match(Auth.passwordProblem("x".repeat(9), "x".repeat(9)), /at least 10/);
    assert.match(Auth.passwordProblem("long enough here", "long enough her"), /not the same/);
    assert.match(Auth.passwordProblem("x".repeat(129), "x".repeat(129)), /at most 128/);
    assert.equal(Auth.passwordProblem("x".repeat(10), "x".repeat(10)), null);
    assert.equal(Auth.passwordProblem("x".repeat(128), "x".repeat(128)), null);
});

test("the page asks for the password length the server asks for", () => {
    const store = fs.readFileSync(path.join(root, "java", "carpet", "logic", "web", "PasswordStore.java"), "utf8");
    const shortest = Number(/MIN_LENGTH = (\d+)/.exec(store)[1]);
    const longest = Number(/MAX_LENGTH = (\d+)/.exec(store)[1]);
    assert.notEqual(Auth.passwordProblem("x".repeat(shortest - 1), "x".repeat(shortest - 1)), null);
    assert.equal(Auth.passwordProblem("x".repeat(shortest), "x".repeat(shortest)), null);
    assert.equal(Auth.passwordProblem("x".repeat(longest), "x".repeat(longest)), null);
    assert.notEqual(Auth.passwordProblem("x".repeat(longest + 1), "x".repeat(longest + 1)), null);
    const html = fs.readFileSync(path.join(root, "resources", "webui", "index.html"), "utf8");
    assert.ok(html.includes("At least " + shortest + " characters"), "and says so under the field");
});

test("a wait is put in words", () => {
    assert.equal(Auth.waitText(1), "Too many attempts. Try again in 1 second.");
    assert.equal(Auth.waitText(40), "Too many attempts. Try again in 40 seconds.");
    assert.equal(Auth.waitText(900), "Too many attempts. Try again in 15 minutes.");
});

test("a refused sign-in is explained by what the server answered", () => {
    assert.equal(Auth.signInProblem({ status: 401, body: { error: "Wrong name or password" } }), "Wrong name or password");
    assert.equal(Auth.signInProblem({ status: 429, body: { retryAfter: 10 } }), "Too many attempts. Try again in 10 seconds.");
    assert.match(Auth.signInProblem({ status: 401, body: { error: "Missing or expired token. Run /carpetlogic open in game for a link." } }),
        /turned off/, "the sign-in was turned off after the page was loaded");
    assert.match(Auth.signInProblem({ status: 500, body: { error: "Internal error" } }), /answered 500/);
});

test("a refused set-password link is explained by what the server answered", () => {
    assert.equal(Auth.setupProblem({ status: 403, body: { error: "This link is no longer valid." } }), "This link is no longer valid.");
    assert.equal(Auth.setupProblem({ status: 400, body: { error: "A password needs at least 10 characters" } }), "A password needs at least 10 characters");
    assert.match(Auth.setupProblem({ status: 401, body: { error: "Missing or expired token." } }), /turned off/);
    assert.match(Auth.setupProblem({ status: 429, body: { retryAfter: 300 } }), /5 minutes/);
});

test("a password typed over plain HTTP to another machine is warned about", () => {
    assert.equal(Auth.overPlainHttp({ protocol: "http:", hostname: "play.example" }), true);
    assert.equal(Auth.overPlainHttp({ protocol: "http:", hostname: "192.168.1.20" }), true);
    assert.equal(Auth.overPlainHttp({ protocol: "https:", hostname: "play.example" }), false);
    for (const local of ["localhost", "127.0.0.1", "[::1]"]) {
        assert.equal(Auth.overPlainHttp({ protocol: "http:", hostname: local }), false, local);
    }
});

// ── The screen ──

test("a page without a session asks the server whether it can offer the sign-in", async () => {
    page("");
    await Auth.open();
    assert.deepEqual(server.calls, [["offersSignIn"]]);
    assert.equal(shown("signin"), true);
    assert.equal(Auth.isOpen(), true);
    assert.equal(focused, "auth-name");
    assert.equal($("auth-wire").classList.contains("hidden"), false, "this page came over plain HTTP from another machine");

    page("");
    server.offers = false;
    await Auth.open();
    assert.equal(shown("none"), true, "without one it says how to get a link from the game");
    assert.equal(shown("signin"), false);
});

test("a server that does not answer is said to be away, and can be asked again", async () => {
    const state = page("");
    server.down = true;
    await Auth.open();
    assert.equal(shown("none"), true);
    assert.match($("auth-none-reason").textContent, /did not answer/);
    assert.equal($("auth-back").textContent, "Try again");
    assert.equal($("auth-back").classList.contains("hidden"), false);

    $("auth-back").fire("click");
    assert.equal(state.entered, 1);
});

test("the right name and password let the page in", async () => {
    const state = page("");
    await Auth.open({ offersSignIn: true });
    $("auth-name").value = "  Steve ";
    $("auth-password").value = "correct horse battery";

    const event = $("auth-signin").fire("submit");
    await settle();
    assert.equal(event.prevented, true, "the form is never sent as a form");
    assert.deepEqual(server.calls, [["signIn", "Steve", "correct horse battery"]]);
    assert.equal(state.entered, 1);
    assert.equal(Auth.isOpen(), false);
    assert.equal($("auth-password").value, "", "the password does not stay in the page");
});

test("a wrong password keeps the page out and says what the server said", async () => {
    const state = page("");
    await Auth.open({ offersSignIn: true });
    server.signIn = { ok: false, status: 401, body: { error: "Wrong name or password" } };
    $("auth-name").value = "Steve";
    $("auth-password").value = "not the password";

    $("auth-signin").fire("submit");
    await settle();
    assert.equal(state.entered, 0);
    assert.equal(Auth.isOpen(), true);
    assert.equal($("auth-error").textContent, "Wrong name or password");
    assert.equal($("auth-error").classList.contains("hidden"), false);
    assert.equal($("auth-password").value, "");
    assert.equal($("auth-submit").disabled, false, "and can be tried again");
});

test("nothing is sent while the name or the password is missing", async () => {
    page("");
    await Auth.open({ offersSignIn: true });
    $("auth-name").value = "Steve";

    $("auth-signin").fire("submit");
    await settle();
    assert.deepEqual(server.calls, []);
    assert.match($("auth-error").textContent, /name and your web password/);
});

test("being held up shuts the button for as long as the server said", async () => {
    page("");
    await Auth.open({ offersSignIn: true });
    server.signIn = { ok: false, status: 429, body: { error: "Too many attempts", retryAfter: 40 } };
    $("auth-name").value = "Steve";
    $("auth-password").value = "one guess too many";

    $("auth-signin").fire("submit");
    await settle();
    assert.equal($("auth-submit").disabled, true);
    assert.equal($("auth-submit").textContent, "Wait 40 s");
    assert.equal($("auth-error").textContent, "Too many attempts. Try again in 40 seconds.");
    Auth.close();
});

test("a set-password link opens its own view and leaves the address bar", async () => {
    page("#setup=TICKET&name=Steve");
    assert.equal(Auth.hasSetup(), true);
    assert.equal(addressBar, "/", "the ticket is taken out of the address bar");

    await Auth.open();
    assert.equal(shown("setup"), true);
    assert.equal($("auth-setup-name").textContent, "Steve");
    assert.deepEqual(server.calls, [], "the server is not asked anything until a password is sent");
});

test("a password that is too short or mistyped is not sent", async () => {
    page("#setup=TICKET&name=Steve");
    await Auth.open();
    $("auth-new").value = "short";
    $("auth-repeat").value = "short";
    $("auth-setup").fire("submit");
    await settle();
    assert.match($("auth-setup-error").textContent, /at least 10/);

    $("auth-new").value = "correct horse battery";
    $("auth-repeat").value = "correct horse batter";
    $("auth-setup").fire("submit");
    await settle();
    assert.match($("auth-setup-error").textContent, /not the same/);
    assert.deepEqual(server.calls, []);
    assert.equal(Auth.hasSetup(), true, "the link is still to be used");
});

test("setting a password signs its admin in with it", async () => {
    const state = page("#setup=TICKET&name=Steve");
    await Auth.open();
    $("auth-new").value = "correct horse battery";
    $("auth-repeat").value = "correct horse battery";

    $("auth-setup").fire("submit");
    await settle();
    assert.deepEqual(server.calls, [
        ["setPassword", "TICKET", "Steve", "correct horse battery"],
        ["signIn", "Steve", "correct horse battery"]]);
    assert.equal(state.entered, 1);
    assert.equal(Auth.isOpen(), false);
    assert.equal(Auth.hasSetup(), false, "the link has been used");
    assert.equal($("auth-new").value, "");
    assert.equal($("auth-repeat").value, "");
});

test("a link the server refuses says so and lets nobody in", async () => {
    const state = page("#setup=TICKET&name=Steve");
    await Auth.open();
    server.setPassword = { ok: false, status: 403, body: { error: "This link is no longer valid. Ask for a new one with /carpetlogic password" } };
    $("auth-new").value = "correct horse battery";
    $("auth-repeat").value = "correct horse battery";

    $("auth-setup").fire("submit");
    await settle();
    assert.deepEqual(server.calls, [["setPassword", "TICKET", "Steve", "correct horse battery"]]);
    assert.match($("auth-setup-error").textContent, /no longer valid/);
    assert.equal(state.entered, 0);
    assert.equal(Auth.isOpen(), true);
});
