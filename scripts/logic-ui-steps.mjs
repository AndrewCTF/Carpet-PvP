// The pictures scripts/logic-ui-check.sh takes of the CarpetLogic web editor: the ones docs/ shows, and no
// others. LOGIC_PHASE says which half is wanted: "signin" while the admin sign-in is on, "closed" once it has
// been turned off again.
import { chmodSync } from "node:fs";

const PASSWORD = "red carpet diamond sword";
const PROGRAM_BAR = '#top-bar .bar-group[aria-label="Program"]';

async function open(shots, env, width, height) {
    const page = await shots.page(width, height);
    await page.goto(env.LOGIC_URL + "/#token=" + env.LOGIC_TOKEN);
    await page.until("document.querySelector('#canvas-state').classList.contains('hidden')");
    await page.until("document.querySelectorAll('#guide-preset-list button').length > 0");
    return page;
}

// Adds a node the way the keyboard does: find it by name, Enter.
async function add(page, name) {
    await page.eval("document.querySelector('#node-search').value = ''; document.querySelector('#node-search').dispatchEvent(new Event('input')); document.querySelector('#node-search').focus()");
    await page.type("#node-search", name);
    await page.key("Enter");
    await page.eval("document.querySelector('#node-search').value = ''; document.querySelector('#node-search').dispatchEvent(new Event('input')); document.activeElement.blur()");
    await page.wait(150);
}

// Adds the node of exactly that title, where a search finds more than one.
async function addNamed(page, search, title) {
    await page.eval("document.querySelector('#node-search').value = ''; document.querySelector('#node-search').dispatchEvent(new Event('input')); document.querySelector('#node-search').focus()");
    await page.type("#node-search", search);
    await page.eval("[...document.querySelectorAll('.node-row')].find(r => r.querySelector('.node-row-title').textContent === " + JSON.stringify(title) + ").click()");
    await page.eval("document.querySelector('#node-search').value = ''; document.querySelector('#node-search').dispatchEvent(new Event('input')); document.activeElement.blur()");
    await page.wait(150);
}

// Waits for the toolbar to say something about the save, and says what it said instead when it never does.
async function saveState(page, text) {
    try {
        await page.until("document.querySelector('#program-state').textContent.includes(" + JSON.stringify(text) + ")", 30000);
    } catch (e) {
        const said = await page.eval("document.querySelector('#program-state').textContent + ' (' + document.querySelector('#program-state').title + '); console: ' + document.querySelector('#log-last').textContent");
        throw new Error("The toolbar never said '" + text + "', it says: " + said);
    }
}

async function editor(shots, env) {
    const page = await open(shots, env, 1440, 900);
    await page.until("document.querySelectorAll('.bot-card').length === 2");
    await page.wait(400);
    await page.shot(shots.out("carpetlogic-editor.png"));

    // Finding a node by what it does.
    await page.key("/");
    await page.type("#node-search", "health");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-library.png"));
    await page.eval("document.querySelector('#node-search').value = ''; document.querySelector('#node-search').dispatchEvent(new Event('input')); document.activeElement.blur()");

    // A preset, run on one of the two bots. The canvas has the room of the bots panel for this picture.
    await page.click("#btn-bots");
    await page.click("#btn-autosave");
    await page.click("#btn-load");
    await page.until("document.querySelectorAll('#preset-program-list .program-list-item').length > 0");
    await page.eval("[...document.querySelectorAll('#preset-program-list .program-list-item')].find(i => i.textContent.includes('Patrol and Fight')).querySelector('button').click()");
    await page.wait(500);
    await page.eval("document.querySelector('#target-bot-select').value = 'Striker'; document.querySelector('#target-bot-select').dispatchEvent(new Event('change'))");
    await page.click("#exec-run-btn");
    await page.until("document.querySelector('#exec-state').textContent.startsWith('Running')");
    await page.wait(600);
    await page.shot(shots.out("carpetlogic-program.png"));

    await page.click("#btn-bots");
    await page.until("document.querySelectorAll('.bot-card').length === 2");
    await page.wait(500);
    await page.shot(shots.out("carpetlogic-bots.png"));

    // Somebody who came in with a link reads the settings, and is offered the sign-in.
    await page.click("#btn-settings");
    await page.until("document.querySelectorAll('#settings-list .rule').length > 0");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-settings-readonly.png"));
    await page.key("Escape");
    await page.click("#btn-stop");
    await page.click("#btn-autosave");
    await page.wait(300);
    await page.close();

    const narrow = await open(shots, env, 900, 700);
    await narrow.wait(400);
    await narrow.shot(shots.out("carpetlogic-editor-narrow.png"));
    await narrow.close();
}

// The toolbar in every state a save can be in, the offer of work the browser kept, the choice between two
// copies, and the list of programs with one of them in a subfolder.
async function saving(shots, env) {
    const folder = env.LOGIC_SERVER_DIR + "/world/carpetlogic/programs";
    const network = (page, conditions) => page.send("Network.emulateNetworkConditions",
        { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1, ...conditions });
    const call = (page, path, body) => page.eval("fetch(" + JSON.stringify(path) + ", { method: 'POST', headers: { Authorization: 'Bearer ' + sessionStorage.getItem('carpetlogic.token'), 'Content-Type': 'application/json' }, body: JSON.stringify(" + JSON.stringify(body) + ") }).then(r => r.json())");

    let page = await open(shots, env, 1440, 900);
    await page.send("Network.enable");
    await page.shot(shots.out("carpetlogic-save-new.png"), PROGRAM_BAR);

    // With autosave off, a change waits for the Save button.
    await page.click("#btn-autosave");
    await page.eval("document.querySelector('#program-name').focus(); document.querySelector('#program-name').select()");
    await page.send("Input.insertText", { text: "Guard the gate" });
    await add(page, "sprint");
    await page.shot(shots.out("carpetlogic-save-unsaved.png"), PROGRAM_BAR);

    // A server that takes its time, to see the save under way.
    await network(page, { latency: 2500 });
    await page.click("#btn-save");
    await saveState(page, "Saving");
    await page.shot(shots.out("carpetlogic-save-saving.png"), PROGRAM_BAR);
    await network(page, {});
    await saveState(page, "Saved");
    await page.shot(shots.out("carpetlogic-save-saved.png"), PROGRAM_BAR);

    // With autosave on, a change is saved a moment later.
    await page.click("#btn-autosave");
    await add(page, "delay");
    await saveState(page, "Autosaved");
    await page.shot(shots.out("carpetlogic-save-autosaved.png"), PROGRAM_BAR);

    // An If / Else with nothing on its condition does not compile: it is saved all the same, as a draft.
    await addNamed(page, "if", "If / Else");
    await saveState(page, "does not run yet");
    await page.shot(shots.out("carpetlogic-save-draft.png"), PROGRAM_BAR);

    // A folder the server may not write to. The tab is closed while saving still fails; the work it had is
    // kept in the browser and offered back when the editor is opened again.
    chmodSync(folder, 0o555);
    try {
        await add(page, "jump");
        await saveState(page, "Save failed");
        await page.shot(shots.out("carpetlogic-save-failed.png"), PROGRAM_BAR);
        await page.goto("about:blank");
        // The save the page sent on its way out has to meet the folder as it is now.
        await page.wait(1000);
    } finally {
        chmodSync(folder, 0o755);
    }
    await page.goto(env.LOGIC_URL + "/");
    await page.until("!document.querySelector('#recovery-bar').classList.contains('hidden')");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-save-kept.png"), "#recovery-bar");
    await page.click("#recovery-restore");
    await saveState(page, "Autosaved");

    // A server that cannot be reached: the work stays in the browser until it answers again.
    await network(page, { offline: true });
    await add(page, "sneak");
    await saveState(page, "Kept in this browser");
    await page.shot(shots.out("carpetlogic-save-offline.png"), PROGRAM_BAR);
    await network(page, {});
    await saveState(page, "Autosaved");

    // Somebody else saves the same program; this page's next save is refused and asks which copy stays.
    const mine = (await page.eval("fetch('/api/programs', { headers: { Authorization: 'Bearer ' + sessionStorage.getItem('carpetlogic.token') } }).then(r => r.json())"))
        .find(program => program.name === "Guard the gate");
    await call(page, "/api/programs", { id: mine.id, name: mine.name, graphData: { nodes: [], links: [] }, actions: [] });
    await add(page, "jump");
    await saveState(page, "Changed elsewhere");
    await page.click("#btn-save");
    await page.until("document.querySelector('#conflict-modal').classList.contains('active')");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-save-conflict.png"));
    await page.click("#conflict-mine");
    await saveState(page, "Autosaved");

    // More programs, one of them moved into a subfolder from the list.
    for (const name of ["W-Tap drill", "Crit drill", "Arena opener"]) {
        await call(page, "/api/programs", { name, graphData: null, actions: [{ type: "JUMP" }] });
    }
    await page.click("#btn-load");
    await page.until("document.querySelectorAll('#load-program-list .program-list-item').length === 4");
    for (const name of ["W-Tap drill", "Crit drill"]) {
        const row = "[...document.querySelectorAll('#load-program-list .program-list-item')].find(i => i.textContent.includes(" + JSON.stringify(name) + "))";
        const drills = await page.eval("[..." + row + ".querySelector('select').options].some(o => o.value === 'Drills')");
        await page.eval("(() => { const s = " + row + ".querySelector('select'); s.value = " + (drills ? "'Drills'" : "'+'") + "; s.dispatchEvent(new Event('change')); })()");
        if (!drills) {
            await page.send("Input.insertText", { text: "Drills" });
            await page.key("Enter");
        }
        await page.until("[...document.querySelectorAll('#load-program-list .folder-heading')].some(h => h.textContent.startsWith('Drills" + (drills ? "2" : "1") + "'))");
    }
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-open.png"));
    await page.close();
}

// A field of the inspector that holds an expression: the names it knows offered as they are typed, and a
// mistake said under the field and on the node.
async function expressions(shots, env) {
    const page = await open(shots, env, 1440, 900);
    await page.click("#btn-bots");
    await page.click("#btn-autosave");
    await addNamed(page, "if", "If");
    await page.until("!document.querySelector('#inspector').classList.contains('hidden')");
    await page.eval("document.querySelector('#inspect-condition').focus(); document.querySelector('#inspect-condition').select()");
    await page.send("Input.insertText", { text: "health < 10 and target_dis" });
    await page.until("document.querySelectorAll('.suggest-item').length > 0");
    await page.until("document.querySelector('#inspector .field-note.refused')");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-expression.png"));
    await page.click("#btn-autosave");
    await page.close();
}

async function signIn(shots, env) {
    let page = await shots.page(1440, 900);
    await page.goto(env.LOGIC_URL + "/");
    await page.until("!document.querySelector('#auth-signin').classList.contains('hidden')");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-login.png"));
    await page.close();

    // The link the console printed: it sets the password and signs its admin in.
    page = await shots.page(1440, 900);
    await page.goto(env.LOGIC_URL + "/" + env.LOGIC_SETUP);
    await page.until("!document.querySelector('#auth-setup').classList.contains('hidden')");
    await page.type("#auth-new", PASSWORD);
    await page.type("#auth-repeat", PASSWORD);
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-set-password.png"));
    await page.click("#auth-setup-submit");
    await page.until("document.querySelector('#auth-screen').classList.contains('hidden')");
    await page.until("!document.querySelector('#identity-role').classList.contains('hidden')");

    // The admin changes a rule, and is refused a value the rule does not take.
    await page.click("#btn-settings");
    await page.until("document.querySelectorAll('#settings-list .rule').length > 0");
    for (const [rule, value, outcome] of [["carpetLogicUpdateInterval", "10", "saved"], ["carpetLogicMaxPrograms", "-1", "refused"]]) {
        await page.eval("document.querySelector('[aria-label=\"" + rule + "\"]').focus(); document.querySelector('[aria-label=\"" + rule + "\"]').select()");
        await page.send("Input.insertText", { text: value });
        await page.key("Enter");
        await page.until("document.querySelector('.field-note." + outcome + "')");
    }
    await page.eval("document.activeElement.blur()");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-settings-admin.png"));
    await page.key("Escape");
    await page.click("#btn-signout");
    await page.until("!document.querySelector('#auth-signin').classList.contains('hidden')");
    await page.close();
}

async function closed(shots, env) {
    const page = await shots.page(1440, 900);
    await page.goto(env.LOGIC_URL + "/");
    await page.until("!document.querySelector('#auth-none').classList.contains('hidden')");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-no-session.png"));
    await page.close();
}

export default async function (shots, env) {
    if (env.LOGIC_PHASE === "closed") {
        await closed(shots, env);
        return;
    }
    const only = env.LOGIC_ONLY;
    if (!only || only === "editor") await editor(shots, env);
    if (!only || only === "saving") await saving(shots, env);
    if (!only || only === "expressions") await expressions(shots, env);
    if (!only || only === "signin") await signIn(shots, env);
}
