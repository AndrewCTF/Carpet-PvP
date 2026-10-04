// Tests of the web editor's autosave: when it saves, what it does when a save fails, and what it keeps in the
// browser for a program the server does not have yet.
// Run with: node --test src/test/js   (Gradle's check does, when node is installed)
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const Autosave = require(path.join(__dirname, "..", "..", "main", "resources", "webui", "js", "autosave.js"));

// A clock the test moves, a server that answers what the test tells it to, and the autosave between them.
function setup(answers) {
    const world = { now: 0, timers: [], saves: 0, notified: 0, failures: [], answers: answers || [] };
    world.advance = async (millis) => {
        const until = world.now + millis;
        for (;;) {
            const due = world.timers.filter(timer => timer.at <= until).sort((a, b) => a.at - b.at)[0];
            if (!due) break;
            world.timers = world.timers.filter(timer => timer !== due);
            world.now = due.at;
            due.callback();
            await settle();
        }
        world.now = until;
    };
    world.autosave = Autosave.create({
        save: () => {
            world.saves++;
            const answer = world.answers.length ? world.answers.shift() : { ok: true };
            return answer instanceof Error ? Promise.reject(answer) : Promise.resolve(answer);
        },
        setTimer: (callback, millis) => {
            const timer = { callback, at: world.now + millis };
            world.timers.push(timer);
            return timer;
        },
        clearTimer: (timer) => { world.timers = world.timers.filter(other => other !== timer); },
        changed: () => { world.notified++; },
        failed: (reason) => { world.failures.push(reason); }
    });
    return world;
}
const settle = async () => { for (let i = 0; i < 5; i++) await new Promise(resolve => setImmediate(resolve)); };

function storage() {
    const items = new Map();
    return { getItem: (key) => items.has(key) ? items.get(key) : null, setItem: (key, value) => items.set(key, String(value)), items };
}

// ── When it saves ──

test("a change is saved once the program has been left alone for a moment", async () => {
    const world = setup();
    world.autosave.changed();
    assert.equal(world.autosave.state.dirty, true);

    await world.advance(Autosave.QUIET_MILLIS - 1);
    assert.equal(world.saves, 0, "not while somebody may still be working on it");
    await world.advance(1);
    assert.equal(world.saves, 1);
    assert.equal(world.autosave.state.dirty, false);
    assert.equal(world.autosave.state.failed, null);
});

test("changes that follow each other closely are saved once, after the last of them", async () => {
    const world = setup();
    for (let i = 0; i < 20; i++) {
        world.autosave.changed();
        await world.advance(500);
    }
    assert.equal(world.saves, 0, "ten seconds of steady editing have not sent a single save");

    await world.advance(Autosave.QUIET_MILLIS);
    assert.equal(world.saves, 1);
    await world.advance(60000);
    assert.equal(world.saves, 1, "and nothing is saved again while nothing changes");
});

test("a change that arrives while a save is under way is saved after it", async () => {
    const world = setup();
    let release;
    world.answers.push(null);
    world.autosave = Autosave.create({
        save: () => { world.saves++; return world.saves === 1 ? new Promise(resolve => { release = resolve; }) : Promise.resolve({ ok: true }); },
        setTimer: (callback, millis) => { const timer = { callback, at: world.now + millis }; world.timers.push(timer); return timer; },
        clearTimer: (timer) => { world.timers = world.timers.filter(other => other !== timer); },
        changed: () => {}, failed: () => {}
    });
    world.autosave.changed();
    await world.advance(Autosave.QUIET_MILLIS);
    assert.equal(world.saves, 1);
    assert.ok(world.autosave.state.saving);

    world.autosave.changed();
    await world.advance(Autosave.QUIET_MILLIS * 3);
    assert.equal(world.saves, 1, "two saves never run at once");
    release({ ok: true });
    await settle();
    assert.equal(world.autosave.state.dirty, true, "what changed during the save is still to be saved");
    await world.advance(Autosave.QUIET_MILLIS);
    assert.equal(world.saves, 2);
    assert.equal(world.autosave.state.dirty, false);
});

test("flush saves at once, and does nothing when there is nothing to save", async () => {
    const world = setup();
    assert.deepEqual(await world.autosave.flush(), { ok: true });
    assert.equal(world.saves, 0);

    world.autosave.changed();
    await world.autosave.flush();
    assert.equal(world.saves, 1, "before a run and when the page is left, the wait is skipped");
    await world.advance(Autosave.QUIET_MILLIS * 2);
    assert.equal(world.saves, 1, "and the save that was waiting does not happen a second time");
});

// ── When a save fails ──

test("a save that fails is tried again, a little later every time", async () => {
    const world = setup([{ ok: false, reason: "disk full" }, { ok: false, reason: "disk full" }, { ok: false, reason: "disk full" }, { ok: true }]);
    world.autosave.changed();
    await world.advance(Autosave.QUIET_MILLIS);
    assert.equal(world.autosave.state.failed, "disk full");
    assert.equal(world.autosave.state.dirty, true, "nothing is dropped");

    await world.advance(Autosave.FIRST_RETRY_MILLIS - 1);
    assert.equal(world.saves, 1);
    await world.advance(1);
    assert.equal(world.saves, 2);
    await world.advance(Autosave.FIRST_RETRY_MILLIS * 2 - 1);
    assert.equal(world.saves, 2, "the second wait is twice the first");
    await world.advance(1);
    assert.equal(world.saves, 3);
    await world.advance(Autosave.FIRST_RETRY_MILLIS * 4);
    assert.equal(world.saves, 4);
    assert.equal(world.autosave.state.failed, null, "and the try that goes through ends it");
    assert.equal(world.autosave.state.dirty, false);
    assert.deepEqual(world.failures, ["disk full"], "the failure is reported once, not on every retry");
});

test("the wait between retries stops growing at a minute", async () => {
    const world = setup(Array.from({ length: 40 }, () => ({ ok: false, reason: "no" })));
    world.autosave.changed();
    await world.advance(Autosave.QUIET_MILLIS);
    await world.advance(10 * 60000);
    const before = world.saves;
    await world.advance(Autosave.LONGEST_RETRY_MILLIS);
    assert.equal(world.saves, before + 1, "one try a minute from then on");
});

test("a server that cannot be reached is told apart, and the work is kept until it is back", async () => {
    const world = setup([new Error("Failed to fetch"), { ok: false, reason: "the server cannot be reached", offline: true }, { ok: true }]);
    world.autosave.changed();
    await world.advance(Autosave.QUIET_MILLIS);
    assert.equal(world.autosave.state.offline, true, "a save that never got an answer");
    assert.equal(world.autosave.state.failed, "Failed to fetch");
    assert.equal(world.autosave.state.dirty, true);

    await world.advance(Autosave.FIRST_RETRY_MILLIS);
    assert.equal(world.autosave.state.offline, true);
    await world.advance(Autosave.FIRST_RETRY_MILLIS * 2);
    assert.equal(world.autosave.state.offline, false);
    assert.equal(world.autosave.state.dirty, false, "saved as soon as the server answers again");
});

test("a program somebody else saved is not tried again until that is settled", async () => {
    const world = setup([{ ok: false, reason: "saved somewhere else", conflict: true }, { ok: true }]);
    world.autosave.changed();
    await world.advance(Autosave.QUIET_MILLIS);
    assert.equal(world.autosave.state.conflict, true);

    world.autosave.changed();
    await world.advance(10 * 60000);
    assert.equal(world.saves, 1, "it does not save over the other copy by itself, however long it waits");
    assert.deepEqual(await world.autosave.flush(), { ok: false });

    await world.autosave.saveNow();
    assert.equal(world.saves, 2, "only when its owner says which copy stays");
    assert.equal(world.autosave.state.conflict, false);
    assert.equal(world.autosave.state.dirty, false);
});

// ── The switch ──

test("switched off, nothing is saved until somebody asks", async () => {
    const world = setup();
    world.autosave.setEnabled(false);
    world.autosave.changed();
    await world.advance(10 * 60000);
    assert.equal(world.saves, 0);
    assert.equal(world.autosave.state.dirty, true);
    await world.autosave.flush();
    assert.equal(world.saves, 0, "not before a run and not on leaving either");

    await world.autosave.saveNow();
    assert.equal(world.saves, 1, "the Save button still saves");
    assert.equal(world.autosave.state.dirty, false);

    world.autosave.changed();
    world.autosave.setEnabled(true);
    await world.advance(Autosave.QUIET_MILLIS);
    assert.equal(world.saves, 2, "switched on again, what was waiting is saved");
});

test("switching it off stops the retries of a save that failed", async () => {
    const world = setup([{ ok: false, reason: "no" }]);
    world.autosave.changed();
    await world.advance(Autosave.QUIET_MILLIS);
    world.autosave.setEnabled(false);
    await world.advance(10 * 60000);
    assert.equal(world.saves, 1);
});

test("the switch is remembered in the browser, and is on until somebody turns it off", () => {
    const browser = storage();
    assert.equal(Autosave.readEnabled(browser), true);
    Autosave.writeEnabled(browser, false);
    assert.equal(Autosave.readEnabled(browser), false);
    Autosave.writeEnabled(browser, true);
    assert.equal(Autosave.readEnabled(browser), true);
    assert.equal(Autosave.readEnabled({ getItem() { throw new Error("no storage"); } }), true);
});

test("a page that may not write saves nothing by itself", async () => {
    const world = setup();
    world.autosave.setWritable(false);
    world.autosave.changed();
    await world.advance(10 * 60000);
    assert.equal(world.saves, 0, "in viewer mode the server would refuse every one of them");
    assert.equal(world.autosave.state.dirty, true);

    world.autosave.setWritable(true);
    await world.advance(Autosave.QUIET_MILLIS);
    assert.equal(world.saves, 1);
});

test("a program that was just opened has nothing to save", async () => {
    const world = setup([{ ok: false, reason: "no" }]);
    world.autosave.changed();
    await world.advance(Autosave.QUIET_MILLIS);
    world.autosave.settled();
    assert.equal(world.autosave.state.dirty, false);
    assert.equal(world.autosave.state.failed, null);
    await world.advance(10 * 60000);
    assert.equal(world.saves, 1, "and the retry that was waiting is gone with the old program");
});

// ── What is kept in the browser ──

test("unsaved work is kept in the browser and offered back newest first", () => {
    const browser = storage();
    assert.deepEqual(Autosave.kept(browser), []);
    assert.equal(Autosave.keep(browser, { key: "p1", name: "Walk", graph: { nodes: [1] }, baseUpdatedAt: 7, at: 100 }), true);
    assert.equal(Autosave.keep(browser, { key: "new", name: "Untitled", graph: { nodes: [2] }, at: 300 }), true);
    assert.equal(Autosave.keep(browser, { key: "p1", name: "Walk", graph: { nodes: [1, 2] }, baseUpdatedAt: 7, at: 200 }), true);

    const kept = Autosave.kept(browser);
    assert.deepEqual(kept.map(copy => copy.key), ["new", "p1"]);
    assert.deepEqual(kept[1].graph, { nodes: [1, 2] }, "one copy per program, the latest");
    assert.equal(kept[1].baseUpdatedAt, 7, "with the copy of the server's it was made from");

    Autosave.forget(browser, "new");
    assert.deepEqual(Autosave.kept(browser).map(copy => copy.key), ["p1"], "what the server has is forgotten");
    Autosave.forget(browser, "never kept");
});

test("the browser holds a handful of copies, not all there ever were", () => {
    const browser = storage();
    for (let i = 0; i < Autosave.MAX_COPIES + 5; i++) Autosave.keep(browser, { key: "p" + i, name: "n", graph: {}, at: i });
    const kept = Autosave.kept(browser);
    assert.equal(kept.length, Autosave.MAX_COPIES);
    assert.equal(kept[0].key, "p" + (Autosave.MAX_COPIES + 4), "the newest stay");
});

test("a browser that cannot store says so instead of pretending", () => {
    const full = { getItem: () => null, setItem() { throw new Error("QuotaExceededError"); } };
    assert.equal(Autosave.keep(full, { key: "p1", name: "Walk", graph: {}, at: 1 }), false);
    const none = { getItem() { throw new Error("SecurityError"); }, setItem() { throw new Error("SecurityError"); } };
    assert.deepEqual(Autosave.kept(none), []);
    assert.equal(Autosave.keep(none, { key: "p1", name: "Walk", graph: {}, at: 1 }), false);
    const garbled = storage();
    garbled.setItem("carpetlogic.unsaved", "{ not json");
    assert.deepEqual(Autosave.kept(garbled), []);
});
