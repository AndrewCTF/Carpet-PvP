/* ═══════════════════════════════════════════════════════════════
   Bot Panel — spawn, remove, pick and watch bots
   ═══════════════════════════════════════════════════════════════ */
const BotPanel = (() => {

    let bots = {};          // name → bot state, as the server last sent it
    let programs = {};      // name → state of the program on that bot
    let targetBot = "";

    function init() {
        document.getElementById("spawn-bot-btn").addEventListener("click", spawnBot);
        document.getElementById("remove-bot-btn").addEventListener("click", removeBot);
        document.getElementById("target-bot-select").addEventListener("change", (e) => selectBot(e.target.value));
        API.on("botUpdate", (update) => {
            bots = update.bots || {};
            programs = update.programs || {};
            if (targetBot && !bots[targetBot]) targetBot = "";
            // A single bot is the obvious target.
            if (!targetBot && Object.keys(bots).length === 1) targetBot = Object.keys(bots)[0];
            render();
        });
    }

    async function spawnBot() {
        const name = document.getElementById("bot-name-input").value.trim();
        if (!name) return;
        try {
            // No position is sent: the server puts the bot where the player this link belongs to is standing.
            await API.spawnBot(name);
            targetBot = name;
            log("Spawning bot " + name);
        } catch (e) {
            log("Could not spawn " + name + ": " + e.message, "error");
        }
    }

    async function removeBot() {
        const name = document.getElementById("bot-name-input").value.trim();
        if (!name) return;
        try {
            const result = await API.removeBot(name);
            log(result.success ? "Removed bot " + name : "There is no bot named " + name, result.success ? "info" : "error");
        } catch (e) {
            log("Could not remove " + name + ": " + e.message, "error");
        }
    }

    function selectBot(name) {
        targetBot = name;
        if (name) document.getElementById("bot-name-input").value = name;
        render();
    }

    function stateOf(name) {
        const program = programs[name];
        if (!program) return "idle";
        return program.running ? "running" : program.status === "ERROR" ? "error" : "idle";
    }

    // The lists are only rebuilt when they would look different, so an open dropdown is not reset four
    // times a second by status updates.
    let rendered = null;

    function render() {
        const names = Object.keys(bots).sort();
        const signature = JSON.stringify([targetBot, names.map(name => [name, stateOf(name)])]);
        if (signature !== rendered) {
            rendered = signature;
            renderLists(names);
        }
        renderStatus();
    }

    function renderLists(names) {
        const list = document.getElementById("bot-list");
        list.replaceChildren();
        if (names.length === 0) list.append(el("div", "empty-hint", "No active bots"));
        for (const name of names) {
            const state = stateOf(name);
            const item = el("div", "bot-list-item" + (name === targetBot ? " selected" : ""));
            const info = el("div", "bot-info");
            info.append(el("span", "conn-dot " + (state === "running" ? "connected" : "disconnected")), el("span", "bot-name", name));
            item.append(info, el("span", "bot-status-badge " + state, state));
            item.addEventListener("click", () => selectBot(name));
            list.append(item);
        }

        const select = document.getElementById("target-bot-select");
        select.replaceChildren(new Option("Select a bot…", ""));
        for (const name of names) select.append(new Option(name, name));
        select.value = targetBot;

        document.getElementById("run-target").textContent = targetBot ? "Runs on " + targetBot : "No bot selected";
    }

    function renderStatus() {
        const bot = bots[targetBot];
        const program = programs[targetBot];
        const state = document.getElementById("exec-state");
        const label = !program ? "Idle" : program.running ? "Running" : program.status === "ERROR" ? "Error" : "Finished";
        state.textContent = label;
        state.className = "rp-status-value" + (label === "Running" ? " status-running" : label === "Error" ? " status-error" : "");

        const section = document.getElementById("bot-status-section");
        section.classList.toggle("hidden", !bot);
        if (!bot) return;
        const rows = [
            ["Name", bot.name],
            ["Health", Math.round(bot.health * 10) / 10 + " / " + bot.maxHealth],
            ["Position", [bot.x, bot.y, bot.z].map(v => v.toFixed(1)).join(", ")],
            ["Dimension", bot.dimension],
            ["Main hand", bot.mainhand],
        ];
        if (program) {
            rows.push(["Program", program.programName]);
            if (program.currentAction) rows.push(["Action", program.currentAction]);
            if (program.error) rows.push(["Error", program.error]);
        }
        const content = document.getElementById("bot-status-content");
        content.replaceChildren();
        for (const [name, value] of rows) {
            const row = el("div", "status-row");
            row.append(el("span", "status-label", name), el("span", "status-value" + (name === "Error" ? " status-error" : ""), value));
            content.append(row);
        }
    }

    function getTargetBot() { return targetBot; }

    return { init, getTargetBot };
})();
