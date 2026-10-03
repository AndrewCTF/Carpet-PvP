/* ═══════════════════════════════════════════════════════════════
   Program Panel — new, open, save, run, stop, settings
   ═══════════════════════════════════════════════════════════════ */
const ProgramPanel = (() => {

    let currentProgramId = null;    // the saved program being edited, or null for one not saved yet

    function init() {
        document.getElementById("btn-new").addEventListener("click", newProgram);
        document.getElementById("btn-load").addEventListener("click", openLoadModal);
        document.getElementById("btn-save").addEventListener("click", saveProgram);
        document.getElementById("btn-undo").addEventListener("click", () => NodeEditor.undo());
        document.getElementById("btn-redo").addEventListener("click", () => NodeEditor.redo());
        document.getElementById("btn-clear").addEventListener("click", () => {
            NodeEditor.clearGraph();
            log("Canvas cleared (Undo brings it back)");
        });
        document.getElementById("btn-settings").addEventListener("click", openSettings);

        for (const id of ["exec-run-btn", "btn-run"]) document.getElementById(id).addEventListener("click", runProgram);
        for (const id of ["btn-stop", "exec-stop-btn"]) document.getElementById(id).addEventListener("click", stopProgram);

        document.querySelectorAll(".modal-tab").forEach(tab => {
            tab.addEventListener("click", () => {
                document.querySelectorAll(".modal-tab").forEach(t => t.classList.toggle("active", t === tab));
                document.querySelectorAll(".modal-tab-panel").forEach(p => p.classList.toggle("active", p.id === "panel-" + tab.dataset.tab));
            });
        });
        document.querySelectorAll(".modal-close").forEach(btn => {
            btn.addEventListener("click", () => btn.closest(".modal-overlay").classList.remove("active"));
        });
        document.querySelectorAll(".modal-overlay:not(#no-access)").forEach(overlay => {
            overlay.addEventListener("click", (e) => {
                if (e.target === overlay) overlay.classList.remove("active");
            });
        });
    }

    function programName() {
        return document.getElementById("program-name").value.trim() || "Untitled";
    }

    // ── New / Save ───────────────────────────────────────────
    function newProgram() {
        NodeEditor.clearGraph();
        NodeEditor.resetHistory();
        document.getElementById("program-name").value = "Untitled";
        currentProgramId = null;
        log("New program");
    }

    async function saveProgram() {
        try {
            const program = {
                name: programName(),
                actions: NodeCompiler.compile(NodeEditor.getGraph()),
                graphData: NodeEditor.getGraphJSON()
            };
            // A program that has not been saved yet has no id: the server assigns one.
            if (currentProgramId) program.id = currentProgramId;
            const result = await API.saveProgram(program);
            currentProgramId = result.id;
            log("Saved: " + program.name);
        } catch (e) {
            log("Save failed: " + e.message, "error");
        }
    }

    // ── Open ─────────────────────────────────────────────────
    async function openLoadModal() {
        document.getElementById("load-modal").classList.add("active");
        try {
            const [programs, presets] = await Promise.all([API.getPrograms(), API.getPresets()]);
            programs.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
            renderList("load-program-list", programs, "No saved programs", true);
            renderList("preset-program-list", presets, "No presets", false);
        } catch (e) {
            log("Could not list programs: " + e.message, "error");
        }
    }

    function renderList(listId, programs, emptyText, deletable) {
        const list = document.getElementById(listId);
        list.replaceChildren();
        if (programs.length === 0) list.append(el("div", "empty-hint", emptyText));
        for (const program of programs) {
            const info = el("div", "program-info");
            info.append(
                el("span", "program-name", program.name || program.id),
                el("span", "program-meta", program.description || (program.actions || []).length + " top-level actions"));

            const actions = el("div", "program-actions");
            const open = el("button", "btn-sm primary", "Open");
            open.addEventListener("click", () => loadProgram(program));
            actions.append(open);
            if (deletable) {
                const remove = el("button", "btn-sm red", "Delete");
                remove.addEventListener("click", () => deleteProgram(program));
                actions.append(remove);
            }

            const item = el("div", "program-list-item");
            item.append(info, actions);
            list.append(item);
        }
    }

    // A saved program reopens as the graph it was saved from. A program that has no graph, as the presets
    // do, gets one built from its actions. A preset opens as a copy: saving it creates a new program.
    function loadProgram(program) {
        try {
            if (program.graphData) {
                NodeEditor.loadGraphJSON(program.graphData);
            } else {
                NodeEditor.loadActions(program.actions || []);
            }
            document.getElementById("program-name").value = program.name || "Untitled";
            currentProgramId = program.isPreset ? null : program.id;
            document.getElementById("load-modal").classList.remove("active");
            log("Opened: " + (program.name || program.id));
        } catch (e) {
            log("Could not open " + (program.name || program.id) + ": " + e.message, "error");
        }
    }

    async function deleteProgram(program) {
        if (!confirm("Delete the program \"" + (program.name || program.id) + "\"?")) return;
        try {
            await API.deleteProgram(program.id);
            if (currentProgramId === program.id) currentProgramId = null;
            log("Deleted: " + (program.name || program.id));
            openLoadModal();
        } catch (e) {
            log("Delete failed: " + e.message, "error");
        }
    }

    // ── Run / Stop ───────────────────────────────────────────
    async function runProgram() {
        const bot = BotPanel.getTargetBot();
        if (!bot) {
            document.getElementById("right-panel").classList.remove("panel-closed");
            log("Pick a bot to run on first (Bots panel)", "error");
            return;
        }
        try {
            await API.runProgram(bot, programName(), NodeCompiler.compile(NodeEditor.getGraph()));
        } catch (e) {
            log("Run failed: " + e.message, "error");
        }
    }

    async function stopProgram() {
        const bot = BotPanel.getTargetBot();
        if (!bot) return;
        try {
            await API.stopProgram(bot);
        } catch (e) {
            log("Stop failed: " + e.message, "error");
        }
    }

    // ── Settings ─────────────────────────────────────────────
    async function openSettings() {
        const content = document.querySelector("#settings-modal .settings-content");
        content.replaceChildren(el("span", "rp-muted", "Loading…"));
        document.getElementById("settings-modal").classList.add("active");
        try {
            const settings = await API.getSettings();
            content.replaceChildren(el("p", "notice-text", "Carpet rules, read only here. Change them in game with /carpet <rule> <value>."));
            for (const [rule, value] of Object.entries(settings)) {
                const row = el("div", "settings-row");
                row.append(el("span", "settings-key", rule), el("span", "settings-value", value));
                content.append(row);
            }
        } catch (e) {
            content.replaceChildren(el("span", "rp-muted", "Could not load settings: " + e.message));
        }
    }

    return { init, saveProgram };
})();
