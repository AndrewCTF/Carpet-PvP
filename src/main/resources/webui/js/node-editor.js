/* ═══════════════════════════════════════════════════════════════
   Node Editor — the LiteGraph canvas: its look, adding nodes, the
   view, undo, and the guide an empty canvas shows
   ═══════════════════════════════════════════════════════════════ */
const NodeEditor = (() => {

    let graph = null;
    let canvas = null;
    let schema = null;
    const listeners = [];       // called whenever the graph has changed

    const ACCENT = "#4fe0cf";
    /** A program that has just been opened is not shown smaller than this: its nodes have to be readable. */
    const READABLE = 0.7;
    // The floor the nodes stand on: a faint line every block and a stronger one every four.
    const FLOOR = "data:image/svg+xml," + encodeURIComponent(
        "<svg xmlns='http://www.w3.org/2000/svg' width='96' height='96'><rect width='96' height='96' fill='#16171a'/>"
        + "<path d='M24.5 0v96M48.5 0v96M72.5 0v96M0 24.5h96M0 48.5h96M0 72.5h96' stroke='#1b1d21'/>"
        + "<path d='M.5 0v96M0 .5h96' stroke='#24272c'/></svg>");

    // ── Initialise ───────────────────────────────────────────
    function init(actionSchema) {
        schema = actionSchema;
        graph = new LGraph();
        const element = document.getElementById("graph-canvas");
        squareCorners(element.getContext("2d"));
        canvas = new LGraphCanvas(element, graph);
        applyTheme(canvas);
        drawNodesOurWay();
        resize();

        // Give every node room for its widgets
        graph.onNodeAdded = function(node) {
            const size = node.computeSize();
            node.size[0] = Math.max(size[0], 190);
            node.size[1] = Math.max(size[1] + 10, 60);
        };

        clearGraph();
        resetHistory();

        NodeLibrary.init(LiteGraph.registered_node_types, Nodes.CATEGORIES, addNodeToCanvas, startDragNode);

        document.getElementById("zoom-in").addEventListener("click", () => zoom(1.2));
        document.getElementById("zoom-out").addEventListener("click", () => zoom(1 / 1.2));
        document.getElementById("zoom-reset").addEventListener("click", () => zoom(1 / canvas.ds.scale));
        document.getElementById("zoom-fit").addEventListener("click", () => fit());
        // The canvas is as large as the room the panels leave it, whenever that changes.
        new ResizeObserver(resize).observe(document.getElementById("canvas-area"));

        // Undo history: every edit ends with a mouse button or key being released, so that is when the graph
        // is compared with the last snapshot. Listening in the capture phase sees the events LiteGraph swallows.
        for (const type of ["mouseup", "pointerup", "keyup", "dblclick"]) {
            document.addEventListener(type, () => setTimeout(snapshot, 0), true);
        }
        canvas.onDrawForeground = showScale;
    }

    function isReady() {
        return graph !== null;
    }

    // ── Look ─────────────────────────────────────────────────
    function applyTheme(c) {
        const family = getComputedStyle(document.body).fontFamily;
        c.background_image = FLOOR;
        c.clear_background_color = "#16171a";
        c.render_canvas_border = false;
        c.render_shadows = false;
        c.show_info = false;
        c.allow_searchbox = false;
        c.render_connection_arrows = false;
        c.render_connections_border = false;
        c.connections_width = 2;
        // Links run along the grid and turn at right angles, as a line of redstone does.
        c.links_render_mode = LiteGraph.STRAIGHT_LINK;
        c.title_text_font = "600 13px " + family;
        c.inner_text_font = "12px " + family;
        c.node_title_color = "#ffffff";
        c.default_connection_color_byType = { "flow": "#c4c8ce", "condition": "#3ab3da" };
        c.default_connection_color = { input_off: "#6b7078", input_on: "#c4c8ce", output_off: "#6b7078", output_on: "#c4c8ce" };
        c.default_link_color = "#aeb3ba";
        c.ds.scale = 1;

        LiteGraph.NODE_DEFAULT_COLOR    = "#2e3136";
        LiteGraph.NODE_DEFAULT_BGCOLOR  = "#1f2125";
        LiteGraph.NODE_DEFAULT_BOXCOLOR = "#0d0e10";
        LiteGraph.NODE_BOX_OUTLINE_COLOR = ACCENT;
        LiteGraph.NODE_TITLE_HEIGHT     = 26;
        LiteGraph.NODE_TITLE_TEXT_Y     = 18;
        LiteGraph.NODE_TEXT_SIZE        = 13;
        LiteGraph.NODE_SUBTEXT_SIZE     = 12;
        LiteGraph.NODE_TEXT_COLOR       = "#b6bac0";
        LiteGraph.NODE_DEFAULT_SHAPE    = "box";

        LiteGraph.LINK_COLOR            = "#aeb3ba";
        LiteGraph.EVENT_LINK_COLOR      = "#aeb3ba";
        LiteGraph.CONNECTING_LINK_COLOR = ACCENT;

        LiteGraph.WIDGET_BGCOLOR             = "#111214";
        LiteGraph.WIDGET_OUTLINE_COLOR       = "#3d4148";
        LiteGraph.WIDGET_TEXT_COLOR          = "#eceef0";
        LiteGraph.WIDGET_SECONDARY_TEXT_COLOR = "#9a9fa7";
    }

    // LiteGraph rounds its widgets and sockets. Here everything is square, as in the game.
    function squareCorners(ctx) {
        ctx.roundRect = function(x, y, width, height) {
            this.rect(x, y, width, height);
        };
    }

    function drawNodesOurWay() {
        const drawNode = LGraphCanvas.prototype.drawNode;
        LGraphCanvas.prototype.drawNode = function(node, ctx) {
            // A title is written in the ink that reads on its category's colour, selected or not.
            LiteGraph.NODE_SELECTED_TITLE_COLOR = node.constructor.title_text_color || "#ffffff";
            drawNode.call(this, node, ctx);
            ctx.save();
            ctx.strokeStyle = "#0b0c0d";
            ctx.lineWidth = 1;
            ctx.strokeRect(-0.5, -LiteGraph.NODE_TITLE_HEIGHT - 0.5, node.size[0] + 1, node.size[1] + LiteGraph.NODE_TITLE_HEIGHT + 1);
            ctx.restore();
        };
    }

    // ── View ─────────────────────────────────────────────────
    let shownScale = 0;

    function showScale() {
        if (canvas.ds.scale === shownScale) return;
        shownScale = canvas.ds.scale;
        document.getElementById("zoom-reset").textContent = Math.round(shownScale * 100) + "%";
    }

    // Zooms about the middle of the canvas, so what is being looked at stays where it is.
    function zoom(factor) {
        const element = document.getElementById("graph-canvas");
        const scale = Math.min(2, Math.max(0.2, canvas.ds.scale * factor));
        canvas.ds.changeScale(scale, [element.width / 2, element.height / 2]);
        canvas.setDirty(true, true);
    }

    /**
     * Brings the nodes into view, at full size when they fit.
     * @param smallest how far it may zoom out; past that the program starts at the top left and runs off the canvas
     */
    function fit(smallest) {
        const nodes = graph._nodes || [];
        if (nodes.length === 0) return;
        const title = LiteGraph.NODE_TITLE_HEIGHT;
        const left = Math.min(...nodes.map(n => n.pos[0]));
        const top = Math.min(...nodes.map(n => n.pos[1] - title));
        const right = Math.max(...nodes.map(n => n.pos[0] + n.size[0]));
        const bottom = Math.max(...nodes.map(n => n.pos[1] + n.size[1]));
        const element = document.getElementById("graph-canvas");
        // Room is kept for what floats over the canvas: the view buttons underneath, and a margin all round.
        const margin = 32;
        const width = element.width - 2 * margin;
        const height = element.height - 2 * margin - 40;
        const floor = typeof smallest === "number" ? smallest : 0.2;
        const scale = Math.min(1, Math.max(floor, Math.min(width / (right - left), height / (bottom - top))));
        canvas.ds.scale = scale;
        canvas.ds.offset[0] = margin / scale - left + Math.max(0, (width / scale - (right - left)) / 2);
        canvas.ds.offset[1] = margin / scale - top + Math.max(0, (height / scale - (bottom - top)) / 4);
        canvas.setDirty(true, true);
    }

    // LiteGraph sizes the canvas itself, and only draws again when it was the one to change the size: a
    // canvas whose size is set from outside is wiped and stays blank until something else redraws it.
    function resize() {
        const area = document.getElementById("canvas-area");
        if (area.clientWidth === 0 || area.clientHeight === 0) return;
        canvas.resize(area.clientWidth, area.clientHeight);
        canvas.setDirty(true, true);
    }

    // ── Adding nodes ─────────────────────────────────────────

    // A node picked in the library goes after the node that is selected and is wired to it, so that picking
    // one after the other builds a chain. A condition goes under the node that is waiting for one. With
    // nothing selected the node is put in the middle of the view, for the user to wire.
    function addNodeToCanvas(type) {
        const node = LiteGraph.createNode(type);
        if (!node) return;
        const anchor = anchorNode();
        graph.add(node);
        const size = node.size;
        const gap = 50;
        let follows = anchor;
        if (anchor && isCondition(node)) {
            const input = (anchor.inputs || []).findIndex(slot => slot.type === "condition" && slot.link == null);
            node.pos = freeSpot(node, anchor.pos[0] - size[0] + 40, anchor.pos[1] + anchor.size[1] + LiteGraph.NODE_TITLE_HEIGHT + gap);
            if (input >= 0) node.connect(0, anchor, input);
        } else if (anchor) {
            const output = (anchor.outputs || []).findIndex(slot => slot.type === "flow" && !(slot.links && slot.links.length));
            node.pos = freeSpot(node, anchor.pos[0] + anchor.size[0] + gap, anchor.pos[1]);
            if (output >= 0 && node.inputs && node.inputs.length && node.inputs[0].type === "flow") anchor.connect(output, node, 0);
            follows = node;
        } else {
            const area = canvas.visible_area;
            node.pos = freeSpot(node, area[0] + area[2] * 0.4, area[1] + area[3] * 0.3);
            follows = node;
        }
        canvas.selectNode(follows);
        reveal(node);
        canvas.setDirty(true, true);
        snapshot();
    }

    function isCondition(node) {
        return (!node.inputs || node.inputs.length === 0) && node.outputs && node.outputs.length === 1 && node.outputs[0].type === "condition";
    }

    // The one selected node, or Start while the canvas holds nothing else.
    function anchorNode() {
        const selected = Object.values(canvas.selected_nodes || {});
        if (selected.length === 1) return selected[0];
        const nodes = graph._nodes || [];
        return selected.length === 0 && nodes.length === 1 ? nodes[0] : null;
    }

    // The place asked for, or the first one below it where the node covers no other.
    function freeSpot(node, x, y) {
        const title = LiteGraph.NODE_TITLE_HEIGHT;
        const covers = (other) => other !== node
            && x < other.pos[0] + other.size[0] + 20 && x + node.size[0] + 20 > other.pos[0]
            && y - title < other.pos[1] + other.size[1] + 20 && y + node.size[1] + 20 > other.pos[1] - title;
        for (let tries = 0; tries < 60 && (graph._nodes || []).some(covers); tries++) y += 30;
        return [x, y];
    }

    // Moves the view just far enough for a node to be on it.
    function reveal(node) {
        const area = canvas.visible_area;
        const margin = 40;
        const right = node.pos[0] + node.size[0] + margin - (area[0] + area[2]);
        const bottom = node.pos[1] + node.size[1] + margin + 50 - (area[1] + area[3]);
        if (right > 0) canvas.ds.offset[0] -= right;
        if (bottom > 0) canvas.ds.offset[1] -= bottom;
    }

    function startDragNode(type) {
        const onUp = (ev) => {
            document.removeEventListener("mouseup", onUp);
            const rect = document.getElementById("graph-canvas").getBoundingClientRect();
            if (ev.clientX >= rect.left && ev.clientX <= rect.right && ev.clientY >= rect.top && ev.clientY <= rect.bottom) {
                const node = LiteGraph.createNode(type);
                if (node) {
                    node.pos = canvas.convertEventToCanvasOffset(ev);
                    graph.add(node);
                    canvas.selectNode(node);
                    snapshot();
                }
            }
        };
        document.addEventListener("mouseup", onUp);
    }

    // ── Undo history: snapshots of the serialised graph ──────
    const HISTORY_LIMIT = 100;
    let history = [];
    let historyIndex = -1;

    // Records the graph if it differs from the snapshot the history currently points at.
    function snapshot() {
        if (!graph) return;
        const state = JSON.stringify(graph.serialize());
        if (history[historyIndex] === state) return;
        history = history.slice(Math.max(0, historyIndex + 2 - HISTORY_LIMIT), historyIndex + 1);
        history.push(state);
        historyIndex = history.length - 1;
        changed();
    }

    function restore(index) {
        historyIndex = index;
        graph.configure(JSON.parse(history[index]));
        canvas.setDirty(true, true);
        changed();
    }

    function undo() {
        if (!graph) return;
        snapshot();
        if (historyIndex > 0) restore(historyIndex - 1);
    }

    function redo() {
        if (graph && historyIndex < history.length - 1) restore(historyIndex + 1);
    }

    function resetHistory() {
        history = [];
        historyIndex = -1;
        snapshot();
    }

    function onChange(listener) {
        listeners.push(listener);
    }

    function changed() {
        updateGuide();
        listeners.forEach(listener => listener());
    }

    // ── The guide on an empty canvas ─────────────────────────
    /** Whether the canvas holds nothing but where a program starts. */
    function isEmpty() {
        return !graph || (graph._nodes || []).every(node => node.type === "Control/Start");
    }

    function updateGuide() {
        document.getElementById("guide").classList.toggle("hidden", !graph || !isEmpty());
    }

    // ── Graph content ────────────────────────────────────────
    function clearGraph() {
        graph.clear();
        const start = LiteGraph.createNode("Control/Start");
        start.pos = [60, 80];
        graph.add(start);
        canvas.ds.scale = 1;
        canvas.ds.offset[0] = 0;
        canvas.ds.offset[1] = 0;
        canvas.setDirty(true, true);
        snapshot();
    }

    /** Shows a graph saved earlier with getGraphJSON. */
    function loadGraphJSON(data) {
        graph.configure(data);
        fit(READABLE);
        resetHistory();
    }

    /** Shows a program that has no saved graph by building one from its actions. */
    function loadActions(actions) {
        GraphBuilder.build(graph, schema, actions);
        fit(READABLE);
        resetHistory();
    }

    function getGraph() { return graph; }
    function getGraphJSON() { return graph ? graph.serialize() : null; }

    return { init, isReady, getGraph, getGraphJSON, clearGraph, loadGraphJSON, loadActions, undo, redo, resetHistory, snapshot,
        fit, onChange, isEmpty };
})();
