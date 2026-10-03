/* ═══════════════════════════════════════════════════════════════
   Bot Panel — Spawn, kill, list, status
   ═══════════════════════════════════════════════════════════════ */
const BotPanel = (() => {

    let selectedBot = null;

    function init() {
        document.getElementById("spawn-bot-btn").addEventListener("click", spawnBot);
        document.getElementById("kill-bot-btn").addEventListener("click", killSelectedBot);

        // Listen for SSE updates
        API.on("botUpdate", refreshBotList);
        API.on("connectionChange", (connected) => {
            if (connected) refreshBotList();
        });

        console.log("[BotPanel] Initialised");
    }

    async function spawnBot() {
        const name = document.getElementById("bot-name-input").value.trim();
        if (!name) return;
        try {
            await API.spawnBot(name);
            log("Spawned bot: " + name);
        } catch (e) {
            log("Failed to spawn: " + e.message, "error");
        }
    }

    async function killSelectedBot() {
        const name = document.getElementById("bot-name-input").value.trim();
        if (!name) return;
        try {
            await API.killBot(name);
            log("Killed bot: " + name);
        } catch (e) {
            log("Failed to kill: " + e.message, "error");
        }
    }

    async function refreshBotList() {
        try {
            const data = await API.getBots();
            const bots = normalizeBots(data);
            renderBotList(bots);
            updateTargetSelect(bots);
            if (selectedBot) updateBotStatus(bots.find(b => b.name === selectedBot));
        } catch (e) { /* ignore */ }
    }

    function normalizeBots(data) {
        if (Array.isArray(data)) {
            return data.map((b) => b && b.name ? b : { ...(b || {}), name: b?.id || b?.botName || b?.uuid || "" })
                       .filter(b => b.name);
        }
        if (data && typeof data === "object") {
            if (Array.isArray(data.bots)) return data.bots;
            // Map<String,BotState> -> add name from key if missing
            return Object.entries(data).map(([name, bot]) => ({ name, ...(bot || {}) }))
                         .filter(b => b.name);
        }
        return [];
    }

    function renderBotList(bots) {
        const list = document.getElementById("bot-list");
        if (bots.length === 0) {
            list.innerHTML = '<div class="empty-hint">No active bots</div>';
            return;
        }
        list.innerHTML = bots.map(b => {
            const sel = b.name === selectedBot ? " selected" : "";
            const running = Boolean(b.running) || b.programState === "RUNNING";
            const st = running ? "running" : "idle";
            return `<div class="bot-list-item${sel}" data-name="${b.name}">
                <div class="bot-info">
                    <span class="conn-dot ${running ? 'connected' : 'disconnected'}" style="width:6px;height:6px;"></span>
                    <span class="bot-name">${b.name}</span>
                </div>
                <span class="bot-status-badge ${st}">${st}</span>
            </div>`;
        }).join("");

        list.querySelectorAll(".bot-list-item").forEach(el => {
            el.addEventListener("click", () => {
                selectedBot = el.dataset.name;
                document.getElementById("bot-name-input").value = selectedBot;
                // Auto-select in target dropdown too
                const sel = document.getElementById("target-bot-select");
                if (sel) sel.value = selectedBot;
                refreshBotList();
            });
        });
    }

    function updateTargetSelect(bots) {
        const sel = document.getElementById("target-bot-select");
        const current = sel.value;
        sel.innerHTML = '<option value="">Select a bot…</option>' +
            bots.map(b => `<option value="${b.name}"${b.name === current ? " selected" : ""}>${b.name}</option>`).join("");
    }

    function updateBotStatus(bot) {
        const section = document.getElementById("bot-status-section");
        const content = document.getElementById("bot-status-content");
        if (!bot) {
            section.style.display = "none";
            return;
        }
        section.style.display = "";
        content.innerHTML = `
            <div class="status-grid">
                <div class="status-row"><span class="status-label">Name</span><span class="status-value">${bot.name}</span></div>
                <div class="status-row"><span class="status-label">Health</span><span class="status-value">${bot.health ?? "?"} / 20</span></div>
                <div class="status-row"><span class="status-label">Position</span><span class="status-value">${bot.x?.toFixed(0) ?? "?"}, ${bot.y?.toFixed(0) ?? "?"}, ${bot.z?.toFixed(0) ?? "?"}</span></div>
                <div class="status-row"><span class="status-label">Dimension</span><span class="status-value">${bot.dimension ?? "?"}</span></div>
                <div class="status-row"><span class="status-label">Program</span><span class="status-value ${(bot.running || bot.programState === 'RUNNING') ? 'status-running' : 'status-idle'}">${(bot.running || bot.programState === 'RUNNING') ? "Running" : "Idle"}</span></div>
            </div>`;
    }

    function getSelectedBot() { return selectedBot; }
    function getTargetBot() { return document.getElementById("target-bot-select").value; }

    return { init, refreshBotList, getSelectedBot, getTargetBot };
})();
