/* ═══════════════════════════════════════════════════════════════
   Inspector — the selected node's settings as fields of the page:
   each with what it takes, the names an expression may use as they
   are typed, and what is wrong with it the moment it is.

   The node on the canvas keeps its own small widgets for a quick
   change. What the fields are and what is said about them knows
   nothing of the page, so it is tested on its own.
   ═══════════════════════════════════════════════════════════════ */
const Inspector = (() => {

    const MAX_SUGGESTIONS = 7;

    // ── What a node's fields are ─────────────────────────────────

    function labelFor(name) {
        const spaced = name.replace(/([a-z])([A-Z0-9])/g, "$1 $2");
        return spaced.charAt(0).toUpperCase() + spaced.slice(1);
    }

    function range(param) {
        const low = param.min !== undefined, high = param.max !== undefined;
        return low && high ? " from " + param.min + " to " + param.max : low ? " of at least " + param.min : high ? " of at most " + param.max : "";
    }

    const GIVES = {
        bool: "An expression that is true or false, such as health < 10 and has_target.",
        number: "An expression that gives a number.",
        text: "An expression that gives text.",
        list: "An expression that gives a list.",
        any: "An expression: a number, text in quotes, true or false, or a list."
    };

    /**
     * The fields of a node, one per parameter: how each is edited and what is said under it.
     * @param params the parameters as the action schema (or a macro node) declares them
     * @param served node parameter name → the list the server sent for it, where it sent one
     */
    function fields(params, served) {
        return params.map(param => {
            const options = param.options || (served && served[param.name]) || null;
            const field = { name: param.name, label: labelFor(param.name), param: param };
            if (param.type === "bool") return Object.assign(field, { kind: "switch", help: "" });
            if (param.options) return Object.assign(field, { kind: "select", options: options, help: "" });
            if (param.type === "expr") return Object.assign(field, { kind: "expression", help: GIVES[param.returns || "any"] });
            if (param.type === "int" || param.type === "number") {
                return Object.assign(field, {
                    kind: "expression",
                    help: (param.type === "int" ? "A whole number" : "A number") + range(param) + ", a variable such as $count, or an expression."
                });
            }
            return Object.assign(field, { kind: "text", options: options, help: options ? "One of the names this server has, or another." : "" });
        });
    }

    /**
     * The name being typed where the caret is, for the suggestions: { word, start }, or null where no name is
     * being typed: in text between quotes, or right after a number.
     */
    function wordAt(text, caret) {
        const before = text.slice(0, caret);
        let quote = null;
        for (let i = 0; i < before.length; i++) {
            const c = before[i];
            if (quote) {
                if (c === "\\") i++;
                else if (c === quote) quote = null;
            } else if (c === "'" || c === '"') {
                quote = c;
            }
        }
        if (quote) return null;
        const match = /[$A-Za-z_][A-Za-z0-9_]*$/.exec(before);
        if (!match) return null;
        const start = before.length - match[0].length;
        if (start > 0 && /[0-9.]/.test(before[start - 1])) return null;
        return { word: match[0], start: start };
    }

    /** The variables a graph gives a value to, by the nodes that do: what "$" can be followed by. */
    function variablesOf(graph) {
        const names = new Set();
        for (const node of (graph && graph._nodes) || []) {
            if (/^Variables\//.test(node.type) && node.properties && /^[A-Za-z_][A-Za-z0-9_]*$/.test(node.properties.name || "")) {
                names.add(node.properties.name);
            }
        }
        return [...names].sort();
    }

    /**
     * What to offer for the name being typed: the values and functions the server knows, or after "$" the
     * variables of this program. Each is { name, kind, type, description, insert }.
     */
    function suggestions(word, vocabulary, variables) {
        if (word.startsWith("$")) {
            return variables.filter(name => name.toLowerCase().startsWith(word.slice(1).toLowerCase()) && name !== word.slice(1))
                .slice(0, MAX_SUGGESTIONS)
                .map(name => ({ name: "$" + name, kind: "variable", type: "any", description: "A variable of this program.", insert: "$" + name }));
        }
        return Expression.suggest(vocabulary, word).filter(entry => entry.name !== word).slice(0, MAX_SUGGESTIONS);
    }

    // ── The panel ────────────────────────────────────────────────

    let schema = null;
    let settings = {};
    let node = null;            // the node whose fields are shown
    let offered = [];           // the suggestions on show, and the field they are for
    let offeredFor = null;

    function $(id) { return document.getElementById(id); }

    function init(actionSchema, serverSettings) {
        schema = actionSchema;
        settings = serverSettings || {};
        NodeEditor.onSelect(show);
        NodeEditor.onChange(() => { refresh(); review(); });
        $("inspector-close").addEventListener("click", () => show(null));
        review();
    }

    function vocabulary() {
        return Object.assign({ prefix: schema.variables.referencePrefix }, schema.expressions);
    }

    function paramsOf(type) {
        const action = Object.values(schema.actions).find(def => def.node === type);
        if (action) return action.params;
        return Nodes.MACROS[type] ? Nodes.MACROS[type].params : [];
    }

    // The lists the server sends for a node's free-text parameters, as nodes.js names them.
    function servedFor(type, params) {
        const served = {};
        for (const param of params) {
            const from = (Nodes.FROM_SERVER[type] || {})[param.name] || param.optionsFrom;
            if (from && (settings[from] || []).length > 0 && !param.options) served[param.name] = settings[from];
        }
        return served;
    }

    function show(selected) {
        node = selected;
        const panel = $("inspector");
        panel.classList.toggle("hidden", !node);
        hideSuggestions();
        if (!node) return;
        const info = Nodes.NODES[node.type] || Nodes.MACROS[node.type] || {};
        $("inspector-title").textContent = node.title;
        $("inspector-swatch").style.background = node.color;
        $("inspector-desc").textContent = info.desc || "";
        const params = paramsOf(node.type);
        const form = $("inspector-fields");
        form.replaceChildren();
        if (params.length === 0) form.append(el("p", "hint", "This node has nothing to set."));
        for (const field of fields(params, servedFor(node.type, params))) form.append(row(field));
        showProblem();
    }

    function row(field) {
        const id = "inspect-" + field.name;
        const line = el("div", "inspect-row");
        const label = el("label", null, field.label);
        label.htmlFor = id;
        const note = el("p", "field-note");
        note.setAttribute("role", "status");
        let control;
        if (field.kind === "switch") {
            control = el("button", "switch");
            control.type = "button";
            control.setAttribute("role", "switch");
            control.append(el("span", "switch-knob"));
            const paint = () => {
                const on = Boolean(node.properties[field.name]);
                control.classList.toggle("on", on);
                control.setAttribute("aria-checked", String(on));
            };
            control.addEventListener("click", () => { set(field, !node.properties[field.name]); paint(); commit(); });
            control.paint = paint;
            paint();
        } else if (field.kind === "select") {
            control = el("select", "field");
            for (const option of field.options) control.append(new Option(option, option));
            control.value = String(node.properties[field.name]);
            control.addEventListener("change", () => { set(field, control.value); commit(); });
            control.paint = () => { control.value = String(node.properties[field.name]); };
        } else {
            control = el("input", "field" + (field.kind === "expression" ? " code" : ""));
            control.type = "text";
            control.spellcheck = false;
            control.autocomplete = "off";
            control.maxLength = 1024;
            control.value = String(node.properties[field.name]);
            if (field.options) {
                const list = el("datalist");
                list.id = id + "-options";
                for (const option of field.options) list.append(new Option(option, option));
                control.setAttribute("list", list.id);
                line.append(list);
            }
            const check = () => {
                const wrong = NodeCompiler.problem(field.param, control.value);
                note.textContent = wrong || "";
                note.className = "field-note" + (wrong ? " refused" : "");
                control.classList.toggle("invalid", Boolean(wrong));
                return !wrong;
            };
            control.addEventListener("input", () => {
                // What is typed is what the node holds, mistake or not: the mistake is shown, not undone.
                set(field, control.value);
                check();
                review();
                if (field.kind === "expression") suggest(field, control);
            });
            control.addEventListener("keydown", (e) => {
                if (e.key === "Tab" && offeredFor === control && offered.length > 0 && !e.shiftKey) {
                    e.preventDefault();
                    accept(field, control, offered[0]);
                } else if (e.key === "Escape" && offeredFor === control) {
                    e.stopPropagation();
                    hideSuggestions();
                } else if (e.key === "Enter") {
                    control.blur();
                }
            });
            control.addEventListener("blur", () => { setTimeout(() => { if (offeredFor === control) hideSuggestions(); }, 150); commit(); });
            control.paint = () => { if (document.activeElement !== control) { control.value = String(node.properties[field.name]); check(); } };
            check();
        }
        control.id = id;
        control.dataset.field = field.name;
        line.append(label, control);
        if (field.help) line.append(el("p", "hint", field.help));
        line.append(note);
        return line;
    }

    // A number field that holds a plain number gives the node a number; anything else it gives as typed.
    function set(field, value) {
        const numeric = (field.param.type === "int" || field.param.type === "number") && typeof value === "string"
            && /^-?(\d+(\.\d+)?|\.\d+)$/.test(value.trim());
        node.setProperty(field.name, numeric ? Number(value) : value);
        NodeEditor.redraw();
    }

    function commit() {
        NodeEditor.snapshot();
    }

    // The canvas changed what the node holds: the fields follow, except the one being typed in.
    function refresh() {
        if (!node) return;
        if (!NodeEditor.getGraph().getNodeById(node.id)) {
            show(null);
            return;
        }
        document.querySelectorAll("#inspector-fields [data-field]").forEach(control => control.paint());
    }

    // ── Suggestions ──────────────────────────────────────────────

    function suggest(field, control) {
        const typed = wordAt(control.value, control.selectionStart);
        offered = typed ? suggestions(typed.word, vocabulary(), variablesOf(NodeEditor.getGraph())) : [];
        const box = $("inspector-suggest");
        box.replaceChildren();
        box.classList.toggle("hidden", offered.length === 0);
        offeredFor = offered.length > 0 ? control : null;
        if (offered.length === 0) return;
        control.parentElement.append(box);
        offered.forEach((entry, position) => {
            const item = el("button", "suggest-item");
            item.type = "button";
            item.append(el("code", null, entry.insert), el("span", "suggest-type", entry.type), el("span", "suggest-desc", entry.description));
            // The field must keep the focus, so that the caret is where the name goes.
            item.addEventListener("mousedown", (e) => { e.preventDefault(); accept(field, control, entry); });
            if (position === 0) item.title = "Tab takes this one";
            box.append(item);
        });
    }

    function accept(field, control, entry) {
        const typed = wordAt(control.value, control.selectionStart);
        if (!typed) return;
        const caret = typed.start + entry.insert.length;
        control.value = control.value.slice(0, typed.start) + entry.insert + control.value.slice(control.selectionStart);
        control.setSelectionRange(caret, caret);
        control.dispatchEvent(new Event("input"));
    }

    function hideSuggestions() {
        offered = [];
        offeredFor = null;
        $("inspector-suggest").classList.add("hidden");
    }

    // ── Mistakes, shown on the nodes ─────────────────────────────

    let problems = new Map();   // node id → what is wrong with it
    const inField = new Set();  // the nodes whose mistake is in one of their fields

    /** Looks the program over the way a run would, and marks the nodes that keep it from running. */
    function review() {
        const found = [];
        try {
            NodeCompiler.compile(NodeEditor.getGraph(), found);
        } catch (e) {
            if (e.nodeId !== undefined) found.push({ nodeId: e.nodeId, param: null, message: e.message });
        }
        problems = new Map();
        inField.clear();
        for (const mistake of found) {
            if (problems.has(mistake.nodeId)) continue;
            problems.set(mistake.nodeId, mistake.param ? labelFor(mistake.param) + ": " + mistake.message : mistake.message);
            // A mistake in a field is said under the field; the panel's own line is for what no field can say.
            if (mistake.param) inField.add(mistake.nodeId);
        }
        NodeEditor.setProblems(problems);
        showProblem();
    }

    function showProblem() {
        const wrong = node && !inField.has(node.id) ? problems.get(node.id) : null;
        const note = $("inspector-problem");
        note.textContent = wrong || "";
        note.classList.toggle("hidden", !wrong);
    }

    return { fields, wordAt, variablesOf, suggestions, labelFor, init, show, review };
})();

if (typeof module !== "undefined") module.exports = Inspector;
