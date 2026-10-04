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
        saved: false, changed: false, hasWork: false, saving: false, failed: null, savedAt: null, autosave: false
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
    const failed = status({ saved: true, savedAt: 1200, changed: true, hasWork: true, failed: "A If / Else node has no condition connected" });
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
