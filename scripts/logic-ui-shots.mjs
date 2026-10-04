#!/usr/bin/env node
// Screenshots of the CarpetLogic web editor, taken in headless Chrome over its DevTools protocol.
// Nothing to install: node's own WebSocket talks to the browser.
//
//   node scripts/logic-ui-shots.mjs <steps.mjs> <outdir>
//
// The steps file default-exports an async function (shots, env): shots.page(width, height) opens a tab,
// and env is this process's environment, which scripts/logic-ui-check.sh fills with the server's URL
// and the tokens it made. See scripts/logic-ui-steps.mjs.
import { spawn } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const CHROME = process.env.CHROME || ["/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium",
    "/usr/bin/chromium-browser"].find(existsSync);

class Browser {
    static async launch() {
        if (!CHROME) throw new Error("No Chrome found; set CHROME to its path");
        const profile = mkdtempSync(join(tmpdir(), "logic-ui-"));
        const child = spawn(CHROME, ["--headless=new", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
            "--force-device-scale-factor=1", "--remote-debugging-port=0", "--user-data-dir=" + profile, "about:blank"],
            { stdio: ["ignore", "ignore", "pipe"] });
        const address = await new Promise((done, fail) => {
            let seen = "";
            child.stderr.on("data", (chunk) => {
                seen += chunk;
                const match = /DevTools listening on (ws:\/\/\S+)/.exec(seen);
                if (match) done(match[1]);
            });
            child.on("exit", (code) => fail(new Error("Chrome exited with " + code + ": " + seen.slice(-400))));
            setTimeout(() => fail(new Error("Chrome did not start: " + seen.slice(-400))), 30000);
        });
        const socket = new WebSocket(address);
        await new Promise((done, fail) => { socket.onopen = done; socket.onerror = () => fail(new Error("no DevTools socket")); });
        return new Browser(child, profile, socket);
    }

    constructor(child, profile, socket) {
        this.child = child;
        this.profile = profile;
        this.socket = socket;
        this.next = 1;
        this.waiting = new Map();
        socket.onmessage = (event) => {
            const message = JSON.parse(event.data);
            const call = this.waiting.get(message.id);
            if (!call) return;
            this.waiting.delete(message.id);
            if (message.error) call.fail(new Error(message.error.message)); else call.done(message.result);
        };
    }

    send(method, params, sessionId) {
        const id = this.next++;
        return new Promise((done, fail) => {
            this.waiting.set(id, { done, fail });
            this.socket.send(JSON.stringify({ id, method, params: params || {}, sessionId }));
        });
    }

    async page(width, height) {
        const { targetId } = await this.send("Target.createTarget", { url: "about:blank" });
        const { sessionId } = await this.send("Target.attachToTarget", { targetId, flatten: true });
        const page = new Page(this, sessionId, targetId);
        await page.send("Page.enable");
        await page.send("Runtime.enable");
        await page.size(width, height);
        return page;
    }

    async close() {
        try { await this.send("Browser.close"); } catch (e) { /* already gone */ }
        await new Promise((done) => { this.child.on("exit", done); setTimeout(done, 3000); });
        if (this.child.exitCode === null) this.child.kill();
        rmSync(this.profile, { recursive: true, force: true });
    }
}

class Page {
    constructor(browser, sessionId, targetId) {
        this.browser = browser;
        this.sessionId = sessionId;
        this.targetId = targetId;
    }

    send(method, params) { return this.browser.send(method, params, this.sessionId); }

    size(width, height) {
        return this.send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: false });
    }

    async goto(url) {
        await this.send("Page.navigate", { url });
        await this.until("document.readyState === 'complete'");
    }

    /** Evaluates an expression in the page and answers its value; a promise is waited for. */
    async eval(expression) {
        const { result, exceptionDetails } = await this.send("Runtime.evaluate",
            { expression, awaitPromise: true, returnByValue: true });
        if (exceptionDetails) throw new Error("In the page: " + (exceptionDetails.exception?.description || exceptionDetails.text));
        return result.value;
    }

    wait(millis) { return new Promise((done) => setTimeout(done, millis)); }

    async until(expression, millis = 15000) {
        const end = Date.now() + millis;
        while (Date.now() < end) {
            if (await this.eval("Boolean(" + expression + ")")) return;
            await this.wait(100);
        }
        throw new Error("Never became true: " + expression);
    }

    async click(selector) {
        await this.until("document.querySelector(" + JSON.stringify(selector) + ")");
        await this.eval("document.querySelector(" + JSON.stringify(selector) + ").click()");
    }

    /** Types into a field the way a person does, so the page's own input handlers see it. */
    async type(selector, text) {
        await this.until("document.querySelector(" + JSON.stringify(selector) + ")");
        await this.eval("document.querySelector(" + JSON.stringify(selector) + ").focus()");
        await this.send("Input.insertText", { text });
    }

    async key(key, modifiers = 0) {
        const code = key.length === 1 ? "Key" + key.toUpperCase() : key;
        const base = { key, code, modifiers, windowsVirtualKeyCode: key.length === 1 ? key.toUpperCase().charCodeAt(0) : { Enter: 13, Escape: 27, Tab: 9 }[key] || 0 };
        await this.send("Input.dispatchKeyEvent", { type: "keyDown", ...base, text: key.length === 1 && !modifiers ? key : undefined });
        await this.send("Input.dispatchKeyEvent", { type: "keyUp", ...base });
    }

    async mouse(x, y) {
        await this.send("Input.dispatchMouseEvent", { type: "mouseMoved", x, y });
        await this.send("Input.dispatchMouseEvent", { type: "mousePressed", x, y, button: "left", clickCount: 1 });
        await this.send("Input.dispatchMouseEvent", { type: "mouseReleased", x, y, button: "left", clickCount: 1 });
    }

    async shot(file) {
        // Two frames, so that what the last step changed has been painted.
        await this.eval("new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done)))");
        const { data } = await this.send("Page.captureScreenshot", { format: "png" });
        writeFileSync(file, Buffer.from(data, "base64"));
        console.log("SHOT " + file);
    }

    close() { return this.browser.send("Target.closeTarget", { targetId: this.targetId }); }
}

const [stepsFile, outDir] = process.argv.slice(2);
if (!stepsFile || !outDir) {
    console.error("usage: logic-ui-shots.mjs <steps.mjs> <outdir>");
    process.exit(2);
}
mkdirSync(outDir, { recursive: true });
const steps = (await import(pathToFileURL(resolve(stepsFile)).href)).default;
const browser = await Browser.launch();
let failed = false;
try {
    await steps({ page: (width, height) => browser.page(width, height), out: (name) => join(outDir, name) }, process.env);
} catch (error) {
    failed = true;
    console.error("FAILED " + error.message);
} finally {
    await browser.close();
}
process.exit(failed ? 1 : 0);
