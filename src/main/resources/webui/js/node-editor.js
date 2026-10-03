/* ═══════════════════════════════════════════════════════════════
   Node Editor — Pathmind-style LiteGraph canvas, palette, theme
   ═══════════════════════════════════════════════════════════════ */
const NodeEditor = (() => {

    let graph = null;
    let canvas = null;

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
    };

    // ── Initialise ───────────────────────────────────────────
    function init() {
        graph = new LGraph();
        const el = document.getElementById("graph-canvas");

        canvas = new LGraphCanvas(el, graph);
        applyTheme(canvas);
        applyCustomRendering();
        resize();

        // Auto-compute node sizes when added
        graph.onNodeAdded = function(node) {
            var sz = node.computeSize();
            node.size[0] = Math.max(sz[0], 180);
            node.size[1] = Math.max(sz[1] + 10, 60);
        };

        // Default Start node
        const start = LiteGraph.createNode("Control/Start");
        start.pos = [200, 250];
        graph.add(start);
        graph.start();

        // Category sidebar
        document.querySelectorAll(".cat-btn").forEach(btn => {
            btn.addEventListener("click", () => openPalette(btn.dataset.category, btn));
        });

        // Palette close
        document.getElementById("palette-close").addEventListener("click", closePalette);

        // Zoom controls
        document.getElementById("zoom-in").addEventListener("click", () => {
            if (canvas) { canvas.ds.scale = Math.min(canvas.ds.scale * 1.2, 4); canvas.setDirty(true, true); }
        });
        document.getElementById("zoom-out").addEventListener("click", () => {
            if (canvas) { canvas.ds.scale = Math.max(canvas.ds.scale / 1.2, 0.15); canvas.setDirty(true, true); }
        });

        // Resize
        window.addEventListener("resize", resize);

        console.log("[NodeEditor] Initialised with " + Object.keys(CATEGORIES).length + " categories");
    }

    // ── Theme — Pathmind-style dark charcoal ─────────────────
    function applyTheme(c) {
        c.background_image = null;
        c.render_canvas_border = false;
        c.render_shadows = false;

        // Connection colors
        c.default_connection_color_byType = {
            "flow": "#a0a0a0",
            "condition": "#0ea5e9"
        };
        c.default_connection_color = "#888888";

        // Node defaults — dark charcoal
        LiteGraph.NODE_DEFAULT_COLOR    = "#2e2e2e";
        LiteGraph.NODE_DEFAULT_BGCOLOR  = "#252525";
        LiteGraph.NODE_DEFAULT_BOXCOLOR = "#444444";
        LiteGraph.NODE_TITLE_HEIGHT     = 26;
        LiteGraph.NODE_TITLE_TEXT_Y     = 17;
        LiteGraph.NODE_TEXT_SIZE        = 12;
        LiteGraph.NODE_SUBTEXT_SIZE     = 10;
        LiteGraph.NODE_DEFAULT_SHAPE    = "box";

        // Link colors
        LiteGraph.LINK_COLOR              = "#66666680";
        LiteGraph.EVENT_LINK_COLOR        = "#aaaaaa";
        LiteGraph.CONNECTING_LINK_COLOR   = "#bbbbbb";

        // Widget colors — olive/green tint for Pathmind parameter blocks
        LiteGraph.WIDGET_BGCOLOR             = "#1e2a12";
        LiteGraph.WIDGET_OUTLINE_COLOR       = "#3a5a1e";
        LiteGraph.WIDGET_TEXT_COLOR          = "#c8d8a0";
        LiteGraph.WIDGET_SECONDARY_TEXT_COLOR = "#8a9a6a";

        // Canvas background — dark charcoal
        c.clear_background_color = "#1e1e1e";

        // Grid
        c.render_grid = true;
        c.ds.scale = 1;

        // Make node text more readable
        LiteGraph.NODE_TITLE_COLOR = "#ffffff";
    }

    // ── Custom Rendering — colored borders, enhanced nodes ───
    function applyCustomRendering() {
        // Monkey-patch drawNode to add colored borders
        var origDrawNode = LGraphCanvas.prototype.drawNode;
        LGraphCanvas.prototype.drawNode = function(node, ctx) {
            // Call original drawing first
            origDrawNode.call(this, node, ctx);

            // Draw colored border around the node
            if (node.color && node.color !== LiteGraph.NODE_DEFAULT_COLOR) {
                ctx.save();
                ctx.strokeStyle = node.color;
                ctx.lineWidth = 2;
                ctx.globalAlpha = 0.7;

                var shape = node._shape || LiteGraph.BOX_SHAPE;
                var w = node.size[0];
                var h = node.size[1];
                var titleH = LiteGraph.NODE_TITLE_HEIGHT;

                if (shape === LiteGraph.BOX_SHAPE || shape === LiteGraph.CARD_SHAPE) {
                    ctx.beginPath();
                    ctx.rect(-0.5, -titleH - 0.5, w + 1, h + titleH + 1);
                    ctx.stroke();
                } else {
                    ctx.beginPath();
                    var r = 6;
                    ctx.roundRect(-0.5, -titleH - 0.5, w + 1, h + titleH + 1, r);
                    ctx.stroke();
                }

                ctx.restore();
            }
        };

        // Add "Parameter" label drawing to all registered nodes
        for (var type in LiteGraph.registered_node_types) {
            var NodeClass = LiteGraph.registered_node_types[type];
            if (!NodeClass.prototype._origOnDrawForeground) {
                (function(NC) {
                    var origFG = NC.prototype.onDrawForeground;
                    NC.prototype._origOnDrawForeground = origFG;
                    NC.prototype.onDrawForeground = function(ctx, graphCanvas) {
                        if (origFG) origFG.call(this, ctx, graphCanvas);
                        // Draw "Parameter" label if node has widgets
                        if (this.widgets && this.widgets.length > 0) {
                            ctx.save();
                            ctx.fillStyle = "#888888";
                            ctx.font = "bold 10px 'Segoe UI', sans-serif";
                            ctx.fillText("Parameter", 8, 16);
                            ctx.restore();
                        }
                    };
                })(NodeClass);
            }
        }

        // Make slot rendering slightly more square
        if (LiteGraph.SLOT_SHAPE !== undefined) {
            // Some versions support this
            LiteGraph.SLOT_SHAPE = 1; // box
        }
    }

    // ── Palette ──────────────────────────────────────────────
    let activeCat = null;

    function openPalette(catId, btn) {
        const palette = document.getElementById("node-palette");
        const nodes = document.getElementById("palette-nodes");
        const title = document.getElementById("palette-title");

        if (activeCat === catId && !palette.classList.contains("hidden")) {
            closePalette();
            return;
        }

        activeCat = catId;
        const cat = CATEGORIES[catId];
        if (!cat) return;

        title.textContent = cat.label;
        nodes.innerHTML = "";

        const types = Object.keys(LiteGraph.registered_node_types).filter(t => t.startsWith(cat.prefix + "/"));
        types.forEach(t => {
            const info = LiteGraph.registered_node_types[t];
            const label = t.split("/")[1] || t;
            const div = document.createElement("div");
            div.className = "palette-node";
            div.innerHTML = `<span class="pal-dot" style="background:${cat.color}"></span>
                             <span class="pal-label">${splitCamel(label)}</span>`;
            div.title = info ? (info.desc || label) : label;
            div.addEventListener("dblclick", () => addNodeToCanvas(t));
            div.addEventListener("mousedown", (e) => startDragNode(e, t));
            nodes.appendChild(div);
        });

        palette.classList.remove("hidden");

        document.querySelectorAll(".cat-btn").forEach(b => b.classList.remove("active"));
        if (btn) btn.classList.add("active");
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
    }

    function startDragNode(e, type) {
        const onUp = (ev) => {
            document.removeEventListener("mouseup", onUp);
            const canvasEl = document.getElementById("graph-canvas");
            const rect = canvasEl.getBoundingClientRect();
            if (ev.clientX >= rect.left && ev.clientX <= rect.right &&
                ev.clientY >= rect.top && ev.clientY <= rect.bottom) {
                const node = LiteGraph.createNode(type);
                if (node) {
                    const pos = canvas.convertEventToCanvasOffset(ev);
                    node.pos = [pos[0], pos[1]];
                    graph.add(node);
                }
            }
        };
        document.addEventListener("mouseup", onUp);
    }

    // ── Helpers ──────────────────────────────────────────────
    function splitCamel(s) {
        return s.replace(/([a-z])([A-Z])/g, "$1 $2")
                .replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2");
    }

    function resize() {
        const canvasEl = document.getElementById("graph-canvas");
        const wrap = document.getElementById("canvas-wrap");
        canvasEl.width = wrap.clientWidth;
        canvasEl.height = wrap.clientHeight;
        if (canvas) canvas.resize();
    }

    function getGraph() { return graph; }
    function getCanvas() { return canvas; }

    function clearGraph() {
        if (graph) {
            const hook = graph.onNodeAdded;
            graph.clear();
            graph.onNodeAdded = hook;
            const start = LiteGraph.createNode("Control/Start");
            start.pos = [200, 250];
            graph.add(start);
        }
    }

    function loadGraphJSON(data) {
        if (graph) graph.configure(data);
    }

    function getGraphJSON() {
        return graph ? graph.serialize() : null;
    }

    return {
        init, getGraph, getCanvas,
        clearGraph, loadGraphJSON, getGraphJSON,
        CATEGORIES, resize
    };
})();
