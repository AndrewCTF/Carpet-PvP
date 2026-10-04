// Tests of what the web editor's toolbar says about the program on the canvas: saved or not, being saved,
// and why a save did not go through.
// Run with: node --test src/test/js   (Gradle's check does, when node is installed)
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const ProgramPanel = require(path.join(__dirname, "..", "..", "main", "resources", "webui", "js", "program-panel.js"));

const time = (at) => "at " + at;
// A program as the toolbar sees it. Anything in it can be replaced per test.
function status(overrides) {
    return ProgramPanel.saveStatus(Object.assign({
        saved: false, changed: false, hasWork: false, saving: false, failed: null, offline: false, conflict: false,
        readOnly: false, draft: null, file: null, savedAt: null, autosave: false
    }, overrides), time);
}

test("a canvas with nothing to lose is quietly not saved", () => {
    assert.deepEqual([status({}).text, status({}).tone], ["Not saved", "quiet"]);
    // Only Start, under another name: still nothing to lose.
    assert.equal(status({ changed: true }).tone, "quiet");
});

test("work that the server has never had is warned about", () => {
    const unsaved = status({ changed: true, hasWork: true });
    assert.equal(unsaved.text, "Not saved");
    assert.equal(unsaved.tone, "warn");
    assert.match(unsaved.title, /Ctrl\+S/, "and says how to keep it");
});

test("a program the server has says when it got it", () => {
    const saved = status({ saved: true, savedAt: 1200 });
    assert.equal(saved.text, "Saved at 1200");
    assert.equal(saved.tone, "ok");
    assert.equal(status({ saved: true }).text, "Saved", "or just that it has it, when the moment is not known");
});

test("a saved program that was changed says so", () => {
    const changed = status({ saved: true, savedAt: 1200, changed: true });
    assert.equal(changed.text, "Unsaved changes");
    assert.equal(changed.tone, "warn");
    // Clearing the canvas of a saved program is a change too.
    assert.equal(status({ saved: true, changed: true, hasWork: false }).text, "Unsaved changes");
});

test("a save that is under way is shown before anything else", () => {
    const saving = status({ saved: true, changed: true, hasWork: true, saving: true, failed: "an earlier reason" });
    assert.equal(saving.text, "Saving");
});

test("a save that did not go through says why, until one does", () => {
    const failed = status({ saved: true, savedAt: 1200, changed: true, hasWork: true, failed: "The If / Else node has no condition connected" });
    assert.equal(failed.text, "Save failed");
    assert.equal(failed.tone, "bad");
    assert.match(failed.title, /no condition connected/);
    assert.match(failed.title, /tries again/);

    assert.equal(status({ saved: true, savedAt: 1300 }).text, "Saved at 1300");
});

test("with autosave on, the toolbar says that the program is kept without being asked", () => {
    const kept = status({ saved: true, savedAt: 1200, autosave: true });
    assert.equal(kept.text, "Autosaved at 1200");
    assert.equal(kept.tone, "ok");

    const pending = status({ saved: true, changed: true, hasWork: true, autosave: true });
    assert.equal(pending.text, "Unsaved changes");
    assert.match(pending.title, /saved in a moment/);
    assert.doesNotMatch(status({ saved: true, changed: true, hasWork: true }).title, /in a moment/, "which it does not promise while autosave is off");
});

test("a saved program says where its file is", () => {
    const saved = status({ saved: true, savedAt: 1200, file: "world/carpetlogic/programs/W-Tap.json" });
    assert.match(saved.title, /world\/carpetlogic\/programs\/W-Tap\.json/);
    assert.match(status({ saved: true, savedAt: 1200, autosave: true, file: "world/carpetlogic/programs/W-Tap.json" }).title, /W-Tap\.json/);
});

test("a draft is saved, and says that it does not run yet and why", () => {
    const draft = status({ saved: true, savedAt: 1200, draft: "An If / Else node has no condition connected" });
    assert.equal(draft.text, "Saved at 1200, does not run yet");
    assert.equal(draft.tone, "warn");
    assert.match(draft.title, /no condition connected/);
});

test("work the server could not be reached for is said to be kept in the browser", () => {
    const kept = status({ saved: true, changed: true, hasWork: true, failed: "the server cannot be reached", offline: true, autosave: true });
    assert.equal(kept.text, "Kept in this browser");
    assert.equal(kept.tone, "warn");
    assert.match(kept.title, /saved as soon as the server answers/);
});

test("a failed save says whether it is tried again by itself", () => {
    assert.match(status({ changed: true, hasWork: true, failed: "disk full", autosave: true }).title, /tried again by itself/);
    assert.match(status({ changed: true, hasWork: true, failed: "disk full" }).title, /Save tries again/);
});

test("a program somebody else saved says so before anything else but a save under way", () => {
    const conflict = status({ saved: true, changed: true, hasWork: true, failed: "saved somewhere else", conflict: true, autosave: true });
    assert.equal(conflict.text, "Changed elsewhere");
    assert.equal(conflict.tone, "bad");
    assert.match(conflict.title, /which copy to keep/);
});

test("in viewer mode the toolbar says that nothing is saved on the server", () => {
    const viewer = status({ changed: true, hasWork: true, readOnly: true, autosave: true });
    assert.equal(viewer.text, "Viewer mode: not saved");
    assert.match(viewer.title, /kept in this browser/);
    assert.equal(status({ saved: true, savedAt: 1200, readOnly: true }).text, "Saved at 1200", "what was saved before still is");
});

test("the folders of the saved programs are listed with the programs folder first", () => {
    const programs = [{ folder: "Drills" }, { folder: "" }, { folder: "Arena" }, { folder: "Drills" }, {}];
    assert.deepEqual(ProgramPanel.folders(programs), ["", "Arena", "Drills"]);
    assert.deepEqual(ProgramPanel.folders([]), []);
});

test("work the server turns out to have is not offered back", () => {
    const graph = { nodes: [1, 2] };
    const copies = [
        { key: "p1", id: "p1", name: "Walk", graph: { nodes: [1, 2], group: null } },
        { key: "p2", id: "p2", name: "Duel", graph: { nodes: [9] } },
        { key: "p3", id: "p3", name: "Gone", graph: {} },
        { key: "new-abc", id: null, name: "Untitled", graph: {} },
        { key: "open", id: "open", name: "Open now", graph: {} }
    ];
    const programs = [{ id: "p1", name: "Walk", graphData: graph }, { id: "p2", name: "Duel", graphData: { nodes: [] } }];

    const worth = ProgramPanel.worthOffering(copies, programs, "open").map(copy => copy.key);
    assert.deepEqual(worth, ["p2", "p3", "new-abc"], "what differs from the server's copy, what the server no longer has, and what it never had");
});
