/* ═══════════════════════════════════════════════════════════════
   App — Bootstrap & global utilities
   ═══════════════════════════════════════════════════════════════ */

// ── Global log function ──────────────────────────────────────
function log(msg, level) {
    const entries = document.getElementById("log-entries");
    if (!entries) return;
    const time = new Date().toLocaleTimeString("en-GB", { hour12: false });
    const cls = level === "error" ? 'style="color:#ef4444"' : "";
    entries.innerHTML += `<div class="log-entry"><span class="log-time">${time}</span><span class="log-msg" ${cls}>${msg}</span></div>`;
    entries.parentElement.scrollTop = entries.parentElement.scrollHeight;
    if (level === "error") console.error("[CarpetLogic]", msg);
    else console.log("[CarpetLogic]", msg);
}

// ── Init ─────────────────────────────────────────────────────
document.addEventListener("DOMContentLoaded", () => {

    // 1. Boot node editor
    NodeEditor.init();

    // 2. Boot panels
    BotPanel.init();
    ProgramPanel.init();

    // 3. Connection status
    API.on("connectionChange", (connected) => {
        const dot = document.getElementById("connection-dot");
        const text = document.getElementById("connection-text");
        dot.className = "conn-dot " + (connected ? "connected" : "disconnected");
        text.textContent = connected ? "Online" : "Offline";
        if (connected) {
            log("Connected to server");
            BotPanel.refreshBotList();
            refreshProgramDropdown();
        } else {
            log("Disconnected — retrying…", "error");
        }
    });

    API.on("unauthorized", (message) => {
        document.getElementById("connection-text").textContent = "No access";
        log(message || "Not authorised. Run /carpetlogic open in game for a link.", "error");
    });

    // 4. Connect to the server this page came from
    API.connect();

    // 5. Console toggle
    const logPanel = document.getElementById("log-panel");
    const logToggle = document.getElementById("log-toggle");
    logToggle.addEventListener("click", () => {
        logPanel.classList.toggle("collapsed");
        logToggle.textContent = logPanel.classList.contains("collapsed") ? "▲" : "▼";
    });

    // 6. Clear log
    document.getElementById("clear-log-btn").addEventListener("click", () => {
        document.getElementById("log-entries").innerHTML = "";
    });

    // 7. SSE log forwarding
    API.on("log", (data) => {
        log(data.message || JSON.stringify(data), data.level);
    });

    // 8. Bot panel toggle — right-click on play opens bot panel
    const rightPanel = document.getElementById("right-panel");
    const rpClose = document.getElementById("rp-close");

    // Open bot panel on right-click on play button or via keyboard B
    document.getElementById("exec-run-btn").addEventListener("contextmenu", (e) => {
        e.preventDefault();
        rightPanel.classList.toggle("panel-closed");
    });

    rpClose.addEventListener("click", () => {
        rightPanel.classList.add("panel-closed");
    });

    // 9. Program dropdown — load selected program
    const progSelect = document.getElementById("program-select");
    progSelect.addEventListener("change", function() {
        const id = this.value;
        if (id) {
            ProgramPanel.loadProgram(id);
            log("Loading program: " + id);
        }
    });
    // Refresh on focus
    progSelect.addEventListener("focus", refreshProgramDropdown);

    // 10. Keyboard shortcuts
    document.addEventListener("keydown", (e) => {
        // Ctrl+S = save
        if ((e.ctrlKey || e.metaKey) && e.key === "s") {
            e.preventDefault();
            document.getElementById("btn-save").click();
        }
        // Ctrl+O = load modal
        if ((e.ctrlKey || e.metaKey) && e.key === "o") {
            e.preventDefault();
            document.getElementById("btn-load").click();
        }
        // Ctrl+N = new
        if ((e.ctrlKey || e.metaKey) && e.key === "n") {
            e.preventDefault();
            document.getElementById("btn-new").click();
        }
        // B = toggle bot panel (when not focused on input)
        if (e.key === "b" && !isInputFocused()) {
            rightPanel.classList.toggle("panel-closed");
        }
        // Escape = close panels
        if (e.key === "Escape") {
            rightPanel.classList.add("panel-closed");
            document.querySelectorAll(".modal-overlay.active").forEach(m => m.classList.remove("active"));
        }
    });

    log("CarpetLogic Node Editor ready");
    log("Press B to toggle bot panel • Right-click Play for controls");
});

// ── Helpers ──────────────────────────────────────────────────
function isInputFocused() {
    const el = document.activeElement;
    return el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.tagName === "SELECT" || el.isContentEditable);
}

async function refreshProgramDropdown() {
    try {
        const data = await API.getPrograms();
        const programs = Array.isArray(data) ? data : (data.programs || Object.values(data));
        const sel = document.getElementById("program-select");
        const current = sel.value;
        sel.innerHTML = '<option value="">Default</option>' +
            programs.map(p => `<option value="${p.id}"${p.id === current ? " selected" : ""}>${p.name || p.id}</option>`).join("");
    } catch (e) { /* not connected yet */ }
}
