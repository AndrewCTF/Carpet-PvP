/* ═══════════════════════════════════════════════════════════════
   Node Compiler — turns the node graph into a JSON action tree

   Action types and their parameters come from the action schema the
   server sends (/api/schema). A node's properties are read by the
   parameter names the schema declares, so the compiler cannot emit a
   name the interpreter does not read.
   ═══════════════════════════════════════════════════════════════ */
const NodeCompiler = (() => {

    // The checker of expressions: a script of the page, or a module where there is no page.
    const Checker = typeof Expression !== "undefined" ? Expression : require("./expression.js");
    const NUMERAL = /^-?(\d+(\.\d+)?|\.\d+)$/;
    // How much text a parameter holds when the schema does not say: ActionSchema.MAX_STRING_LENGTH.
    const MAX_STRING_LENGTH = 1024;

    let schema = null;
    let actionByNode = {};      // editor node type → action type
    let vocabulary = null;      // the names and functions an expression may use, and how a variable is written
    let mistakes = null;        // where the mistakes found while compiling are noted, when somebody wants them
    let compiling = null;       // the node being compiled
    let nesting = 0;            // how deep in conditions made of conditions the compiler is

    function setSchema(newSchema) {
        schema = newSchema;
        actionByNode = {};
        for (const [type, def] of Object.entries(schema.actions)) {
            actionByNode[def.node] = type;
        }
        vocabulary = Object.assign({ prefix: schema.variables.referencePrefix }, schema.expressions);
    }

    // ── Parameters ───────────────────────────────────────────

    // What an expression has to give to fit a parameter.
    function expected(param) {
        return param.type === "expr" ? param.returns || "any" : "number";
    }

    /** What is wrong with a parameter's value as the checker of expressions sees it, or null. */
    function problem(param, value) {
        const text = value === undefined || value === null ? "" : String(value).trim();
        if (param.type === "expr") {
            const verdict = Checker.check(text === "" ? String(param.default) : text, vocabulary, expected(param));
            return verdict.ok ? null : verdict.error;
        }
        if ((param.type === "int" || param.type === "number") && typeof value === "string" && text !== "" && !NUMERAL.test(text)) {
            const verdict = Checker.check(text, vocabulary, "number");
            return verdict.ok ? null : verdict.error;
        }
        const longest = param.maxLength || MAX_STRING_LENGTH;
        if (param.type === "string" && !param.options && value !== undefined && value !== null && String(value).length > longest) {
            return "must be text of at most " + longest + " characters";
        }
        return null;
    }

    // A value the parameter cannot take becomes the parameter's default, so that what is compiled always fits
    // the schema. What was wrong with it is noted for whoever asked compile() for the mistakes.
    function coerce(param, value) {
        const wrong = problem(param, value);
        if (wrong) {
            if (mistakes) {
                mistakes.push({ nodeId: compiling ? compiling.id : null, node: compiling ? compiling.title : null, param: param.name, message: wrong });
            }
            return param.default;
        }
        switch (param.type) {
            case "expr": {
                const text = value === undefined || value === null ? "" : String(value).trim();
                return text === "" ? param.default : text;
            }
            case "bool":
                return typeof value === "boolean" ? value : param.default;
            case "string": {
                const text = value === undefined || value === null ? param.default : String(value);
                return param.options && !param.options.includes(text) ? param.default : text;
            }
            default: {
                // A number parameter may hold an expression instead of a number, of which a variable is the simplest.
                if (typeof value === "string" && value.trim() !== "" && !NUMERAL.test(value.trim())) {
                    return value.trim();
                }
                let number = value === "" || value === null ? NaN : Number(value);
                if (!Number.isFinite(number)) number = param.default;
                if (param.type === "int") number = Math.round(number);
                if (param.min !== undefined) number = Math.max(param.min, number);
                if (param.max !== undefined) number = Math.min(param.max, number);
                return number;
            }
        }
    }

    /** Builds an action of the given type. Values are looked up by the parameter names the schema declares. */
    function make(type, values) {
        const def = schema.actions[type];
        if (!def) throw new Error("Unknown action type " + type);
        for (const name of Object.keys(values || {})) {
            if (!def.params.some(p => p.name === name)) throw new Error(type + " has no parameter '" + name + "'");
        }
        const action = { type };
        if (def.params.length > 0) {
            action.params = {};
            for (const param of def.params) {
                action.params[param.name] = coerce(param, values ? values[param.name] : undefined);
            }
        }
        return action;
    }

    // ── Compile graph to actions array ───────────────────────

    // Which output a node's chain carries on from once the node is done. Every other node carries on from
    // output 0. Forever never finishes and Sequence's outputs are all part of it, so nothing follows those.
    const CONTINUES_FROM = {
        "Control/Repeat": 1,        // body, done
        "Control/If-Else": 2,       // then, else, done
        "Control/Forever": null,
        "Control/Sequence": null,
        "Control/If": 2,            // then, else, done
        "Control/While": 1,         // body, done
        "Control/ForEach": 1,       // body, done
        "Events/OnEvent": 1,        // body, next
    };

    // The nodes that are handed a condition: it arrives on their last input, as nodes.js declares the sockets.
    const WITH_CONDITION = new Set(["Control/If-Else", "Control/WaitUntil"]);

    /**
     * @param found optional: an array that is given the mistakes in the fields of the nodes that were compiled,
     *              each as { nodeId, node, param, message }. A program that has any is not what its author
     *              wrote, since every mistaken field was compiled as its default.
     */
    function compile(graph, found) {
        if (!schema) throw new Error("The action schema has not been loaded");
        const start = (graph._nodes || []).find(n => n.type === "Control/Start");
        if (!start) throw new Error("No Start node found. Add a Control/Start node.");
        mistakes = found || null;
        try {
            return chainFrom(graph, start, new Set());
        } finally {
            mistakes = null;
            compiling = null;
        }
    }

    /**
     * Compiles a program that is meant to run: one mistaken field is a reason not to.
     * @throws Error with the first reason the graph does not compile, and the nodeId of the node it is about
     */
    function compileStrictly(graph) {
        const found = [];
        const actions = compile(graph, found);
        if (found.length > 0) {
            const first = found[0];
            const error = new Error(first.param ? first.node + ", " + first.param + ": " + first.message : first.message);
            error.nodeId = first.nodeId;
            throw error;
        }
        return actions;
    }

    // The actions for a node and everything that follows it. A node already on this path ends the chain,
    // so a graph that somehow holds a cycle cannot make the compiler loop.
    function chainFrom(graph, node, visited) {
        const actions = [];
        while (node && !visited.has(node.id)) {
            visited.add(node.id);
            const action = compileNode(graph, node, visited);
            if (action) actions.push(action);
            const output = node.type in CONTINUES_FROM ? CONTINUES_FROM[node.type] : 0;
            node = output === null ? null : target(graph, node, output);
        }
        return actions;
    }

    // The actions wired to one output of a control node: its body, or a branch.
    function branch(graph, node, output, visited) {
        return chainFrom(graph, target(graph, node, output), new Set(visited));
    }

    function compileNode(graph, node, visited) {
        const props = node.properties || {};

        switch (node.type) {
            case "Control/Start":
                return null;
            case "Crystal/CrystalCombo":
                return expandCrystalCombo(props);
            case "Crystal/AutoCrystal":
                return expandAutoCrystal(props);
        }

        const type = actionByNode[node.type];
        if (!type) throw new Error("Node '" + node.type + "' cannot be compiled");
        compiling = node;
        const action = make(type, pick(schema.actions[type], props));

        if (WITH_CONDITION.has(node.type)) {
            const condition = source(graph, node, node.inputs.length - 1);
            if (!condition) throw at(node, new Error("The " + node.title + " node has no condition connected"));
            action.condition = compileNode(graph, condition, visited);
        }

        // A condition made of conditions: what is wired into it, compiled the same way.
        if (node.type === "Conditions/Not" || node.type === "Conditions/All" || node.type === "Conditions/Any") {
            if (nesting > 32) throw at(node, new Error("The conditions are wired in a circle"));
            nesting++;
            try {
                const wired = node.inputs.map((input, index) => source(graph, node, index)).filter(Boolean);
                if (wired.length === 0) {
                    if (mistakes) mistakes.push({ nodeId: node.id, node: node.title, param: null, message: "The " + node.title + " node has no condition connected" });
                } else if (node.type === "Conditions/Not") {
                    action.condition = compileNode(graph, wired[0], visited);
                } else {
                    action.conditions = wired.map(condition => compileNode(graph, condition, visited));
                }
            } finally {
                nesting--;
            }
        }

        switch (node.type) {
            case "Control/Repeat":
            case "Control/Forever":
            case "Control/While":
            case "Control/ForEach":
            case "Events/OnEvent":
                action.children = branch(graph, node, 0, visited);
                break;

            case "Control/If":
            case "Control/If-Else":
                action.children = branch(graph, node, 0, visited);
                action.elseChildren = branch(graph, node, 1, visited);
                break;

            case "Control/Sequence":
                action.children = [];
                for (let output = 0; output < node.outputs.length; output++) {
                    action.children.push(...branch(graph, node, output, visited));
                }
                break;
        }

        return action;
    }

    // Which node an error is about, so that the page can show it there.
    function at(node, error) {
        if (error.nodeId === undefined) error.nodeId = node.id;
        return error;
    }

    // The node properties that are parameters of the action; a node carries nothing else the compiler reads.
    function pick(def, props) {
        const values = {};
        for (const param of def.params) values[param.name] = props[param.name];
        return values;
    }

    // ── Macro nodes ──────────────────────────────────────────

    function expandCrystalCombo(props) {
        const combo = make("SEQUENCE");
        combo.children = [
            make("HOTBAR", { slot: props.obsidianSlot }),
            make("PLACE_BLOCK", { ticks: 1 }),
            make("DELAY", { ticks: 2 }),
            make("HOTBAR", { slot: props.crystalSlot }),
            make("PLACE_CRYSTAL", { ticks: 1 }),
            make("DELAY", { ticks: 1 }),
            make("HOTBAR", { slot: props.swordSlot }),
            make("DETONATE_CRYSTAL", { ticks: 1 }),
        ];
        return combo;
    }

    // One cycle takes "speed" ticks: place for one tick, detonate for the rest.
    function expandAutoCrystal(props) {
        const speed = Math.max(2, Math.round(Number(props.speed) || 2));
        const loop = make("LOOP", { count: Math.floor((Number(props.ticks) || 100) / speed) });
        loop.children = [
            make("HOTBAR", { slot: props.crystalSlot }),
            make("PLACE_CRYSTAL", { ticks: 1 }),
            make("DETONATE_CRYSTAL", { ticks: speed - 1 }),
        ];
        return loop;
    }

    // ── Graph helpers ────────────────────────────────────────

    // The node an output leads to, or null. Execution cannot fork, so an output may lead to one node only.
    function target(graph, node, outputIndex) {
        const output = node.outputs && node.outputs[outputIndex];
        const links = (output && output.links) || [];
        if (links.length > 1) {
            throw new Error("Output '" + output.name + "' of a " + node.title + " node leads to " + links.length + " nodes; it can only lead to one");
        }
        const link = links.length === 1 ? graph.links[links[0]] : null;
        return link ? graph.getNodeById(link.target_id) : null;
    }

    // The node an input is fed from, or null.
    function source(graph, node, inputIndex) {
        const input = node.inputs && node.inputs[inputIndex];
        const link = input && input.link != null ? graph.links[input.link] : null;
        return link ? graph.getNodeById(link.origin_id) : null;
    }

    return { setSchema, compile, compileStrictly, make, problem };
})();

if (typeof module !== "undefined") module.exports = NodeCompiler;
