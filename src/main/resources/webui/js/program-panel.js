/* ═══════════════════════════════════════════════════════════════
   Program Panel — new, open, save, run, stop, and whether what is
   on the canvas has been saved
   ═══════════════════════════════════════════════════════════════ */
const ProgramPanel = (() => {

    let currentProgramId = null;    // the saved program being edited, or null for one not saved yet
    let savedGraph = null;          // the graph as it was last saved or opened, to tell changes by
    let savedName = "Untitled";
    let savedAt = null;             // when the server last had it, in milliseconds
    let saving = false;
    let saveError = null;           // why the last save did not go through
    let listed = { programs: [], presets: [] };

    // ── Saved or not ─────────────────────────────────────────

    /**
     * What the toolbar says about the program on the canvas, and in which tone.
     * @param state saved: the server has it; changed: it differs from what the server has; hasWork: there is
     *              something on the canvas to lose; saving; failed: why the last save did not go through;
     *              savedAt: when the server last had it; autosave: changes are saved without being asked
     * @param time how a moment is written, given its milliseconds
     */
    function saveStatus(state, time) {
        if (state.saving) {
            return { text: "Saving", tone: "quiet", title: "Sending the program to the server" };
        }
        if (state.failed) {
            return { text: "Save failed", tone: "bad", title: "Not saved: " + state.failed + ". Save tries again." };
        }
        if (state.saved && !state.changed) {
            const when = state.savedAt ? " " + time(state.savedAt) : "";
            return state.autosave
                ? { text: "Autosaved" + when, tone: "ok", title: "Changes are saved as they are made" }
                : { text: "Saved" + when, tone: "ok", title: "The server has this program as it is on the canvas" };
        }
        if (state.changed && (state.saved || state.hasWork)) {
            return {
                text: state.saved ? "Unsaved changes" : "Not saved",
                tone: "warn",
                title: state.autosave ? "Changes are saved in a moment" : "Save (Ctrl+S) keeps what is on the canvas"
            };
        }
        return { text: "Not saved", tone: "quiet", title: "Nothing to save yet" };
    }

    function $(id) { return document.getElementById(id); }

    function init() {
        $("btn-new").addEventListener("click", newProgram);
        $("btn-load").addEventListener("click", () => openLoadModal());
        $("btn-save").addEventListener("click", saveProgram);
        $("btn-undo").addEventListener("click", () => NodeEditor.undo());
        $("btn-redo").addEventListener("click", () => NodeEditor.redo());
        $("btn-clear").addEventListener("click", () => {
            if (!NodeEditor.isReady()) return;
            NodeEditor.clearGraph();
            log("Canvas cleared (Undo brings it back)");
        });
        $("exec-run-btn").addEventListener("click", runProgram);
        $("btn-stop").addEventListener("click", stopProgram);
        $("program-name").addEventListener("input", showState);
        NodeEditor.onChange(showState);

        document.querySelectorAll("#load-modal .modal-tab").forEach(tab => {
            tab.addEventListener("click", () => showTab(tab.dataset.tab));
        });
        $("load-filter").addEventListener("input", renderLists);
        $("load-filter").addEventListener("keydown", (e) => {
            if (e.key !== "Enter") return;
            const first = document.querySelector("#load-modal .modal-tab-panel.active .program-list-item button");
            if (first) first.click();
        });
        // Leaving the page with work that was never saved asks first.
        window.addEventListener("beforeunload", (e) => {
            if (hasUnsavedWork()) e.preventDefault();
        });
    }

    function programName() {
        return $("program-name").value.trim() || "Untitled";
    }

    function graphText() {
        return JSON.stringify(NodeEditor.getGraphJSON());
    }

    /**
     * Takes what is on the canvas as what the server has.
     * @param at when the server got it, or nothing for a program it does not have
     */
    function remember(at) {
        savedGraph = NodeEditor.isReady() ? graphText() : null;
        savedName = programName();
        savedAt = at || null;
        saveError = null;
        showState();
    }

    function changedSinceSaved() {
        return NodeEditor.isReady() && (graphText() !== savedGraph || programName() !== savedName);
    }

    /** Whether closing the page now would lose something: a program that has nodes and differs from what was saved. */
    function hasUnsavedWork() {
        return NodeEditor.isReady() && !NodeEditor.isEmpty() && changedSinceSaved();
    }

    function showState() {
        const status = saveStatus({
            saved: Boolean(currentProgramId), changed: changedSinceSaved(), hasWork: hasUnsavedWork(),
            saving: saving, failed: saveError, savedAt: savedAt, autosave: false
        }, (at) => new Date(at).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" }));
        const chip = $("program-state");
        chip.textContent = status.text;
        chip.title = status.title;
        chip.className = "chip " + status.tone;
    }

    function mayDiscard() {
        return !hasUnsavedWork() || confirm("“" + programName() + "” has changes that are not saved. Discard them?");
    }

    // ── New / Save ───────────────────────────────────────────
    function newProgram() {
        if (!NodeEditor.isReady() || !mayDiscard()) return;
        NodeEditor.clearGraph();
        NodeEditor.resetHistory();
        $("program-name").value = "Untitled";
        currentProgramId = null;
        remember();
        log("New program");
    }

    async function saveProgram() {
        if (!NodeEditor.isReady() || saving) return;
        saving = true;
        saveError = null;
        showState();
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
            saving = false;
            remember(Date.now());
            log("Saved: " + program.name);
        } catch (e) {
            saving = false;
            saveError = e.message;
            showState();
            log("Save failed: " + e.message, "error");
        }
    }

    // ── Open ─────────────────────────────────────────────────
    function showTab(name) {
        document.querySelectorAll("#load-modal .modal-tab").forEach(tab => {
            tab.classList.toggle("active", tab.dataset.tab === name);
            tab.setAttribute("aria-selected", String(tab.dataset.tab === name));
        });
        document.querySelectorAll("#load-modal .modal-tab-panel").forEach(panel => {
            panel.classList.toggle("active", panel.id === "panel-" + name);
        });
    }

    /** Opens the program list, on its saved programs or, for somebody who has none, on the presets. */
    async function openLoadModal(tab) {
        Modal.open("load-modal");
        $("load-filter").value = "";
        for (const id of ["load-program-list", "preset-program-list"]) {
            $(id).replaceChildren(el("p", "list-state", "Reading the programs from the server"));
        }
        try {
            const [programs, presets] = await Promise.all([API.getPrograms(), API.getPresets()]);
            programs.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
            listed = { programs, presets };
            renderLists();
            showTab(tab || (programs.length === 0 ? "presets" : "programs"));
            $("load-filter").focus();
        } catch (e) {
            for (const id of ["load-program-list", "preset-program-list"]) {
                $(id).replaceChildren(el("p", "list-state error", "The programs could not be read: " + e.message));
            }
        }
    }

    function renderLists() {
        const query = $("load-filter").value.trim().toLowerCase();
        const fits = (program) => (program.name || program.id || "").toLowerCase().includes(query)
            || (program.description || "").toLowerCase().includes(query);
        $("count-programs").textContent = listed.programs.length;
        $("count-presets").textContent = listed.presets.length;
        renderList("load-program-list", listed.programs.filter(fits), query ? "No saved program matches." :
            "No saved programs yet. Build one on the canvas and press Save, or start from a preset.", true);
        renderList("preset-program-list", listed.presets.filter(fits), query ? "No preset matches." : "This server has no presets.", false);
    }

    function renderList(listId, programs, emptyText, deletable) {
        const list = $(listId);
        list.replaceChildren();
        if (programs.length === 0) list.append(el("p", "list-state", emptyText));
        for (const program of programs) {
            const info = el("div", "program-info");
            info.append(
                el("span", "program-name", program.name || program.id),
                el("span", "program-meta", meta(program)));

            const actions = el("div", "program-actions");
            const open = el("button", "btn", "Open");
            open.addEventListener("click", () => loadProgram(program));
            actions.append(open);
            if (deletable) actions.append(deleteButton(program));

            const item = el("div", "program-list-item" + (program.id === currentProgramId ? " current" : ""));
            item.append(info, actions);
            list.append(item);
        }
    }

    function meta(program) {
        const size = (program.actions || []).length + ((program.actions || []).length === 1 ? " top-level action" : " top-level actions");
        if (program.description) return program.description;
        return program.updatedAt ? size + " · saved " + new Date(program.updatedAt).toLocaleString() : size;
    }

    // Deleting asks on the button itself: the second press does it, and looking away takes the question back.
    function deleteButton(program) {
        const button = el("button", "btn", "Delete");
        let asked = false;
        button.addEventListener("click", () => {
            if (asked) {
                deleteProgram(program);
                return;
            }
            asked = true;
            button.textContent = "Delete for good?";
            button.classList.add("stop");
        });
        button.addEventListener("blur", () => {
            asked = false;
            button.textContent = "Delete";
            button.classList.remove("stop");
        });
        return button;
    }

    // A saved program reopens as the graph it was saved from. A program that has no graph, as the presets
    // do, gets one built from its actions. A preset opens as a copy: saving it creates a new program.
    function loadProgram(program) {
        if (!NodeEditor.isReady() || !mayDiscard()) return;
        try {
            if (program.graphData) {
                NodeEditor.loadGraphJSON(program.graphData);
            } else {
                NodeEditor.loadActions(program.actions || []);
            }
            $("program-name").value = program.name || "Untitled";
            currentProgramId = program.isPreset ? null : program.id;
            remember(program.isPreset ? null : program.updatedAt);
            Modal.close("load-modal");
            log("Opened: " + (program.name || program.id) + (program.isPreset ? " (a copy of the preset: Save keeps it as your own)" : ""));
        } catch (e) {
            log("Could not open " + (program.name || program.id) + ": " + e.message, "error");
        }
    }

    async function deleteProgram(program) {
        try {
            await API.deleteProgram(program.id);
            if (currentProgramId === program.id) {
                currentProgramId = null;
                showState();
            }
            log("Deleted: " + (program.name || program.id));
            listed.programs = listed.programs.filter(other => other.id !== program.id);
            renderLists();
        } catch (e) {
            log("Delete failed: " + e.message, "error");
        }
    }

    // ── Run / Stop ───────────────────────────────────────────
    async function runProgram() {
        if (!NodeEditor.isReady()) return;
        const bot = BotPanel.getTargetBot();
        if (!bot) {
            // Nothing to run on: the place to get a bot is shown, and the choice is pointed at.
            Docks.bots(true);
            $("target-bot-select").focus();
            log("Choose the bot to run on first. The Bots panel spawns one.", "error");
            return;
        }
        try {
            await API.runProgram(bot, programName(), NodeCompiler.compile(NodeEditor.getGraph()));
            log("Started “" + programName() + "” on " + bot);
        } catch (e) {
            log("Run failed: " + e.message, "error");
        }
    }

    async function stopProgram() {
        const bot = BotPanel.getTargetBot();
        if (!bot) return;
        try {
            const result = await API.stopProgram(bot);
            log(result.success ? "Stopped the program of " + bot : bot + " was not running a program");
        } catch (e) {
            log("Stop failed: " + e.message, "error");
        }
    }

    return { init, saveProgram, runProgram, stopProgram, openLoadModal, loadProgram, remember, saveStatus };
})();

if (typeof module !== "undefined") module.exports = ProgramPanel;
