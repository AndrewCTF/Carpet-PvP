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

    function compile(graph) {
        if (!schema) throw new Error("The action schema has not been loaded");
        const start = (graph._nodes || []).find(n => n.type === "Control/Start");
        if (!start) throw new Error("No Start node found. Add a Control/Start node.");
        return followChain(graph, start, 0, new Set());
    }

    function followChain(graph, node, outputIndex, visited) {
        const actions = [];
        let current = node;
        let outIdx = outputIndex;

        while (current) {
            if (visited.has(current.id)) break;
            visited.add(current.id);

            const action = compileNode(graph, current, visited);
            if (action) {
                if (Array.isArray(action)) actions.push(...action);
                else actions.push(action);
            }

            const nextNode = getConnectedNode(graph, current, outIdx);
            current = nextNode;
            outIdx = 0;
        }
        return actions;
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
                action.children = followChain(graph, node, 0, new Set(visited));
                // body output is index 0, done is index 1
                const bodyNode = getConnectedNode(graph, node, 0);
                if (bodyNode) action.children = followChain(graph, bodyNode, 0, new Set(visited));
                break;

            case "Control/Forever":
                action.children = [];
                const foreverBody = getConnectedNode(graph, node, 0);
                if (foreverBody) action.children = followChain(graph, foreverBody, 0, new Set(visited));
                break;

            case "Control/If-Else":
                action.children = [];
                action.elseChildren = [];
                // then = output 0, else = output 1
                const thenNode = getConnectedNode(graph, node, 0);
                if (thenNode) action.children = followChain(graph, thenNode, 0, new Set(visited));
                const elseNode = getConnectedNode(graph, node, 1);
                if (elseNode) action.elseChildren = followChain(graph, elseNode, 0, new Set(visited));
                // condition input (index 1)
                const condNode = getConnectedInput(graph, node, 1);
                if (!condNode) throw new Error("An If / Else node has no condition connected");
                action.condition = compileNode(graph, condNode, new Set(visited));
                break;

            case "Control/Sequence":
                action.children = [];
                for (let i = 0; i < 4; i++) {
                    const seqNode = getConnectedNode(graph, node, i);
                    if (seqNode) {
                        action.children.push(...followChain(graph, seqNode, 0, new Set(visited)));
                    }
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

    function getConnectedNode(graph, node, outputIndex) {
        if (!node.outputs || !node.outputs[outputIndex]) return null;
        const links = node.outputs[outputIndex].links;
        if (!links || links.length === 0) return null;
        const link = graph.links[links[0]];
        if (!link) return null;
        return graph.getNodeById(link.target_id);
    }

    function getConnectedInput(graph, node, inputIndex) {
        if (!node.inputs || !node.inputs[inputIndex]) return null;
        const linkId = node.inputs[inputIndex].link;
        if (linkId == null) return null;
        const link = graph.links[linkId];
        if (!link) return null;
        return graph.getNodeById(link.origin_id);
    }

    return { setSchema, compile, make };
})();

if (typeof module !== "undefined") module.exports = NodeCompiler;
