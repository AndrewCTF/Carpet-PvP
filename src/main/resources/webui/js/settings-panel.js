/* ═══════════════════════════════════════════════════════════════
   Settings Panel — the Carpet rules the editor and its bots run
   under, as the server's rule registry describes them. Anybody with
   a session can read them; an admin who signed in can change them,
   one rule at a time, and each row says whether its change took.

   What groups, filters and words things knows nothing of the page,
   so it is tested on its own.
   ═══════════════════════════════════════════════════════════════ */
const SettingsPanel = (() => {

    const GROUPS = [
        { id: "editor", title: "Web editor", note: "How this editor is reached and what it may do." },
        { id: "actions", title: "Program actions", note: "Rules some nodes need. A program stops at such a node while its rule is off." },
        { id: "bots", title: "Bot defaults", note: "What a new bot starts with. One bot is changed on its card, or with /bot option." },
    ];
    /** Turning this one off ends the session that turned it off. */
    const SIGN_IN_RULE = "carpetLogicAdminLogin";

    // ── Grouping, filtering and wording ──────────────────────────

    /** "carpetLogicMaxPrograms" as words, so that "max programs" finds it. */
    function spaced(name) {
        return name.replace(/([a-z0-9])([A-Z])/g, "$1 $2").toLowerCase();
    }

    function matches(rule, query) {
        const words = String(query || "").toLowerCase().split(/\s+/).filter(Boolean);
        const text = [rule.name.toLowerCase(), spaced(rule.name), rule.description || "", (rule.extra || []).join(" ")]
            .join(" ").toLowerCase();
        return words.every(word => text.includes(word));
    }

    /** The groups that have a rule the query fits, each with those rules, in the order the panel shows them. */
    function grouped(rules, query) {
        return GROUPS
            .map(group => Object.assign({}, group, { rules: rules.filter(rule => rule.group === group.id && matches(rule, query)) }))
            .filter(group => group.rules.length > 0);
    }

    /** How a rule is edited: "switch", "select", "number" or "text". */
    function control(rule) {
        if (rule.type === "boolean") return "switch";
        if (rule.strict && (rule.options || []).length > 0) return "select";
        return rule.type === "int" || rule.type === "number" ? "number" : "text";
    }

    function isModified(rule) {
        return rule.value !== rule.default;
    }

    /**
     * Whether this session may change rules, and the line that says why or why not.
     * @param status what GET /api/status answered
     * @param locked the reason no rule can be changed at all, if there is one
     */
    function access(status, locked) {
        if (locked) return { editable: false, signIn: false, text: locked + "." };
        if (status.admin) {
            return { editable: true, signIn: false, text: "Signed in as an admin: a change here is made on the server at once, and announced to its operators." };
        }
        if (status.adminLogin) {
            return { editable: false, signIn: true, text: "Read only. Admins of this server can sign in to change these." };
        }
        return { editable: false, signIn: false, text: "Read only here. Change a rule in game with /carpet <rule> <value>." };
    }

    /** What a row says once the server has answered a change. */
    function outcome(answer, before) {
        if (answer.ok) {
            const now = answer.body.rule ? answer.body.rule.value : "";
            const said = answer.body.message ? " " + answer.body.message : "";
            return { state: "saved", text: now === before ? "Unchanged." + said : "Saved. It was " + before + "." + said };
        }
        if (answer.status === 401) return { state: "refused", text: "Not saved: this session has ended. Sign in again." };
        return { state: "refused", text: "Not saved: " + (answer.body.error || "the server answered " + answer.status) };
    }

    // ── The panel ────────────────────────────────────────────────

    let rules = [];
    let mode = { editable: false, signIn: false, text: "" };
    const notes = new Map();        // rule name → { state, text }, kept while the panel is open
    let hooks = { changed() {}, signIn() {} };

    function $(id) { return document.getElementById(id); }

    function init(onChanged, onSignIn) {
        hooks = { changed: onChanged, signIn: onSignIn };
        $("btn-settings").addEventListener("click", open);
        $("settings-filter").addEventListener("input", () => {
            render();
            $("settings-list").scrollTop = 0;
        });
    }

    async function open() {
        Modal.open("settings-modal");
        $("settings-filter").value = "";
        notes.clear();
        $("settings-nav").replaceChildren();
        $("settings-banner").replaceChildren();
        $("settings-list").replaceChildren(el("p", "list-state", "Reading the rules from the server"));
        try {
            const [settings, status] = await Promise.all([API.getSettings(), API.getStatus()]);
            rules = settings.rules || [];
            mode = access(status, settings.locked);
            render();
            $("settings-filter").focus();
        } catch (e) {
            const retry = el("button", "btn", "Try again");
            retry.addEventListener("click", open);
            const state = el("p", "list-state error", "The rules could not be read: " + e.message + " ");
            state.append(retry);
            $("settings-list").replaceChildren(state);
        }
    }

    function render() {
        const query = $("settings-filter").value.trim();
        const chip = $("settings-mode");
        chip.textContent = mode.editable ? "Admin" : "Read only";
        chip.className = "chip " + (mode.editable ? "admin" : "quiet");

        const banner = $("settings-banner");
        banner.replaceChildren(el("span", null, mode.text));
        if (mode.signIn) {
            const signIn = el("button", "btn small", "Admin sign in");
            signIn.addEventListener("click", () => hooks.signIn());
            banner.append(signIn);
        }

        const groups = grouped(rules, query);
        const nav = $("settings-nav");
        const list = $("settings-list");
        // The list is drawn again after every change. Where it was scrolled to and which control had the
        // focus are put back, so that changing one rule does not lose the place.
        const scrolled = list.scrollTop;
        const focused = list.contains(document.activeElement) ? document.activeElement.getAttribute("aria-label") : null;
        nav.replaceChildren();
        list.replaceChildren();
        if (groups.length === 0) {
            list.append(el("p", "list-state", query ? "No rule matches “" + query + "”." : "The server listed no rules."));
            return;
        }
        for (const group of groups) {
            const heading = el("h3", "settings-group", group.title);
            heading.id = "settings-group-" + group.id;
            const jump = el("button", "settings-jump");
            jump.type = "button";
            jump.append(el("span", null, group.title), el("span", "count", group.rules.length));
            jump.addEventListener("click", () => heading.scrollIntoView({ block: "start" }));
            nav.append(jump);
            list.append(heading, el("p", "settings-group-note", group.note));
            for (const rule of group.rules) list.append(row(rule));
        }
        list.scrollTop = scrolled;
        if (focused) {
            const again = [...list.querySelectorAll("[aria-label]")].find(node => node.getAttribute("aria-label") === focused);
            if (again) again.focus();
        }
    }

    function row(rule) {
        const line = el("div", "rule" + (isModified(rule) ? " modified" : ""));
        const text = el("div", "rule-text");
        const name = el("div", "rule-name");
        name.append(el("code", null, rule.name));
        if (isModified(rule)) name.append(el("span", "chip quiet", "default " + rule.default));
        text.append(name, el("p", "rule-desc", rule.description));
        for (const extra of rule.extra || []) text.append(el("p", "rule-extra", extra));
        const note = notes.get(rule.name);
        const status = el("p", "field-note" + (note ? " " + note.state : ""), note ? note.text : "");
        status.setAttribute("role", "status");
        text.append(status);

        const side = el("div", "rule-control");
        if (!mode.editable) {
            side.append(el("span", "rule-value", rule.value));
        } else if (note && note.state === "confirm") {
            const sure = el("button", "btn small stop", "Turn it off");
            sure.addEventListener("click", () => commit(rule, "false", true));
            const keep = el("button", "btn small", "Keep it on");
            keep.addEventListener("click", () => { notes.delete(rule.name); render(); });
            side.append(sure, keep);
        } else {
            side.append(...editor(rule, note && note.state === "saving"));
        }
        line.append(text, side);
        return line;
    }

    // The control a rule's type calls for. Each one sends its value as the text /carpet would be given.
    function editor(rule, saving) {
        const kind = control(rule);
        const parts = [];
        if (kind === "switch") {
            const on = rule.value === "true";
            const toggle = el("button", "switch" + (on ? " on" : ""));
            toggle.type = "button";
            toggle.setAttribute("role", "switch");
            toggle.setAttribute("aria-checked", String(on));
            toggle.setAttribute("aria-label", rule.name);
            toggle.disabled = saving;
            toggle.append(el("span", "switch-knob"));
            toggle.addEventListener("click", () => commit(rule, String(!on)));
            parts.push(toggle, el("span", "switch-value", rule.value));
        } else if (kind === "select") {
            const select = el("select", "field");
            select.setAttribute("aria-label", rule.name);
            select.disabled = saving;
            const options = rule.options.includes(rule.value) ? rule.options : [rule.value].concat(rule.options);
            for (const option of options) select.append(new Option(option, option));
            select.value = rule.value;
            select.addEventListener("change", () => commit(rule, select.value));
            parts.push(select);
        } else {
            const input = el("input", "field" + (kind === "number" ? " number" : ""));
            input.type = "text";
            if (kind === "number") input.inputMode = "decimal";
            input.value = rule.value;
            input.maxLength = 120;
            input.spellcheck = false;
            input.setAttribute("aria-label", rule.name);
            input.disabled = saving;
            const send = () => { if (input.value.trim() !== rule.value) commit(rule, input.value.trim()); };
            input.addEventListener("keydown", (e) => {
                if (e.key === "Enter") { e.preventDefault(); send(); }
                if (e.key === "Escape" && input.value !== rule.value) { e.stopPropagation(); input.value = rule.value; }
            });
            input.addEventListener("blur", send);
            parts.push(input);
            const offered = (rule.options || []).filter(option => option !== rule.value);
            if (offered.length > 0) {
                const picks = el("div", "rule-picks");
                for (const option of offered.slice(0, 4)) {
                    const pick = el("button", "pick", option);
                    pick.type = "button";
                    pick.title = "Set " + rule.name + " to " + option;
                    pick.disabled = saving;
                    // A click takes the focus from the field first; the field must not send its old text then.
                    pick.addEventListener("mousedown", (e) => e.preventDefault());
                    pick.addEventListener("click", () => commit(rule, option));
                    picks.append(pick);
                }
                parts.push(picks);
            }
        }
        return parts;
    }

    async function commit(rule, value, confirmed) {
        if (value === "" || (notes.get(rule.name) || {}).state === "saving") return;
        if (rule.name === SIGN_IN_RULE && value === "false" && !confirmed) {
            notes.set(rule.name, { state: "confirm", text: "This takes the admin sign-in away and ends your session at once." });
            render();
            return;
        }
        const before = rule.value;
        notes.set(rule.name, { state: "saving", text: "Saving" });
        render();
        let answer;
        try {
            answer = await API.setRule(rule.name, value);
        } catch (e) {
            answer = { ok: false, status: 0, body: { error: "the server did not answer" } };
        }
        // The answer carries the rule as the server now has it, changed or not.
        if (answer.body.rule) rules = rules.map(other => other.name === rule.name ? answer.body.rule : other);
        notes.set(rule.name, outcome(answer, before));
        render();
        if (answer.ok) log("Rule " + rule.name + " is now " + answer.body.rule.value);
        hooks.changed(rule.name, answer);
    }

    return { GROUPS, spaced, matches, grouped, control, isModified, access, outcome, init, open };
})();

if (typeof module !== "undefined") module.exports = SettingsPanel;
