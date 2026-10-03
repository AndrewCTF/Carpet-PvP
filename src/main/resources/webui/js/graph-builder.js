/* ═══════════════════════════════════════════════════════════════
   Graph Builder — lays out an action tree as a node graph

   The reverse of the compiler, for programs that come without a graph
   (the built-in presets). Compiling the result gives the same actions
   back, except that a SEQUENCE is spliced into its chain: it means the
   same and needs no node.
   ═══════════════════════════════════════════════════════════════ */
const GraphBuilder = (() => {

    const COLUMN = 250;     // horizontal distance between the nodes of a chain
    const GAP = 50;         // vertical space between a row and the bodies laid out under it

    // Output a node's chain continues from: the mirror of the compiler's table.
    const CONTINUES_FROM = { LOOP: 1, IF_THEN_ELSE: 2, FOREVER: null };

    /** Fills the graph with a Start node followed by nodes for the actions. */
    function build(graph, schema, actions) {
        graph.clear();
        const start = LiteGraph.createNode("Control/Start");
        start.pos = [60, 80];
        graph.add(start);
        chain(graph, schema, actions, start, 0, 60 + COLUMN, 80);
    }

    // Lays the actions out as one row starting at (x, y), linked from the given output, with the bodies of
    // control nodes in rows of their own underneath. Returns the y below everything it placed.
    function chain(graph, schema, actions, from, output, x, y) {
        const row = [];
        let bottom = y;
        for (const action of flatten(actions)) {
            if (output === null) break;         // nothing can follow a Forever
            const node = LiteGraph.createNode(schema.actions[action.type].node);
            for (const [name, value] of Object.entries(action.params || {})) node.setProperty(name, value);
            node.pos = [x, y];
            graph.add(node);
            from.connect(output, node, 0);
            row.push([action, node]);
            bottom = Math.max(bottom, y + node.size[1]);
            from = node;
            output = action.type in CONTINUES_FROM ? CONTINUES_FROM[action.type] : 0;
            x += COLUMN;
        }
        bottom += GAP;
        for (const [action, node] of row) {
            if (action.condition) {
                const condition = LiteGraph.createNode(schema.actions[action.condition.type].node);
                for (const [name, value] of Object.entries(action.condition.params || {})) condition.setProperty(name, value);
                condition.pos = [node.pos[0] - COLUMN + 20, bottom];
                graph.add(condition);
                condition.connect(0, node, 1);
                bottom += condition.size[1] + GAP;
            }
            if (action.children && action.children.length > 0) {
                bottom = chain(graph, schema, action.children, node, 0, node.pos[0] + COLUMN, bottom);
            }
            if (action.elseChildren && action.elseChildren.length > 0) {
                bottom = chain(graph, schema, action.elseChildren, node, 1, node.pos[0] + COLUMN, bottom);
            }
        }
        return bottom;
    }

    // A SEQUENCE in a chain runs its children and carries on: the same as its children in its place.
    function flatten(actions) {
        return actions.flatMap(action => action.type === "SEQUENCE" ? flatten(action.children || []) : [action]);
    }

    return { build, flatten };
})();

if (typeof module !== "undefined") module.exports = GraphBuilder;
