/* ═══════════════════════════════════════════════════════════════
   Node Compiler — turns the node graph into a JSON action tree

   Action types and their parameters come from the action schema the
   server sends (/api/schema). A node's properties are read by the
   parameter names the schema declares, so the compiler cannot emit a
   name the interpreter does not read.
   ═══════════════════════════════════════════════════════════════ */
const NodeCompiler = (() => {

    let schema = null;
    let actionByNode = {};      // editor node type → action type

    function setSchema(newSchema) {
        schema = newSchema;
        actionByNode = {};
        for (const [type, def] of Object.entries(schema.actions)) {
            actionByNode[def.node] = type;
        }
    }

    // ── Parameters ───────────────────────────────────────────

    function coerce(param, value) {
        switch (param.type) {
            case "bool":
                return typeof value === "boolean" ? value : param.default;
            case "string": {
                const text = value === undefined || value === null ? param.default : String(value);
                return param.options && !param.options.includes(text) ? param.default : text;
            }
            default: {
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
    };

    function compile(graph) {
        if (!schema) throw new Error("The action schema has not been loaded");
        const start = (graph._nodes || []).find(n => n.type === "Control/Start");
        if (!start) throw new Error("No Start node found. Add a Control/Start node.");
        return chainFrom(graph, start, new Set());
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
        const action = make(type, pick(schema.actions[type], props));

        switch (node.type) {
            case "Control/Repeat":
            case "Control/Forever":
                action.children = branch(graph, node, 0, visited);
                break;

            case "Control/If-Else": {
                const condition = source(graph, node, 1);
                if (!condition) throw new Error("An If / Else node has no condition connected");
                action.condition = compileNode(graph, condition, visited);
                action.children = branch(graph, node, 0, visited);
                action.elseChildren = branch(graph, node, 1, visited);
                break;
            }

            case "Control/Sequence":
                action.children = [];
                for (let output = 0; output < node.outputs.length; output++) {
                    action.children.push(...branch(graph, node, output, visited));
                }
                break;
        }

        return action;
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

    return { setSchema, compile, make };
})();

if (typeof module !== "undefined") module.exports = NodeCompiler;
