/* ═══════════════════════════════════════════════════════════════
   App — start-up, the console, the dialogs and panels, the keyboard,
   and what the page shows about its session and its connection
   ═══════════════════════════════════════════════════════════════ */

// ── DOM helper ───────────────────────────────────────────────
// Everything that comes from the server or the user (names, messages, values) goes into the page as text
// through this helper, never as HTML.
function el(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined && text !== null) element.textContent = String(text);
    return element;
}

// ── Console log ──────────────────────────────────────────────
function log(msg, level) {
    const kind = String(level || "info").toLowerCase();
    const tone = kind === "error" ? " log-error" : kind === "warn" ? " log-warn" : "";
    const entries = document.getElementById("log-entries");
    const entry = el("div", "log-entry");
    entry.append(
        el("span", "log-time", new Date().toLocaleTimeString("en-GB", { hour12: false })),
        el("span", "log-msg" + tone, msg));
    entries.append(entry);
    while (entries.childElementCount > 500) entries.firstElementChild.remove();
    entries.parentElement.scrollTop = entries.parentElement.scrollHeight;
    // The newest line stays readable while the console is shut.
    const last = document.getElementById("log-last");
    last.textContent = msg;
    last.className = "log-last" + tone;
    if (kind === "error") {
        Console.show(true);
        console.error("[CarpetLogic]", msg);
    } else {
        console.log("[CarpetLogic]", msg);
    }
}

function isInputFocused() {
    const active = document.activeElement;
    return active && (active.tagName === "INPUT" || active.tagName === "TEXTAREA" || active.tagName === "SELECT" || active.isContentEditable);
}

const Console = (() => {
    function show(open) {
        document.getElementById("log-panel").classList.toggle("collapsed", !open);
        document.getElementById("console-bar").classList.toggle("open", open);
        document.getElementById("log-toggle").setAttribute("aria-expanded", String(open));
    }

    function toggle() {
        show(document.getElementById("log-panel").classList.contains("collapsed"));
    }

    function init() {
        document.getElementById("log-toggle").addEventListener("click", toggle);
        document.getElementById("clear-log-btn").addEventListener("click", () => {
            document.getElementById("log-entries").replaceChildren();
            document.getElementById("log-last").textContent = "";
        });
    }

    return { init, show, toggle };
})();

// ── Dialogs ──────────────────────────────────────────────────
const Modal = (() => {
    let opener = null;      // what had the focus before a dialog took it

    function open(id) {
        if (!current()) opener = document.activeElement;
        document.getElementById(id).classList.add("active");
    }

    function close(id) {
        document.getElementById(id).classList.remove("active");
        if (!current() && opener && opener.focus) opener.focus();
    }

    function current() {
        const open = document.querySelectorAll(".modal-overlay.active");
        return open.length ? open[open.length - 1] : null;
    }

    /** Closes the dialog on top, and says whether there was one. */
    function closeTop() {
        const top = current();
        if (top) close(top.id);
        return Boolean(top);
    }

    function init() {
        document.querySelectorAll(".modal-overlay").forEach(overlay => {
            overlay.querySelectorAll(".modal-close").forEach(button => button.addEventListener("click", () => close(overlay.id)));
            overlay.addEventListener("mousedown", (e) => {
                if (e.target === overlay) close(overlay.id);
            });
        });
        // Tab stays inside the dialog that is open, and inside the sign-in screen while that is up.
        document.addEventListener("keydown", (e) => {
            const box = Auth.isOpen() ? document.getElementById("auth-screen") : current();
            if (e.key !== "Tab" || !box) return;
            const stops = [...box.querySelectorAll("button, input, select, [tabindex]")]
                .filter(node => !node.disabled && node.tabIndex >= 0 && node.offsetParent !== null);
            if (stops.length === 0) return;
            const first = stops[0];
            const last = stops[stops.length - 1];
            if (!box.contains(document.activeElement)) { e.preventDefault(); first.focus(); }
            else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
        });
    }

    return { init, open, close, closeTop, current };
})();

// ── The panels beside the canvas ─────────────────────────────
const Docks = (() => {
    const BOTS_KEY = "carpetlogic.bots.open";

    function bots(open) {
        document.getElementById("right-panel").classList.toggle("panel-closed", !open);
        document.getElementById("btn-bots").setAttribute("aria-expanded", String(open));
        document.getElementById("guide-bots").classList.toggle("hidden", open || Boolean(BotPanel.getTargetBot()));
        try { localStorage.setItem(BOTS_KEY, String(open)); } catch (e) { /* not remembered */ }
    }

    function botsOpen() {
        return !document.getElementById("right-panel").classList.contains("panel-closed");
    }

    // The library is part of the page where there is room for it, and slides over the canvas where there is not.
    function library(open) {
        document.body.classList.toggle("library-open", open);
        document.getElementById("btn-library").setAttribute("aria-expanded", String(open));
    }

    function init() {
        let remembered = null;
        try { remembered = localStorage.getItem(BOTS_KEY); } catch (e) { /* nothing remembered */ }
        // Where the panel lies over the canvas it starts shut, whatever a wider window remembered.
        bots(window.innerWidth >= 1180 && (remembered === null ? window.innerWidth >= 1280 : remembered === "true"));
        document.getElementById("btn-bots").addEventListener("click", () => bots(!botsOpen()));
        document.getElementById("rp-close").addEventListener("click", () => bots(false));
        document.getElementById("btn-library").addEventListener("click", () => library(!document.body.classList.contains("library-open")));
        document.getElementById("library-close").addEventListener("click", () => library(false));
    }

    return { init, bots, botsOpen, library };
})();

// ── The session, the connection and the editor ───────────────
const App = (() => {

    let status = null;          // what GET /api/status last answered
    let wasConnected = false;
    let everConnected = false;  // a page that has not connected yet has lost nothing
    let loading = false;

    function $(id) { return document.getElementById(id); }

    function init() {
        Console.init();
        Modal.init();
        Docks.init();
        BotPanel.init();
        ProgramPanel.init();
        SettingsPanel.init(ruleChanged, () => {
            Modal.close("settings-modal");
            Auth.open({ dismissible: true, offersSignIn: true });
        });
        Auth.init(enter);

        API.on("connectionChange", showConnection);
        API.on("unauthorized", signedOut);
        API.on("log", (data) => log(data.message, data.level));
        API.on("botUpdate", (update) => {
            if (status && Boolean(update.viewerMode) !== Boolean(status.viewerMode)) {
                status.viewerMode = Boolean(update.viewerMode);
                showNotice();
            }
            showGuideBots();
        });

        $("btn-help").addEventListener("click", () => Modal.open("help-modal"));
        $("btn-signout").addEventListener("click", signOut);
        $("btn-admin").addEventListener("click", () => Auth.open({ dismissible: true, offersSignIn: true }));
        $("canvas-retry").addEventListener("click", loadEditor);
        $("guide-bots").addEventListener("click", () => {
            Docks.bots(true);
            $("bot-name-input").focus();
        });
        document.addEventListener("keydown", shortcut);

        // A set-password link is what this tab was opened for, whatever session it may still hold.
        API.loadToken();
        if (Auth.hasSetup()) Auth.open({ dismissible: API.hasToken() });
        if (API.hasToken()) enter();
        else if (!Auth.hasSetup()) Auth.open();
    }

    /** What happens once this page has a session: at start-up with a link's token, and after a sign-in. */
    function enter() {
        API.connect();
        refreshStatus();
        BotPanel.loadHistory();
        if (!NodeEditor.isReady()) loadEditor();
    }

    // The node types are built from the server's action schema, and the lists the schema cannot know come
    // from its settings, so the editor starts once both are here.
    async function loadEditor() {
        if (loading) return;
        loading = true;
        $("canvas-state").classList.remove("hidden", "error");
        $("canvas-state-text").textContent = "Loading the node set from the server";
        $("canvas-retry").classList.add("hidden");
        try {
            const [schema, settings] = await Promise.all([API.getSchema(), API.getSettings()]);
            Nodes.register(schema, settings);
            NodeCompiler.setSchema(schema);
            NodeEditor.init(schema);
            Inspector.init(schema, settings);
            ProgramPanel.remember();
            ProgramPanel.offerKept();
            $("canvas-state").classList.add("hidden");
            log("Editor ready. Add nodes from the library, choose a bot, press Run.");
            showGuidePresets();
        } catch (e) {
            if (API.hasToken()) {
                $("canvas-state").classList.add("error");
                $("canvas-state-text").textContent = "The node set could not be loaded: " + e.message;
                $("canvas-retry").classList.remove("hidden");
            }
        }
        loading = false;
    }

    async function refreshStatus() {
        try {
            status = await API.getStatus();
        } catch (e) {
            return;
        }
        $("identity").classList.remove("hidden");
        $("identity-name").textContent = status.user;
        $("identity-role").classList.toggle("hidden", !status.admin);
        $("btn-admin").classList.toggle("hidden", status.admin || !status.adminLogin);
        showNotice();
    }

    // ── Connection and notices ───────────────────────────────
    function showConnection(connected) {
        $("connection-dot").className = "conn-dot " + (connected ? "connected" : "disconnected");
        $("connection-text").textContent = connected ? "Connected" : API.hasToken() ? "Reconnecting" : "Signed out";
        if (connected) {
            if (!wasConnected) log("Connected to the server");
            everConnected = true;
            refreshStatus();
        } else if (wasConnected && API.hasToken() && !Auth.isOpen()) {
            log("Lost the connection to the server. Trying again every few seconds.", "error");
        }
        wasConnected = connected;
        showNotice();
    }

    // One line under the top bar for what changes how far the page can be trusted or used.
    function showNotice() {
        const bar = $("notice-bar");
        let text = "";
        if (everConnected && !wasConnected && API.hasToken()) {
            text = "No connection to the server. What is shown may be out of date, and nothing can be run or saved until it is back.";
        } else if (status && status.viewerMode) {
            text = "Viewer mode: this editor shows bots and programs but cannot change or run anything."
                + (status.admin ? " As an admin you can turn carpetLogicViewerMode off in Settings." : "");
        }
        bar.textContent = text;
        bar.className = "notice-bar" + (text ? "" : " hidden") + (status && status.viewerMode && wasConnected ? " viewer" : "");
        document.body.classList.toggle("viewer-mode", Boolean(status && status.viewerMode));
        // In viewer mode the server refuses every write, so nothing is sent to be refused.
        ProgramPanel.setWritable(!(status && status.viewerMode));
    }

    // ── Getting in and out ───────────────────────────────────
    function signedOut(event) {
        if (Auth.isOpen() && !event.denied) return;
        $("identity").classList.add("hidden");
        if (event.denied) {
            Auth.open({ reason: event.message, denied: true });
        } else if (wasConnected || status) {
            Auth.open({ reason: "Your session has ended.", offersSignIn: event.adminLogin });
        } else {
            Auth.open({ offersSignIn: event.adminLogin || undefined });
        }
        status = null;
    }

    async function signOut() {
        await API.signOut();
        status = null;
        $("identity").classList.add("hidden");
        Modal.closeTop();
        Auth.open({ reason: "You signed out." });
    }

    // A rule changed in the Settings panel may be one this page lives by.
    function ruleChanged(rule, answer) {
        if (answer.status === 401) {
            Modal.close("settings-modal");
            signedOut({ adminLogin: Boolean(answer.body.adminLogin) });
        } else {
            refreshStatus();
        }
    }

    // ── The guide ────────────────────────────────────────────
    function showGuideBots() {
        const names = Object.keys(BotPanel.state.bots);
        const target = BotPanel.getTargetBot();
        $("guide-step-bot").classList.toggle("done", Boolean(target));
        $("guide-bot-text").textContent = target ? "It will run on " + target + ". Another one can be chosen in the run bar below."
            : names.length === 0 ? "There is no bot on the server yet. Spawn one from the bots panel."
            : "There are " + names.length + " bots on the server. Choose one in the run bar below, or click its card.";
        $("guide-bots").classList.toggle("hidden", Boolean(target) || Docks.botsOpen());
    }

    async function showGuidePresets() {
        try {
            const presets = await API.getPresets();
            const list = $("guide-preset-list");
            list.replaceChildren();
            for (const preset of presets.slice(0, 4)) {
                const open = el("button", "btn small", preset.name);
                open.title = preset.description || "";
                open.addEventListener("click", () => ProgramPanel.loadProgram(preset));
                list.append(open);
            }
            if (presets.length > 4) {
                const more = el("button", "btn small quiet", "All " + presets.length + " presets");
                more.addEventListener("click", () => ProgramPanel.openLoadModal("presets"));
                list.append(more);
            }
        } catch (e) {
            // The guide does without its presets; Open still lists them.
        }
    }

    // ── Keyboard ─────────────────────────────────────────────
    function shortcut(e) {
        if (Auth.isOpen()) {
            if (e.key === "Escape" && !$("auth-back").classList.contains("hidden")) $("auth-back").click();
            return;
        }
        const ctrl = e.ctrlKey || e.metaKey;
        const key = e.key.toLowerCase();
        if (e.key === "Escape") {
            if (!Modal.closeTop()) {
                if (document.body.classList.contains("library-open")) Docks.library(false);
                else if (isInputFocused()) document.activeElement.blur();
            }
            return;
        }
        if (ctrl && key === "s") {
            e.preventDefault();
            ProgramPanel.saveProgram();
        } else if (ctrl && key === "o") {
            e.preventDefault();
            ProgramPanel.openLoadModal();
        } else if (ctrl && key === "n") {
            e.preventDefault();
            $("btn-new").click();
        } else if (ctrl && e.key === "Enter") {
            e.preventDefault();
            ProgramPanel.runProgram();
        } else if (ctrl && e.key === ".") {
            e.preventDefault();
            ProgramPanel.stopProgram();
        } else if (ctrl && key === "z" && !isInputFocused()) {
            e.preventDefault();
            if (e.shiftKey) NodeEditor.redo(); else NodeEditor.undo();
        } else if (ctrl && key === "y" && !isInputFocused()) {
            e.preventDefault();
            NodeEditor.redo();
        } else if (ctrl || e.altKey || isInputFocused() || Modal.current()) {
            return;
        } else if (e.key === "/") {
            e.preventDefault();
            Docks.library(true);
            NodeLibrary.focus();
        } else if (e.key === "?") {
            Modal.open("help-modal");
        } else if (key === "b") {
            Docks.bots(!Docks.botsOpen());
        } else if (key === "f") {
            if (NodeEditor.isReady()) NodeEditor.fit();
        } else if (e.key === "`") {
            Console.toggle();
        }
    }

    return { init };
})();

document.addEventListener("DOMContentLoaded", App.init);
