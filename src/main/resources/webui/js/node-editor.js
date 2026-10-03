/* ═══════════════════════════════════════════════════════════════
   Node Editor — LiteGraph canvas, palette, theme, undo
   ═══════════════════════════════════════════════════════════════ */
const NodeEditor = (() => {

    let graph = null;
    let canvas = null;
    let schema = null;

    // Sidebar categories. A category lists every registered node type under its prefix.
    const CATEGORIES = {
        control:    { label: "Control Flow",   color: "#f59e0b", prefix: "Control" },
        movement:   { label: "Movement",       color: "#06b6d4", prefix: "Movement" },
        combat:     { label: "Combat",         color: "#f43f5e", prefix: "Combat" },
        equipment:  { label: "Equipment",      color: "#f97316", prefix: "Equipment" },
        look:       { label: "Look / Aim",     color: "#8b5cf6", prefix: "Look" },
        navigation: { label: "Navigation",     color: "#22c55e", prefix: "Navigation" },
        elytra:     { label: "Elytra Flight",  color: "#10b981", prefix: "Elytra" },
        crystal:    { label: "Crystal PvP",    color: "#ec4899", prefix: "Crystal" },
        condition:  { label: "Conditions",     color: "#0ea5e9", prefix: "Conditions" },
        variables:   { label: "Variables",      color: "#eab308", prefix: "Variables" },
        events:      { label: "Events",         color: "#d946ef", prefix: "Events" },
    };

    // ── Initialise ───────────────────────────────────────────
    function init(actionSchema) {
        schema = actionSchema;
        graph = new LGraph();
        canvas = new LGraphCanvas(document.getElementById("graph-canvas"), graph);
        applyTheme(canvas);
        drawNodeBorders();
        resize();

        // Give every node room for its widgets
        graph.onNodeAdded = function(node) {
            const size = node.computeSize();
            node.size[0] = Math.max(size[0], 180);
            node.size[1] = Math.max(size[1] + 10, 60);
        };

        clearGraph();
        resetHistory();

        document.querySelectorAll(".cat-btn").forEach(btn => {
            btn.addEventListener("click", () => openPalette(btn.dataset.category, btn));
        });
        document.getElementById("palette-close").addEventListener("click", closePalette);

        document.getElementById("zoom-in").addEventListener("click", () => zoom(1.2));
        document.getElementById("zoom-out").addEventListener("click", () => zoom(1 / 1.2));
        window.addEventListener("resize", resize);

        // Undo history: every edit ends with a mouse button or key being released, so that is when the graph
        // is compared with the last snapshot. Listening in the capture phase sees the events LiteGraph swallows.
        for (const type of ["mouseup", "pointerup", "keyup", "dblclick"]) {
            document.addEventListener(type, () => setTimeout(snapshot, 0), true);
        }
    }

    function zoom(factor) {
        canvas.ds.scale = Math.min(4, Math.max(0.15, canvas.ds.scale * factor));
        canvas.setDirty(true, true);
    }

    // ── Theme: dark charcoal ─────────────────────────────────
    function applyTheme(c) {
        c.background_image = null;
        c.render_canvas_border = false;
        c.render_shadows = false;
        c.show_info = false;
        c.allow_searchbox = false;
        c.node_title_color = "#111111";
        c.default_connection_color_byType = { "flow": "#a0a0a0", "condition": "#0ea5e9" };
        c.default_connection_color = "#888888";
        c.clear_background_color = "#1e1e1e";
        c.render_grid = true;
        c.ds.scale = 1;

        LiteGraph.NODE_DEFAULT_COLOR    = "#2e2e2e";
        LiteGraph.NODE_DEFAULT_BGCOLOR  = "#252525";
        LiteGraph.NODE_DEFAULT_BOXCOLOR = "#444444";
        LiteGraph.NODE_SELECTED_TITLE_COLOR = "#000000";
        LiteGraph.NODE_TITLE_HEIGHT     = 26;
        LiteGraph.NODE_TITLE_TEXT_Y     = 17;
        LiteGraph.NODE_TEXT_SIZE        = 12;
        LiteGraph.NODE_SUBTEXT_SIZE     = 10;
        LiteGraph.NODE_DEFAULT_SHAPE    = "box";

        LiteGraph.LINK_COLOR            = "#66666680";
        LiteGraph.EVENT_LINK_COLOR      = "#aaaaaa";
        LiteGraph.CONNECTING_LINK_COLOR = "#bbbbbb";

        LiteGraph.WIDGET_BGCOLOR             = "#1e2a12";
        LiteGraph.WIDGET_OUTLINE_COLOR       = "#3a5a1e";
        LiteGraph.WIDGET_TEXT_COLOR          = "#c8d8a0";
        LiteGraph.WIDGET_SECONDARY_TEXT_COLOR = "#8a9a6a";
    }

    // Outline each node in its category colour
    function drawNodeBorders() {
        const drawNode = LGraphCanvas.prototype.drawNode;
        LGraphCanvas.prototype.drawNode = function(node, ctx) {
            drawNode.call(this, node, ctx);
            if (node.color && node.color !== LiteGraph.NODE_DEFAULT_COLOR) {
                ctx.save();
                ctx.strokeStyle = node.color;
                ctx.lineWidth = 2;
                ctx.globalAlpha = 0.7;
                ctx.strokeRect(-0.5, -LiteGraph.NODE_TITLE_HEIGHT - 0.5, node.size[0] + 1, node.size[1] + LiteGraph.NODE_TITLE_HEIGHT + 1);
                ctx.restore();
            }
        };
    }

    // ── Palette ──────────────────────────────────────────────
    let activeCat = null;

    function openPalette(catId, btn) {
        const palette = document.getElementById("node-palette");
        if (activeCat === catId && !palette.classList.contains("hidden")) {
            closePalette();
            return;
        }
        const cat = CATEGORIES[catId];
        if (!cat) return;
        activeCat = catId;

        document.getElementById("palette-title").textContent = cat.label;
        const nodes = document.getElementById("palette-nodes");
        nodes.replaceChildren();
        const types = Object.keys(LiteGraph.registered_node_types).filter(t => t.startsWith(cat.prefix + "/"));
        for (const type of types) {
            const info = LiteGraph.registered_node_types[type];
            const dot = el("span", "pal-dot");
            dot.style.background = cat.color;
            const item = el("div", "palette-node");
            item.append(dot, el("span", "pal-label", info.title));
            item.title = info.desc || info.title;
            item.addEventListener("dblclick", () => addNodeToCanvas(type));
            item.addEventListener("mousedown", () => startDragNode(type));
            nodes.append(item);
        }

        palette.classList.remove("hidden");
        document.querySelectorAll(".cat-btn").forEach(b => b.classList.toggle("active", b === btn));
    }

    function closePalette() {
        document.getElementById("node-palette").classList.add("hidden");
        document.querySelectorAll(".cat-btn").forEach(b => b.classList.remove("active"));
        activeCat = null;
    }

    function addNodeToCanvas(type) {
        const node = LiteGraph.createNode(type);
        if (!node) return;
        const area = canvas.visible_area;
        node.pos = [
            area[0] + area[2] * 0.4 + Math.random() * 100,
            area[1] + area[3] * 0.4 + Math.random() * 100
        ];
        graph.add(node);
        snapshot();
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
    }

    function restore(index) {
        historyIndex = index;
        graph.configure(JSON.parse(history[index]));
        canvas.setDirty(true, true);
    }

    function undo() {
        snapshot();
        if (historyIndex > 0) restore(historyIndex - 1);
    }

    function redo() {
        if (historyIndex < history.length - 1) restore(historyIndex + 1);
    }

    function resetHistory() {
        history = [];
        historyIndex = -1;
        snapshot();
    }

    // ── Graph content ────────────────────────────────────────
    function resize() {
        const wrap = document.getElementById("canvas-wrap");
        const canvasEl = document.getElementById("graph-canvas");
        canvasEl.width = wrap.clientWidth;
        canvasEl.height = wrap.clientHeight;
        if (canvas) canvas.resize();
    }

    function clearGraph() {
        graph.clear();
        const start = LiteGraph.createNode("Control/Start");
        start.pos = [200, 250];
        graph.add(start);
        canvas.setDirty(true, true);
        snapshot();
    }

    /** Shows a graph saved earlier with getGraphJSON. */
    function loadGraphJSON(data) {
        graph.configure(data);
        canvas.setDirty(true, true);
        resetHistory();
    }

    /** Shows a program that has no saved graph by building one from its actions. */
    function loadActions(actions) {
        GraphBuilder.build(graph, schema, actions);
        canvas.ds.offset = [0, 0];
        canvas.setDirty(true, true);
        resetHistory();
    }

    function getGraph() { return graph; }
    function getGraphJSON() { return graph ? graph.serialize() : null; }

    return { init, getGraph, getGraphJSON, clearGraph, loadGraphJSON, loadActions, undo, redo, resetHistory, snapshot };
})();
