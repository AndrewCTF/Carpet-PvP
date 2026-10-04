// The pictures scripts/logic-ui-check.sh takes of the CarpetLogic web editor, at a desktop size and at a
// narrow one. LOGIC_PHASE says which half is wanted: "signin" while the admin sign-in is on, "closed" once it
// has been turned off again.
const SIZES = [[1440, 900, ""], [900, 700, "-narrow"]];
const PASSWORD = "red carpet diamond sword";

async function editor(shots, env, width, height, tag) {
    const page = await shots.page(width, height);
    await page.goto(env.LOGIC_URL + "/#token=" + env.LOGIC_TOKEN);
    await page.until("document.querySelector('#canvas-state').classList.contains('hidden')");
    await page.until("document.querySelectorAll('#guide-preset-list button').length > 0");
    await page.until("document.querySelectorAll('.bot-card').length === 2 || document.querySelector('#right-panel').classList.contains('panel-closed')");
    await page.wait(400);
    await page.shot(shots.out("carpetlogic-editor" + tag + ".png"));

    // Finding a node by what it does.
    await page.key("/");
    await page.type("#node-search", "health");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-library" + tag + ".png"));
    await page.eval("document.querySelector('#node-search').value = ''; document.querySelector('#node-search').dispatchEvent(new Event('input'))");
    await page.eval("document.body.classList.remove('library-open'); document.activeElement.blur()");

    // A preset, run on one of the two bots. The canvas has the room of the bots panel for this picture.
    await page.eval("if (!document.querySelector('#right-panel').classList.contains('panel-closed')) document.querySelector('#btn-bots').click()");
    await page.click("#btn-load");
    await page.until("document.querySelectorAll('#preset-program-list .program-list-item').length > 0");
    await page.eval("[...document.querySelectorAll('#preset-program-list .program-list-item')].find(i => i.textContent.includes('Patrol and Fight')).querySelector('button').click()");
    await page.wait(500);
    await page.eval("document.querySelector('#target-bot-select').value = 'Striker'; document.querySelector('#target-bot-select').dispatchEvent(new Event('change'))");
    await page.click("#exec-run-btn");
    await page.until("document.querySelector('#exec-state').textContent.startsWith('Running')");
    await page.wait(600);
    await page.shot(shots.out("carpetlogic-program" + tag + ".png"));

    await page.eval("if (document.querySelector('#right-panel').classList.contains('panel-closed')) document.querySelector('#btn-bots').click()");
    await page.until("document.querySelectorAll('.bot-card').length === 2");
    await page.wait(500);
    await page.shot(shots.out("carpetlogic-bots" + tag + ".png"));
    if (width < 1180) await page.click("#rp-close");

    await page.click("#btn-load");
    await page.until("document.querySelectorAll('#preset-program-list .program-list-item').length > 0");
    await page.click('.modal-tab[data-tab="presets"]');
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-open" + tag + ".png"));
    await page.key("Escape");

    // Somebody who came in with a link reads the settings, and is offered the sign-in.
    await page.click("#btn-settings");
    await page.until("document.querySelectorAll('#settings-list .rule').length > 0");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-settings-readonly" + tag + ".png"));
    await page.key("Escape");

    await page.click("#btn-stop");
    await page.wait(300);
    await page.close();
}

async function signIn(shots, env, width, height, tag, first) {
    let page = await shots.page(width, height);
    await page.goto(env.LOGIC_URL + "/");
    await page.until("!document.querySelector('#auth-signin').classList.contains('hidden')");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-login" + tag + ".png"));

    await page.type("#auth-name", env.LOGIC_ADMIN);
    await page.type("#auth-password", "not the password");
    await page.click("#auth-submit");
    await page.until("!document.querySelector('#auth-error').classList.contains('hidden')");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-login-error" + tag + ".png"));
    await page.close();

    page = await shots.page(width, height);
    if (first) {
        // The link the console printed: it sets the password and signs its admin in.
        await page.goto(env.LOGIC_URL + "/" + env.LOGIC_SETUP);
        await page.until("!document.querySelector('#auth-setup').classList.contains('hidden')");
        await page.type("#auth-new", PASSWORD);
        await page.type("#auth-repeat", PASSWORD);
        await page.wait(300);
        await page.shot(shots.out("carpetlogic-set-password" + tag + ".png"));
        await page.click("#auth-setup-submit");
    } else {
        await page.goto(env.LOGIC_URL + "/");
        await page.until("!document.querySelector('#auth-signin').classList.contains('hidden')");
        await page.type("#auth-name", env.LOGIC_ADMIN);
        await page.type("#auth-password", PASSWORD);
        await page.click("#auth-submit");
    }
    await page.until("document.querySelector('#auth-screen').classList.contains('hidden')");
    await page.until("!document.querySelector('#identity-role').classList.contains('hidden')");

    // The admin changes a rule, and is refused a value the rule does not take.
    await page.click("#btn-settings");
    await page.until("document.querySelectorAll('#settings-list .rule').length > 0");
    await page.eval("document.querySelector('[aria-label=\"carpetLogicUpdateInterval\"]').focus()");
    await page.eval("document.querySelector('[aria-label=\"carpetLogicUpdateInterval\"]').select()");
    await page.send("Input.insertText", { text: first ? "10" : "5" });
    await page.key("Enter");
    await page.until("document.querySelector('.field-note.saved')");
    await page.eval("document.querySelector('[aria-label=\"carpetLogicMaxPrograms\"]').focus()");
    await page.eval("document.querySelector('[aria-label=\"carpetLogicMaxPrograms\"]').select()");
    await page.send("Input.insertText", { text: "-1" });
    await page.key("Enter");
    await page.until("document.querySelector('.field-note.refused')");
    await page.eval("document.activeElement.blur()");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-settings-admin" + tag + ".png"));
    await page.key("Escape");
    await page.click("#btn-signout");
    await page.until("!document.querySelector('#auth-signin').classList.contains('hidden')");
    await page.close();
}

async function closed(shots, env, width, height, tag) {
    const page = await shots.page(width, height);
    await page.goto(env.LOGIC_URL + "/");
    await page.until("!document.querySelector('#auth-none').classList.contains('hidden')");
    await page.wait(300);
    await page.shot(shots.out("carpetlogic-no-session" + tag + ".png"));
    await page.close();
}

export default async function (shots, env) {
    for (const [width, height, tag] of SIZES) {
        if (env.LOGIC_PHASE === "closed") {
            await closed(shots, env, width, height, tag);
        } else {
            await editor(shots, env, width, height, tag);
            await signIn(shots, env, width, height, tag, tag === "");
        }
    }
}
