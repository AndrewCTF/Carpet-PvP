/* ═══════════════════════════════════════════════════════════════
   Program Panel — new, open, save, run, stop: the program on the
   canvas and the copy of it the server keeps. Saving happens by
   itself (autosave.js); this file does the save, says in the toolbar
   where the program stands, and looks after the copy kept in the
   browser while the server does not have it.
   ═══════════════════════════════════════════════════════════════ */
const ProgramPanel = (() => {

    /** A save sent while the page is being left may not be larger than this, or the browser drops it. */
    const LEAVING_LIMIT = 60000;

    let currentProgramId = null;    // the saved program being edited, or null for one not saved yet
    let savedGraph = null;          // the graph as the server has it, to tell changes by
    let savedName = "Untitled";
    let savedAt = null;             // the server's updatedAt of the copy this page started from
    let savedFile = null;           // where the server keeps it
    let draftReason = null;         // why the saved copy does not run yet
    let listed = { programs: [], presets: [], folder: "" };
    let autosave = null;
    let leaving = false;
    let force = false;              // the next save goes over a newer copy on the server
    let keptLocally = true;         // whether the browser could keep the unsaved work
    let replacing = false;          // the canvas is being given another program, which is not a change to this one
    // What stands for a program that has no id yet among the copies the browser keeps.
    const unsavedKey = "new-" + Date.now().toString(36);

    function $(id) { return document.getElementById(id); }

    // ── Saved or not ─────────────────────────────────────────

    /**
     * What the toolbar says about the program on the canvas, and in which tone.
     * @param state saved: the server has it; changed: it differs from what the server has; hasWork: there is
     *              something on the canvas to lose; saving; failed: why the last save did not go through;
     *              offline: ... because the server could not be reached; conflict: ... because somebody else
     *              saved it since; readOnly: the server takes no writes (viewer mode); draft: why the saved copy
     *              does not run yet; file: where the server keeps it; savedAt: when the server last had it;
     *              autosave: changes are saved without being asked
     * @param time how a moment is written, given its milliseconds
     */
    function saveStatus(state, time) {
        if (state.saving) {
            return { text: "Saving", tone: "quiet", title: "Sending the program to the server" };
        }
        if (state.conflict) {
            return { text: "Changed elsewhere", tone: "bad", title: "This program has been saved somewhere else since it was opened here. Save asks which copy to keep." };
        }
        if (state.offline && state.changed) {
            return { text: "Kept in this browser", tone: "warn", title: "The server cannot be reached. Your work is kept in this browser and saved as soon as the server answers." };
        }
        if (state.failed) {
            return { text: "Save failed", tone: "bad", title: "Not saved: " + state.failed + ". " + (state.autosave ? "It is tried again by itself." : "Save tries again.") };
        }
        if (state.readOnly && state.changed && state.hasWork) {
            return { text: "Viewer mode: not saved", tone: "warn", title: "The editor is in viewer mode, so the server saves nothing. Your work is kept in this browser." };
        }
        if (state.saved && !state.changed) {
            const text = (state.autosave ? "Autosaved" : "Saved") + (state.savedAt ? " " + time(state.savedAt) : "");
            const where = state.file ? " It is in " + state.file : "";
            if (state.draft) {
                return { text: text + ", does not run yet", tone: "warn", title: "Saved as a draft: " + state.draft + "." + where };
            }
            return state.autosave
                ? { text: text, tone: "ok", title: "Changes are saved as they are made." + where }
                : { text: text, tone: "ok", title: "The server has this program as it is on the canvas." + where };
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

    function init() {
        autosave = Autosave.create({
            save: send,
            setTimer: (callback, millis) => setTimeout(callback, millis),
            clearTimer: (timer) => clearTimeout(timer),
            changed: showState,
            failed: (reason) => {
                if (!autosave.state.conflict) log("Saving failed: " + reason + ". The work is kept in this browser and saving is tried again.", "error");
            }
        });
        autosave.setEnabled(Autosave.readEnabled(localStorage));

        $("btn-new").addEventListener("click", newProgram);
        $("btn-load").addEventListener("click", () => openLoadModal());
        $("btn-save").addEventListener("click", saveProgram);
        $("btn-autosave").addEventListener("click", () => {
            const on = !autosave.state.enabled;
            Autosave.writeEnabled(localStorage, on);
            autosave.setEnabled(on);
            log(on ? "Autosave is on: the program is saved a moment after every change" : "Autosave is off: the program is saved when you press Save");
        });
        $("btn-undo").addEventListener("click", () => NodeEditor.undo());
        $("btn-redo").addEventListener("click", () => NodeEditor.redo());
        $("btn-clear").addEventListener("click", () => {
            if (!NodeEditor.isReady()) return;
            NodeEditor.clearGraph();
            log("Canvas cleared (Undo brings it back)");
        });
        $("exec-run-btn").addEventListener("click", runProgram);
        $("btn-stop").addEventListener("click", stopProgram);
        $("program-name").addEventListener("input", noteChange);
        NodeEditor.onChange(noteChange);

        document.querySelectorAll("#load-modal .modal-tab").forEach(tab => {
            tab.addEventListener("click", () => showTab(tab.dataset.tab));
        });
        $("load-filter").addEventListener("input", renderLists);
        $("load-filter").addEventListener("keydown", (e) => {
            if (e.key !== "Enter") return;
            const first = document.querySelector("#load-modal .modal-tab-panel.active .program-list-item button");
            if (first) first.click();
        });
        $("load-refresh").addEventListener("click", () => openLoadModal(document.querySelector("#load-modal .modal-tab.active").dataset.tab));
        $("conflict-mine").addEventListener("click", () => settleConflict("mine"));
        $("conflict-theirs").addEventListener("click", () => settleConflict("theirs"));
        $("conflict-copy").addEventListener("click", () => settleConflict("copy"));
        $("recovery-restore").addEventListener("click", () => recover(true));
        $("recovery-discard").addEventListener("click", () => recover(false));

        // A page that is being left cannot wait for an answer: the save is sent on its way, and the copy the
        // browser keeps is what is offered back should it not arrive.
        const leave = () => {
            leaving = true;
            autosave.flush();
            leaving = false;
        };
        window.addEventListener("pagehide", leave);
        document.addEventListener("visibilitychange", () => { if (document.visibilityState === "hidden") leave(); });
        // Asked only when leaving would lose something: with autosave off, or with nowhere to keep the work.
        window.addEventListener("beforeunload", (e) => {
            if (hasUnsavedWork() && (!autosave.state.enabled || !keptLocally)) e.preventDefault();
        });
        showState();
    }

    function programName() {
        return $("program-name").value.trim() || "Untitled";
    }

    function graphText() {
        return JSON.stringify(NodeEditor.getGraphJSON());
    }

    /**
     * Takes what is on the canvas as what the server has.
     * @param program the server's copy, or nothing for a program it does not have
     */
    function remember(program) {
        savedGraph = NodeEditor.isReady() ? graphText() : null;
        savedName = programName();
        savedAt = program && program.updatedAt ? program.updatedAt : null;
        savedFile = program && program.file ? program.file : null;
        draftReason = program && program.error ? program.error : null;
        force = false;
        if (autosave) autosave.settled();
        showState();
    }

    // Puts another program on the canvas. What the editor reports while it does is the old program going and
    // the new one arriving, neither of which is somebody's work to be saved.
    function replace(put) {
        replacing = true;
        try {
            put();
        } finally {
            replacing = false;
        }
    }

    function changedSinceSaved() {
        return NodeEditor.isReady() && (graphText() !== savedGraph || programName() !== savedName);
    }

    /** Whether there is something here the server does not have: a program that has nodes or a name, and differs from what was saved. */
    function hasUnsavedWork() {
        return NodeEditor.isReady() && changedSinceSaved() && (currentProgramId !== null || !NodeEditor.isEmpty() || programName() !== "Untitled");
    }

    // Every change of the graph or the name comes through here: the browser keeps a copy, and saving is set off.
    function noteChange() {
        if (!NodeEditor.isReady() || replacing) return;
        if (hasUnsavedWork()) {
            keptLocally = Autosave.keep(localStorage, {
                key: currentProgramId || unsavedKey, id: currentProgramId, name: programName(),
                graph: NodeEditor.getGraphJSON(), baseUpdatedAt: savedAt, at: Date.now()
            });
            autosave.changed();
        } else {
            showState();
        }
    }

    function showState() {
        if (!autosave) return;
        const state = autosave.state;
        const status = saveStatus({
            saved: Boolean(currentProgramId), changed: changedSinceSaved(), hasWork: hasUnsavedWork(),
            saving: Boolean(state.saving), failed: state.failed, offline: state.offline, conflict: state.conflict,
            readOnly: !state.writable, draft: draftReason, file: savedFile, savedAt: savedAt, autosave: state.enabled
        }, (at) => new Date(at).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" }));
        const chip = $("program-state");
        chip.textContent = status.text;
        chip.title = status.title;
        chip.className = "chip " + status.tone;
        const toggle = $("btn-autosave");
        toggle.setAttribute("aria-checked", String(state.enabled));
        toggle.title = state.enabled ? "Autosave is on: the program is saved a moment after every change. Click to turn it off."
            : "Autosave is off. Click to have the program saved a moment after every change.";
    }

    function mayDiscard() {
        // With autosave on and working, what is on the canvas is saved before it is left behind.
        if (autosave.state.enabled && autosave.state.writable && !autosave.state.failed) return true;
        return !hasUnsavedWork() || confirm("“" + programName() + "” has changes that are not saved. Discard them?");
    }

    // ── Save ─────────────────────────────────────────────────

    /**
     * Sends the program to the server, whether or not it compiles: one that does not is kept there as a draft,
     * with the reason. Answers what the autosave needs to know: { ok } or { ok: false, reason, conflict, offline }.
     */
    async function send() {
        const name = programName();
        const graph = NodeEditor.getGraphJSON();
        const text = JSON.stringify(graph);
        const program = { name, graphData: graph, actions: [] };
        try {
            program.actions = NodeCompiler.compileStrictly(NodeEditor.getGraph());
        } catch (e) {
            program.error = e.message;
        }
        const key = currentProgramId || unsavedKey;
        if (currentProgramId) {
            program.id = currentProgramId;
            if (savedAt) program.baseUpdatedAt = savedAt;
        }
        if (force) program.force = true;
        if (leaving && text.length > LEAVING_LIMIT) {
            return { ok: false, reason: "the program is too large to send while the page closes", offline: true };
        }
        let answer;
        try {
            answer = await API.saveProgram(program, { keepalive: leaving });
        } catch (e) {
            return { ok: false, reason: "the server cannot be reached", offline: true };
        }
        if (!answer.ok) {
            if (answer.status === 409 && answer.body.conflict) return { ok: false, reason: answer.body.error, conflict: true };
            return { ok: false, reason: answer.body.error || "the server answered " + answer.status };
        }
        const saved = answer.body;
        const first = currentProgramId === null;
        currentProgramId = saved.id;
        savedGraph = text;
        savedAt = saved.updatedAt;
        savedFile = saved.file;
        draftReason = saved.draft ? saved.reason : null;
        force = false;
        // The server has the last word on the name: it keeps names apart, and names a program that has none.
        if (saved.name !== name && programName() === name) {
            $("program-name").value = saved.name;
            log("The name “" + name + "” was taken, so the program is called “" + saved.name + "”");
        }
        savedName = saved.name;
        Autosave.forget(localStorage, key);
        if (first) log("Saved “" + saved.name + "” to " + saved.file + (saved.draft ? " as a draft: " + saved.reason : ""));
        return { ok: true, file: saved.file, draft: saved.draft, reason: saved.reason };
    }

    /** The Save button and Ctrl+S: saves now, and says where the file went. */
    async function saveProgram() {
        if (!NodeEditor.isReady()) return;
        if (autosave.state.conflict) {
            askWhichCopy(autosave.state.failed);
            return;
        }
        if (!autosave.state.writable) {
            log("The editor is in viewer mode (carpetLogicViewerMode), so the server saves nothing. The work is kept in this browser.", "error");
            return;
        }
        const had = currentProgramId !== null;
        const result = await autosave.saveNow();
        if (result.ok) {
            if (had) log("Saved to " + result.file + (result.draft ? " as a draft, it does not run yet: " + result.reason : ""));
        } else if (result.conflict) {
            askWhichCopy(result.reason);
        } else {
            log("Save failed: " + result.reason, "error");
        }
    }

    function askWhichCopy(reason) {
        $("conflict-text").textContent = reason + ".";
        Modal.open("conflict-modal");
    }

    // Two editors had the same program open and the other one saved first. Which copy stays is its owner's call.
    async function settleConflict(choice) {
        Modal.close("conflict-modal");
        if (choice === "theirs") {
            try {
                const theirs = (await API.getPrograms()).find(program => program.id === currentProgramId);
                if (theirs) {
                    Autosave.forget(localStorage, currentProgramId);
                    show(theirs);
                    log("Opened the copy the server has of “" + theirs.name + "”");
                } else {
                    log("The server no longer has this program. Save keeps yours.", "error");
                }
            } catch (e) {
                log("Could not read the server's copy: " + e.message, "error");
            }
            return;
        }
        if (choice === "copy") {
            currentProgramId = null;
            savedAt = null;
            $("program-name").value = (programName() + " (my copy)").slice(0, 64);
        } else {
            force = true;
        }
        const result = await autosave.saveNow();
        if (!result.ok) log("Save failed: " + result.reason, "error");
        else if (choice === "mine") log("Saved yours over the other copy, to " + result.file);
    }

    // ── New ──────────────────────────────────────────────────
    async function newProgram() {
        if (!NodeEditor.isReady() || !mayDiscard()) return;
        await autosave.flush();
        replace(() => {
            NodeEditor.clearGraph();
            NodeEditor.resetHistory();
        });
        $("program-name").value = "Untitled";
        currentProgramId = null;
        remember(null);
        log("New program");
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
            // The server reads its folder again for this, so a file put there by hand is in the list.
            const [programs, presets, status] = await Promise.all([API.getPrograms(), API.getPresets(), API.getStatus()]);
            programs.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
            listed = { programs, presets, folder: status.programsFolder || "" };
            renderLists();
            showTab(tab || (programs.length === 0 ? "presets" : "programs"));
            $("load-filter").focus();
        } catch (e) {
            for (const id of ["load-program-list", "preset-program-list"]) {
                $(id).replaceChildren(el("p", "list-state error", "The programs could not be read: " + e.message));
            }
        }
    }

    /** The folders the saved programs are in, the programs folder itself first. */
    function folders(programs) {
        return [...new Set(programs.map(program => program.folder || ""))].sort((a, b) => a.localeCompare(b));
    }

    function renderLists() {
        const query = $("load-filter").value.trim().toLowerCase();
        const fits = (program) => (program.name || program.id || "").toLowerCase().includes(query)
            || (program.description || "").toLowerCase().includes(query) || (program.folder || "").toLowerCase().includes(query);
        $("count-programs").textContent = listed.programs.length;
        $("count-presets").textContent = listed.presets.length;
        $("load-folder").textContent = listed.folder;

        const saved = listed.programs.filter(fits);
        const list = $("load-program-list");
        list.replaceChildren();
        if (saved.length === 0) {
            list.append(el("p", "list-state", query ? "No saved program matches." :
                "No saved programs yet. Build one on the canvas and it is saved here, or start from a preset."));
        }
        const all = folders(listed.programs);
        for (const folder of folders(saved)) {
            if (all.length > 1 || folder !== "") {
                const heading = el("h3", "folder-heading");
                heading.append(el("span", "folder-name", folder === "" ? "Programs folder" : folder),
                    el("span", "count", saved.filter(program => (program.folder || "") === folder).length));
                list.append(heading);
            }
            for (const program of saved.filter(program => (program.folder || "") === folder)) list.append(row(program, all));
        }

        const presets = $("preset-program-list");
        presets.replaceChildren();
        if (listed.presets.filter(fits).length === 0) presets.append(el("p", "list-state", query ? "No preset matches." : "This server has no presets."));
        for (const preset of listed.presets.filter(fits)) presets.append(row(preset, null));
    }

    function row(program, allFolders) {
        const info = el("div", "program-info");
        info.append(el("span", "program-name", program.name || program.id));
        if (program.draft) info.append(el("span", "program-draft", "Does not run yet: " + program.error));
        info.append(el("span", "program-meta", meta(program)));

        const actions = el("div", "program-actions");
        const open = el("button", "btn", "Open");
        open.addEventListener("click", () => loadProgram(program));
        actions.append(open);
        if (allFolders) actions.append(moveControl(program, allFolders), deleteButton(program));

        const item = el("div", "program-list-item" + (program.id === currentProgramId ? " current" : ""));
        item.append(info, actions);
        return item;
    }

    function meta(program) {
        if (program.isPreset) return program.description || "";
        const file = (program.file || "").split("/").pop();
        const saved = program.updatedAt ? "saved " + new Date(program.updatedAt).toLocaleString() : "put there by hand";
        return file + " · " + saved;
    }

    // Moving a program to a subfolder of the programs folder: one that is there, or a new one.
    function moveControl(program, allFolders) {
        const box = el("span", "move-control");
        const select = el("select", "field");
        select.setAttribute("aria-label", "Move " + program.name + " to a folder");
        select.append(new Option("Move to", ""));
        if ((program.folder || "") !== "") select.append(new Option("Programs folder", "/"));
        for (const folder of allFolders) {
            if (folder !== "" && folder !== program.folder) select.append(new Option(folder, folder));
        }
        select.append(new Option("New folder", "+"));
        select.addEventListener("change", () => {
            if (select.value === "+") {
                const name = el("input", "field");
                name.type = "text";
                name.maxLength = 32;
                name.placeholder = "Folder name";
                name.setAttribute("aria-label", "Name of the new folder");
                const go = el("button", "btn", "Move");
                const moveNew = () => { if (name.value.trim()) move(program, name.value.trim()); };
                go.addEventListener("click", moveNew);
                name.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.stopPropagation(); moveNew(); } });
                box.replaceChildren(name, go);
                name.focus();
            } else if (select.value) {
                move(program, select.value === "/" ? "" : select.value);
            }
        });
        box.append(select);
        return box;
    }

    async function move(program, folder) {
        try {
            const moved = await API.moveProgram(program.id, folder);
            program.folder = moved.folder;
            program.file = moved.file;
            if (program.id === currentProgramId) savedFile = moved.file;
            log("Moved “" + program.name + "” to " + moved.file);
            renderLists();
            showState();
        } catch (e) {
            log("Could not move " + program.name + ": " + e.message, "error");
        }
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

    // Puts a program on the canvas. A saved program reopens as the graph it was saved from, draft or not. A
    // program that has no graph, as the presets do, gets one built from its actions.
    function show(program) {
        replace(() => {
            if (program.graphData) {
                NodeEditor.loadGraphJSON(program.graphData);
            } else {
                NodeEditor.loadActions(program.actions || []);
            }
        });
        $("program-name").value = program.name || "Untitled";
        currentProgramId = program.isPreset ? null : program.id;
        remember(program.isPreset ? null : program);
    }

    // A preset opens as a copy: it is saved as a program of its own as soon as it is changed.
    async function loadProgram(program) {
        if (!NodeEditor.isReady() || !mayDiscard()) return;
        await autosave.flush();
        try {
            show(program);
            Modal.close("load-modal");
            log("Opened: " + (program.name || program.id) + (program.isPreset ? " (a copy of the preset: changing it saves it as your own)"
                : program.draft ? " (a draft, it does not run yet: " + program.error + ")" : ""));
        } catch (e) {
            log("Could not open " + (program.name || program.id) + ": " + e.message, "error");
        }
    }

    async function deleteProgram(program) {
        try {
            await API.deleteProgram(program.id);
            if (currentProgramId === program.id) {
                currentProgramId = null;
                remember(null);
            }
            Autosave.forget(localStorage, program.id);
            log("Deleted: " + (program.name || program.id));
            listed.programs = listed.programs.filter(other => other.id !== program.id);
            renderLists();
        } catch (e) {
            log("Delete failed: " + e.message, "error");
        }
    }

    // ── Work the browser kept ────────────────────────────────

    /**
     * The copies that are worth offering back: not the one of the program that is open, and not one the server
     * turns out to have after all, which happens when the save sent on leaving the page did arrive.
     */
    function worthOffering(copies, programs, openKey) {
        // The server does not keep the members of a graph that are null, so they do not count as a difference.
        const text = (graph) => JSON.stringify(graph, (key, value) => value === null ? undefined : value);
        return copies.filter(copy => {
            if (copy.key === openKey) return false;
            const theirs = programs.find(program => program.id === copy.id);
            return !(theirs && theirs.name === copy.name && text(theirs.graphData) === text(copy.graph));
        });
    }

    /** Offers back the newest unsaved work this browser holds from an earlier visit, if there is any. */
    async function offerKept() {
        let copies = Autosave.kept(localStorage);
        if (copies.length > 0) {
            try {
                const worth = worthOffering(copies, await API.getPrograms(), currentProgramId || unsavedKey);
                copies.filter(copy => !worth.includes(copy) && copy.key !== (currentProgramId || unsavedKey))
                    .forEach(copy => Autosave.forget(localStorage, copy.key));
                copies = worth;
            } catch (e) {
                copies = copies.filter(copy => copy.key !== (currentProgramId || unsavedKey));
            }
        }
        const copy = copies[0];
        $("recovery-bar").classList.toggle("hidden", !copy);
        if (!copy) return;
        $("recovery-bar").dataset.key = copy.key;
        $("recovery-text").textContent = "Unsaved work on “" + copy.name + "” from "
            + new Date(copy.at).toLocaleString() + " is kept in this browser.";
    }

    async function recover(restore) {
        const key = $("recovery-bar").dataset.key;
        const copy = Autosave.kept(localStorage).find(other => other.key === key);
        if (copy && restore) {
            if (!mayDiscard()) return;
            await autosave.flush();
            replace(() => NodeEditor.loadGraphJSON(copy.graph));
            $("program-name").value = copy.name;
            currentProgramId = copy.id || null;
            // What the server has is not known to be this: it is saved, and a newer copy there is asked about.
            savedGraph = null;
            savedAt = copy.baseUpdatedAt || null;
            savedFile = null;
            draftReason = null;
            autosave.settled();
            Autosave.forget(localStorage, key);
            noteChange();
            log("Restored the unsaved work on “" + copy.name + "”");
        } else {
            Autosave.forget(localStorage, key);
        }
        offerKept();
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
        let actions;
        try {
            actions = NodeCompiler.compileStrictly(NodeEditor.getGraph());
        } catch (e) {
            log("“" + programName() + "” does not run yet: " + e.message, "error");
            return;
        }
        // What runs is what is saved: the program is saved first where saving by itself is on.
        await autosave.flush();
        try {
            await API.runProgram(bot, programName(), actions);
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

    /** Whether the server takes writes from this page; it does not in viewer mode. */
    function setWritable(on) {
        if (autosave) autosave.setWritable(on);
    }

    return { init, saveProgram, runProgram, stopProgram, openLoadModal, loadProgram, remember, saveStatus, folders, offerKept, worthOffering, setWritable };
})();

if (typeof module !== "undefined") module.exports = ProgramPanel;
