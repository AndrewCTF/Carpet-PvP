/* ═══════════════════════════════════════════════════════════════
   App — Bootstrap & global utilities
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
    const entries = document.getElementById("log-entries");
    const entry = el("div", "log-entry");
    entry.append(
        el("span", "log-time", new Date().toLocaleTimeString("en-GB", { hour12: false })),
        el("span", "log-msg" + (kind === "error" ? " log-error" : kind === "warn" ? " log-warn" : ""), msg));
    entries.append(entry);
    while (entries.childElementCount > 500) entries.firstElementChild.remove();
    entries.parentElement.scrollTop = entries.parentElement.scrollHeight;
    if (kind === "error") {
        document.getElementById("log-panel").classList.remove("collapsed");
        console.error("[CarpetLogic]", msg);
    } else {
        console.log("[CarpetLogic]", msg);
    }
}

function isInputFocused() {
    const active = document.activeElement;
    return active && (active.tagName === "INPUT" || active.tagName === "TEXTAREA" || active.tagName === "SELECT" || active.isContentEditable);
}

// ── Init ─────────────────────────────────────────────────────
document.addEventListener("DOMContentLoaded", () => {

    BotPanel.init();
    ProgramPanel.init();

    // Connection status
    API.on("connectionChange", (connected) => {
        document.getElementById("connection-dot").className = "conn-dot " + (connected ? "connected" : "disconnected");
        document.getElementById("connection-text").textContent = connected ? "Online" : "Offline";
        log(connected ? "Connected to server" : "Disconnected, retrying…", connected ? "info" : "error");
    });
    API.on("unauthorized", (message) => {
        document.getElementById("connection-text").textContent = "No access";
        document.getElementById("no-access-text").textContent = message || "This page has no valid access token.";
        document.getElementById("no-access").classList.add("active");
    });
    API.on("log", (data) => log(data.message, data.level));

    // Connect to the server this page came from. The node types are built from its action schema,
    // and the lists the schema cannot know come from its settings, so the editor starts once both are here.
    API.connect();
    if (API.hasToken()) {
        Promise.all([API.getSchema(), API.getSettings()]).then(([schema, settings]) => {
            Nodes.register(schema, settings);
            NodeCompiler.setSchema(schema);
            NodeEditor.init(schema);
            log("Node editor ready. Open the Bots panel (B) to spawn a bot, then press Run.");
        }).catch((e) => log("Could not load the action schema: " + e.message, "error"));
    }

    // Console
    const logPanel = document.getElementById("log-panel");
    const logToggle = document.getElementById("log-toggle");
    logToggle.addEventListener("click", () => {
        logPanel.classList.toggle("collapsed");
        logToggle.textContent = logPanel.classList.contains("collapsed") ? "▲" : "▼";
    });
    document.getElementById("clear-log-btn").addEventListener("click", () => {
        document.getElementById("log-entries").replaceChildren();
    });

    // Bot panel
    const rightPanel = document.getElementById("right-panel");
    document.getElementById("btn-bots").addEventListener("click", () => rightPanel.classList.toggle("panel-closed"));
    document.getElementById("rp-close").addEventListener("click", () => rightPanel.classList.add("panel-closed"));

    // Keyboard shortcuts
    document.addEventListener("keydown", (e) => {
        const ctrl = e.ctrlKey || e.metaKey;
        const key = e.key.toLowerCase();
        if (ctrl && key === "s") {
            e.preventDefault();
            ProgramPanel.saveProgram();
        } else if (ctrl && key === "o") {
            e.preventDefault();
            document.getElementById("btn-load").click();
        } else if (ctrl && key === "n") {
            e.preventDefault();
            document.getElementById("btn-new").click();
        } else if (ctrl && key === "z" && !isInputFocused()) {
            e.preventDefault();
            if (e.shiftKey) NodeEditor.redo(); else NodeEditor.undo();
        } else if (ctrl && key === "y" && !isInputFocused()) {
            e.preventDefault();
            NodeEditor.redo();
        } else if (key === "b" && !ctrl && !isInputFocused()) {
            rightPanel.classList.toggle("panel-closed");
        } else if (e.key === "Escape") {
            rightPanel.classList.add("panel-closed");
            document.querySelectorAll(".modal-overlay.active:not(#no-access)").forEach(m => m.classList.remove("active"));
        }
    });
});
