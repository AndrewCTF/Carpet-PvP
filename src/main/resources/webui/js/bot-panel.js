/* ═══════════════════════════════════════════════════════════════
   Bot Panel — a live card per fake player: its vitals, what it is
   fighting and what it is running, and what you may do to it.

   The panel holds one state object. Events from the server go through
   applyEvent(), which only reads and writes that state, and render()
   draws it. A card is built once and patched in place afterwards, so a
   value being typed into it survives the next update. Everything that
   comes from the server goes into the page as text, never as HTML.
   ═══════════════════════════════════════════════════════════════ */
const BotPanel = (() => {

    const DASH = "—";
    /** The rows every card has, in the order it draws them. */
    const ROWS = ["Position", "World", "Style", "Combat", "Target", "Food", "Held", "Program", "Action", "Error"];

    const state = {
        bots: {},            // name → the bot snapshot the server last sent
        programs: {},        // name → what its program is doing
        matches: [],         // finished fights, newest first
        combatSettings: [],  // the setting names /player <name> ai takes
        viewerMode: false,
        targetBot: "",       // the bot the editor's Play button runs on
        connected: false
    };

    // ── Events ───────────────────────────────────────────────────

    function init() {
        document.getElementById("spawn-bot-btn").addEventListener("click", spawnBot);
        // Enter in the name field presses Spawn; the form itself goes nowhere.
        document.getElementById("spawn-form").addEventListener("submit", (e) => e.preventDefault());
        document.getElementById("target-bot-select").addEventListener("change", (e) => selectBot(e.target.value));

        API.on("botUpdate", (update) => {
            applyEvent(state, update);
            render();
        });
        API.on("matchUpdate", (update) => {
            applyEvent(state, update);
            render();
        });
        API.on("connectionChange", (connected) => {
            applyEvent(state, { type: "connectionChange", connected });
            render();
        });

        for (const tab of document.querySelectorAll(".rp-tab")) {
            tab.addEventListener("click", () => {
                for (const other of document.querySelectorAll(".rp-tab")) {
                    other.classList.toggle("active", other === tab);
                    other.setAttribute("aria-selected", String(other === tab));
                }
                for (const panel of document.querySelectorAll(".rp-tab-panel")) {
                    panel.classList.toggle("active", panel.dataset.panel === tab.dataset.tab);
                }
            });
        }

        render();
    }

    /**
     * Folds one message from the server into the panel state.
     */
    function applyEvent(state, message) {
        if (message.type === "botUpdate") {
            applySnapshot(state, message);
        } else if (message.type === "matchUpdate") {
            state.matches = message.matches || [];
        } else if (message.type === "connectionChange") {
            state.connected = Boolean(message.connected);
        }
        return state;
    }

    function applySnapshot(state, snapshot) {
        state.bots = snapshot.bots || {};
        state.programs = snapshot.programs || {};
        if (snapshot.combatSettings) state.combatSettings = snapshot.combatSettings;
        state.viewerMode = Boolean(snapshot.viewerMode);
        if (state.targetBot && !state.bots[state.targetBot]) state.targetBot = "";
        // A single bot is the obvious one to run on.
        if (!state.targetBot && Object.keys(state.bots).length === 1) state.targetBot = Object.keys(state.bots)[0];
        return state;
    }

    async function loadHistory() {
        if (!API.hasToken()) return;
        try {
            applyEvent(state, { type: "matchUpdate", matches: await API.getMatches() });
            render();
        } catch (e) {
            log("Could not load the match history: " + e.message, "error");
        }
    }

    // ── Rendering ────────────────────────────────────────────────

    function render() {
        renderCards();
        renderMatches();
        renderTarget();
        document.getElementById("panel-stale").classList.toggle("hidden", state.connected);
    }

    const views = new Map();     // bot name → its card, so it is only built once
    let listed = null;            // the names the cards were built for

    function renderCards() {
        const names = Object.keys(state.bots).sort();
        const list = document.getElementById("bot-list");
        const signature = JSON.stringify(names);
        if (signature !== listed) {
            listed = signature;
            views.clear();
            list.replaceChildren();
            if (names.length === 0) {
                list.append(el("div", "empty-hint", "No bots on the server. Spawn one above, or in game with /bot spawn or /player <name> spawn."));
            }
            for (const name of names) {
                const view = botCard(name);
                views.set(name, view);
                list.append(view.card);
            }
        }
        views.forEach((view, name) => patchCard(view, state.bots[name]));
    }

    /**
     * Builds the card of one bot. Only its shape is here; the numbers are put in by patchCard.
     */
    function botCard(name) {
        const view = { name: name, values: {}, rows: {} };
        view.card = el("div", "bot-card");
        view.card.title = "Click to make this the bot the program runs on";
        view.card.addEventListener("click", () => selectBot(name));

        const head = el("div", "bot-card-head");
        view.badge = el("span", "bot-status-badge idle", "Idle");
        view.tag = el("span", "bot-target-tag hidden", "runs here");
        head.append(el("span", "bot-name", name), view.tag, view.badge);

        const bar = el("div", "hp-bar");
        view.fill = el("div", "hp-fill");
        bar.append(view.fill);
        view.health = el("span", "hp-label", "");
        const health = el("div", "bot-health");
        health.append(bar, view.health);

        const rows = el("div", "bot-card-rows");
        for (const label of ROWS) {
            view.values[label] = el("span", "status-value" + (label === "Error" ? " status-error" : ""), DASH);
            const line = el("div", "status-row");
            line.append(el("span", "status-label", label), view.values[label]);
            view.rows[label] = line;
            rows.append(line);
        }

        view.card.append(head, health, rows, actions(view));
        return view;
    }

    function patchCard(view, bot) {
        const program = bot.program;
        const pvp = bot.pvp || {};
        const max = bot.maxHealth || 20;
        const fraction = Math.max(0, Math.min(1, (bot.health || 0) / max));

        view.card.className = "bot-card" + (bot.name === state.targetBot ? " selected" : "") + (bot.alive ? "" : " dead");
        view.fill.className = "hp-fill" + (fraction < 0.34 ? " low" : "");
        view.fill.style.width = (fraction * 100).toFixed(1) + "%";
        view.health.textContent = one(bot.health) + " / " + one(max) + (bot.absorption > 0 ? " (+" + one(bot.absorption) + ")" : "");

        const values = cardValues(bot, program, pvp);
        for (const label of ROWS) view.values[label].textContent = values[label];
        // What a program is doing and what went wrong only take a line while there is something to say.
        view.rows.Action.classList.toggle("hidden", values.Action === DASH);
        view.rows.Error.classList.toggle("hidden", values.Error === DASH);
        view.tag.classList.toggle("hidden", bot.name !== state.targetBot);

        const badge = programState(program, bot);
        view.badge.className = "bot-status-badge " + badge.state;
        view.badge.textContent = badge.label;
        view.running = Boolean(program && program.running);
        view.run.textContent = view.running ? "■ Stop" : "▶ Run";
        view.run.className = "btn-sm " + (view.running ? "sm red" : "sm green");
        view.combatOn = Boolean(pvp.combat);
        view.combat.textContent = view.combatOn ? "Combat: on" : "Combat: off";
    }

    function cardValues(bot, program, pvp) {
        return {
            Position: [bot.x, bot.y, bot.z].map(one).join(", "),
            World: String(bot.dimension).replace(/^minecraft:/, ""),
            Style: pvp.style || DASH,
            Combat: pvp.combat === undefined ? DASH : pvp.combat ? "on" : "off",
            Target: bot.target || DASH,
            Food: bot.foodLevel + " · armor " + bot.armor,
            Held: held(bot),
            Program: program ? program.name + " (" + program.status.toLowerCase() + ")" : DASH,
            Action: program && program.action ? program.action : DASH,
            Error: program && program.error ? program.error : DASH
        };
    }

    function held(bot) {
        const equipment = bot.equipment || {};
        const main = equipment.mainhand || "empty";
        return equipment.offhand && equipment.offhand !== "empty" ? main + " + " + equipment.offhand : main;
    }

    function actions(view) {
        const bar = el("div", "bot-card-actions");
        view.run = button("▶ Run", "sm green", () => {
            // The Play button runs on the card that was clicked.
            selectBot(view.name);
            if (view.running) API.stopProgram(view.name).catch((e) => complain(e, "Could not stop " + view.name));
            else ProgramPanel.runProgram();
        });
        view.combat = button("Combat: off", "sm", () => {
            const value = String(!view.combatOn);
            setSetting(view.name, "combat", value, "Could not turn the combat AI of " + view.name + " " + value);
        });
        bar.append(view.run, view.combat);
        const bring = button("Bring me", "sm", () => act("Could not bring " + view.name + " here", () =>
            API.tpBot(view.name).then(() => log(view.name + " is here"))));
        bring.title = "Teleport this bot to where you stand in game";
        bar.append(bring);
        bar.append(button("Remove", "sm red", () => act("Could not remove " + view.name, () =>
            API.removeBot(view.name).then((result) => {
                log(result.success ? "Removed bot " + view.name : "There is no bot named " + view.name,
                    result.success ? "info" : "error");
            }))));
        bar.append(setting(view));
        return bar;
    }

    // One combat setting at a time, by the names the server lists, so the panel never offers a setting that
    // /player <name> ai would refuse.
    function setting(view) {
        const form = el("div", "bot-card-setting");
        form.title = "One setting of this bot's combat AI, as /bot option takes it";
        const pick = el("select", "sm-select");
        for (const key of state.combatSettings) pick.append(new Option(key, key));
        const value = el("input", "sm-input");
        value.type = "text";
        value.maxLength = 64;
        value.spellcheck = false;
        value.placeholder = "value";
        const set = button("Set", "sm", () => {
            if (!value.value.trim()) {
                log("A setting needs a value", "error");
                return;
            }
            setSetting(view.name, pick.value, value.value.trim(), "Could not set " + pick.value + " on " + view.name);
        });
        // The fields are on a card that is clicked to pick the bot: using them is not picking.
        for (const field of [pick, value]) field.addEventListener("click", (e) => e.stopPropagation());
        value.addEventListener("keydown", (e) => { if (e.key === "Enter") set.click(); });
        form.append(pick, value, set);
        return form;
    }

    function setSetting(name, key, value, onError) {
        act(onError, () => API.setBotConfig(name, key, value).then(() => log(name + ": " + key + " = " + value)));
    }

    function button(label, className, onClick) {
        const element = el("button", "btn-sm " + className, label);
        element.addEventListener("click", (e) => {
            // A click on a button is also a click on the card, which picks the bot to run on.
            e.stopPropagation();
            onClick();
        });
        return element;
    }

    function act(onError, work) {
        if (state.viewerMode) {
            log("The editor is in viewer mode (carpetLogicViewerMode)", "error");
            return;
        }
        Promise.resolve().then(work).catch((e) => complain(e, onError));
    }

    function complain(e, what) {
        log(what + ": " + (e && e.message ? e.message : e), "error");
    }

    let matched = null;

    function renderMatches() {
        const signature = JSON.stringify(state.matches);
        if (signature === matched) return;
        matched = signature;
        const list = document.getElementById("match-list");
        list.replaceChildren();
        if (state.matches.length === 0) {
            list.append(el("div", "empty-hint", "No finished fights yet. A fight between bots is listed here when it ends."));
            return;
        }
        for (const match of state.matches) list.append(matchRow(match));
    }

    function matchRow(match) {
        const line = el("div", "match-row");
        line.append(
            el("span", "match-fight", match.attacker + " vs " + match.defender),
            el("span", "match-result", (match.winner ? match.winner + " won" : "no winner") + " · " + match.ticks + " ticks"),
            el("span", "match-damage", one(match.attackerDamage) + " / " + one(match.defenderDamage) + " damage"));
        return line;
    }

    let targeted = null;

    function renderTarget() {
        const names = Object.keys(state.bots).sort();
        const select = document.getElementById("target-bot-select");
        // The options are only rebuilt when the bots themselves changed, so a dropdown that is open is not
        // thrown shut by the next status update.
        if (JSON.stringify(names) !== targeted) {
            targeted = JSON.stringify(names);
            select.replaceChildren(new Option(names.length === 0 ? "No bot" : "Choose a bot", ""));
            for (const name of names) select.append(new Option(name, name));
            document.getElementById("bots-count").textContent = names.length === 0 ? "" : String(names.length);
        }
        select.value = state.targetBot;
        const hint = document.getElementById("run-target");
        hint.textContent = state.targetBot ? "Runs on " + state.targetBot
            : names.length === 0 ? "No bot selected: spawn one in the Bots panel" : "No bot selected";
        hint.classList.toggle("hidden", Boolean(state.targetBot));
        const program = state.programs[state.targetBot];
        const badge = programState(program, state.bots[state.targetBot]);
        const label = document.getElementById("exec-state");
        label.textContent = runLine(badge, program);
        label.className = "run-state" + (badge.state === "running" ? " status-running" : badge.state === "error" ? " status-error" : "");
        label.classList.toggle("hidden", !state.targetBot);
        document.getElementById("btn-stop").disabled = badge.state !== "running";
    }

    // What the run bar says about the chosen bot's program: its state, and what it is doing or what stopped it.
    function runLine(badge, program) {
        if (!program) return badge.label;
        const name = program.programName ? " · " + program.programName : "";
        if (badge.state === "running" && program.currentAction) return badge.label + name + " · " + program.currentAction;
        if (badge.state === "error" && program.error) return badge.label + name + " · " + program.error;
        return badge.label + name;
    }

    // ── Selecting and spawning ────────────────────────────────────

    function selectBot(name) {
        state.targetBot = name;
        if (name) document.getElementById("bot-name-input").value = name;
        render();
    }

    async function spawnBot() {
        const name = document.getElementById("bot-name-input").value.trim();
        if (!name) return;
        try {
            // No position is sent: the server puts the bot where the player this link belongs to is standing.
            await API.spawnBot(name);
            selectBot(name);
            log("Spawning bot " + name);
        } catch (e) {
            complain(e, "Could not spawn " + name);
        }
    }

    function programState(program, bot) {
        if (bot && bot.alive === false) return { state: "error", label: "dead" };
        if (!program) return { state: "idle", label: "Idle" };
        if (program.status === "ERROR") return { state: "error", label: "Error" };
        return program.running ? { state: "running", label: "Running" } : { state: "idle", label: "Finished" };
    }

    function one(value) {
        return Math.round(Number(value || 0) * 10) / 10;
    }

    function getTargetBot() { return state.targetBot; }

    return { init, state, applyEvent, applySnapshot, render, selectBot, getTargetBot, loadHistory };
})();

if (typeof module !== "undefined") module.exports = BotPanel;