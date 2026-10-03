/* ═══════════════════════════════════════════════════════════════
   Program Panel — Save, Load, Presets, Execution
   ═══════════════════════════════════════════════════════════════ */
const ProgramPanel = (() => {

    let currentProgramId = null;

    function init() {
        // Save / Load / New
        document.getElementById("btn-save").addEventListener("click", saveProgram);
        document.getElementById("btn-load").addEventListener("click", () => openModal("load-modal"));
        document.getElementById("btn-new").addEventListener("click", newProgram);

        // Run / Stop (top bar)
        document.getElementById("btn-run").addEventListener("click", runProgram);
        document.getElementById("btn-stop").addEventListener("click", stopProgram);

        // Run / Stop (right panel)
        document.getElementById("exec-run-btn").addEventListener("click", runProgram);
        document.getElementById("exec-stop-btn").addEventListener("click", stopProgram);

        // Clear / Undo
        document.getElementById("btn-clear").addEventListener("click", () => {
            if (confirm("Clear the entire canvas?")) {
                NodeEditor.clearGraph();
                log("Canvas cleared");
            }
        });
        document.getElementById("btn-undo").addEventListener("click", () => {
            // LiteGraph doesn't have built-in undo, but we can try
            log("Undo not yet implemented");
        });

        // Presets dropdown
        loadPresets();

        // Load modal tabs
        document.querySelectorAll(".modal-tab").forEach(tab => {
            tab.addEventListener("click", () => {
                const panel = tab.dataset.tab;
                document.querySelectorAll(".modal-tab").forEach(t => t.classList.remove("active"));
                tab.classList.add("active");
                document.querySelectorAll(".modal-tab-panel").forEach(p => p.classList.remove("active"));
                document.getElementById("panel-" + panel).classList.add("active");
            });
        });

        // Modal close buttons
        document.querySelectorAll(".modal-close").forEach(btn => {
            btn.addEventListener("click", () => {
                btn.closest(".modal-overlay").classList.remove("active");
            });
        });
        document.querySelectorAll(".modal-overlay").forEach(overlay => {
            overlay.addEventListener("click", (e) => {
                if (e.target === overlay) overlay.classList.remove("active");
            });
        });

        // Settings
        document.getElementById("btn-settings").addEventListener("click", openSettings);

        // SSE - botUpdate carries program state too
        API.on("botUpdate", (data) => {
            // Check if our target bot has program status
            const bot = BotPanel.getTargetBot();
            if (bot && data.programs && data.programs[bot]) {
                const state = document.getElementById("exec-state");
                const ps = data.programs[bot];
                state.textContent = ps.running ? "Running" : "Idle";
                state.className = "rp-status-value" + (ps.running ? " status-running" : "");
            }
        });

        console.log("[ProgramPanel] Initialised");
    }

    // ── Save ─────────────────────────────────────────────────
    async function saveProgram() {
        try {
            const name = document.getElementById("program-name").value.trim() || "Untitled";
            const graphData = NodeEditor.getGraphJSON();
            const actions = NodeCompiler.compile(NodeEditor.getGraph());
            const program = {
                id: currentProgramId || name.toLowerCase().replace(/\s+/g, "_") + "_" + Date.now(),
                name: name,
                actions: actions,
                graphData: graphData
            };
            await API.saveProgram(program);
            currentProgramId = program.id;
            log("Saved: " + name);
        } catch (e) {
            log("Save failed: " + e.message, "error");
        }
    }

    // ── New ──────────────────────────────────────────────────
    function newProgram() {
        NodeEditor.clearGraph();
        document.getElementById("program-name").value = "Untitled";
        currentProgramId = null;
        log("New program created");
    }

    // ── Load modal ───────────────────────────────────────────
    async function loadPrograms() {
        try {
            const data = await API.getPrograms();
            const list = document.getElementById("load-program-list");
            // Server returns List directly (array) or {programs: [...]}
            const programs = Array.isArray(data) ? data : (data.programs || Object.values(data));
            if (!programs || programs.length === 0) {
                list.innerHTML = '<div class="empty-hint">No saved programs</div>';
                return;
            }
            list.innerHTML = programs.map(p => `
                <div class="program-list-item">
                    <div class="program-info">
                        <span class="program-name">${p.name || p.id}</span>
                        <span class="program-meta">${p.actions ? p.actions.length + " actions" : ""}</span>
                    </div>
                    <div class="program-actions">
                        <button class="btn-sm primary" onclick="ProgramPanel.loadProgram('${p.id}')">Load</button>
                        <button class="btn-sm red" onclick="ProgramPanel.deleteProgram('${p.id}')">Del</button>
                    </div>
                </div>
            `).join("");
        } catch (e) {
            log("Failed to load programs: " + e.message, "error");
        }
    }

    async function loadPresets() {
        try {
            const data = await API.getPresets();
            // Server returns List directly (array) or {presets: [...]}
            const presets = Array.isArray(data) ? data : (data.presets || Object.values(data));
            // Preset dropdown in top bar
            const dropList = document.getElementById("preset-list");
            dropList.innerHTML = presets.map(p =>
                `<div class="dd-item" data-id="${p.id}">${p.name || p.id}</div>`
            ).join("");
            dropList.querySelectorAll(".dd-item").forEach(item => {
                item.addEventListener("click", () => loadPresetById(item.dataset.id));
            });
            // Preset tab in modal
            const presetList = document.getElementById("preset-program-list");
            presetList.innerHTML = presets.map(p => `
                <div class="program-list-item">
                    <div class="program-info">
                        <span class="program-name">${p.name || p.id}</span>
                        <span class="program-meta">Built-in preset</span>
                    </div>
                    <div class="program-actions">
                        <button class="btn-sm primary" onclick="ProgramPanel.loadPresetById('${p.id}')">Load</button>
                    </div>
                </div>
            `).join("");
        } catch (e) { /* presets not available yet */ }
    }

    async function loadProgram(id) {
        try {
            const data = await API.getPrograms();
            const programs = Array.isArray(data) ? data : (data.programs || Object.values(data));
            const program = programs.find(p => p.id === id);
            if (!program) throw new Error("Program not found");
            if (program.graphData) {
                NodeEditor.loadGraphJSON(program.graphData);
            }
            document.getElementById("program-name").value = program.name || id;
            currentProgramId = id;
            closeAllModals();
            log("Loaded: " + (program.name || id));
        } catch (e) {
            log("Load failed: " + e.message, "error");
        }
    }

    async function loadPresetById(id) {
        try {
            const data = await API.getPresets();
            const presets = Array.isArray(data) ? data : (data.presets || Object.values(data));
            const preset = presets.find(p => p.id === id);
            if (!preset) throw new Error("Preset not found");
            // Presets don't have graphData, create a new graph
            NodeEditor.clearGraph();
            document.getElementById("program-name").value = preset.name || id;
            currentProgramId = null;
            closeAllModals();
            log("Loaded preset: " + (preset.name || id));
        } catch (e) {
            log("Preset load failed: " + e.message, "error");
        }
    }

    async function deleteProgram(id) {
        if (!confirm("Delete this program?")) return;
        try {
            await API.deleteProgram(id);
            loadPrograms();
            log("Deleted program");
        } catch (e) {
            log("Delete failed: " + e.message, "error");
        }
    }

    // ── Run / Stop ───────────────────────────────────────────
    async function runProgram() {
        const bot = BotPanel.getTargetBot();
        if (!bot) { log("Select a target bot first", "error"); return; }
        try {
            // Save program first so server has it, then execute by ID
            const name = document.getElementById("program-name").value.trim() || "Untitled";
            const graphData = NodeEditor.getGraphJSON();
            const actions = NodeCompiler.compile(NodeEditor.getGraph());
            const tempId = currentProgramId || "_run_" + Date.now();
            const program = {
                id: tempId,
                name: name,
                actions: actions,
                graphData: graphData
            };
            await API.saveProgram(program);
            await API.executeProgram(bot, tempId);
            document.getElementById("exec-state").textContent = "Running";
            document.getElementById("exec-state").className = "rp-status-value status-running";
            log("Running program on " + bot);
        } catch (e) {
            log("Run failed: " + e.message, "error");
        }
    }

    async function stopProgram() {
        const bot = BotPanel.getTargetBot();
        if (!bot) return;
        try {
            await API.stopProgram(bot);
            document.getElementById("exec-state").textContent = "Stopped";
            document.getElementById("exec-state").className = "rp-status-value";
            log("Stopped program on " + bot);
        } catch (e) {
            log("Stop failed: " + e.message, "error");
        }
    }

    // ── Settings ─────────────────────────────────────────────
    async function openSettings() {
        openModal("settings-modal");
        try {
            const data = await API.getSettings();
            const content = document.querySelector("#settings-modal .settings-content");
            // Server returns settings at root level, not wrapped
            const settings = data.settings || data;
            const entries = Object.entries(settings);
            if (entries.length === 0) {
                content.innerHTML = '<span class="rp-muted">No settings available</span>';
                return;
            }
            content.innerHTML = entries.map(([k, v]) =>
                `<div class="settings-row"><span class="settings-key">${k}</span><span class="settings-value">${v}</span></div>`
            ).join("");
        } catch (e) {
            document.querySelector("#settings-modal .settings-content").innerHTML =
                '<span class="rp-muted">Failed to load settings</span>';
        }
    }

    // ── Modals ───────────────────────────────────────────────
    function openModal(id) {
        document.getElementById(id).classList.add("active");
        if (id === "load-modal") {
            loadPrograms();
            loadPresets();
        }
    }

    function closeAllModals() {
        document.querySelectorAll(".modal-overlay").forEach(m => m.classList.remove("active"));
    }

    return {
        init, saveProgram, loadProgram, loadPresetById, deleteProgram,
        runProgram, stopProgram
    };
})();
