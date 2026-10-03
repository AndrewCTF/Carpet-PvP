/* ═══════════════════════════════════════════════════════════════
   Control Flow Nodes
   ═══════════════════════════════════════════════════════════════ */
(function () {
    const CAT = "Control";
    const COLOR = "#f59e0b";
    const BG    = "#2d2820";

    // ── Start ─────────────────────────────────────────────────
    function StartNode() {
        this.addOutput("next", "flow");
        this.title = "Start";
        this.color = COLOR; this.bgcolor = BG;

    }
    StartNode.title = "Start";
    StartNode.desc = "Program entry point — execution begins here.";
    LiteGraph.registerNodeType(CAT + "/Start", StartNode);

    // ── Delay ─────────────────────────────────────────────────
    function DelayNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("ticks", 20);
        this.widget = this.addWidget("number", "Ticks", 20, (v) => { this.properties.ticks = v; }, { min: 1, max: 6000, step: 1, precision: 0 });
        this.title = "Delay";
        this.color = COLOR; this.bgcolor = BG;

    }
    DelayNode.title = "Delay";
    DelayNode.desc = "Wait for a number of ticks (20 ticks = 1 second).";
    LiteGraph.registerNodeType(CAT + "/Delay", DelayNode);

    // ── Repeat ────────────────────────────────────────────────
    function RepeatNode() {
        this.addInput("in", "flow");
        this.addOutput("body", "flow");
        this.addOutput("done", "flow");
        this.addProperty("count", 3);
        this.addWidget("number", "Count", 3, (v) => { this.properties.count = v; }, { min: 1, max: 10000, step: 1, precision: 0 });
        this.title = "Repeat";
        this.color = COLOR; this.bgcolor = BG;

    }
    RepeatNode.title = "Repeat";
    RepeatNode.desc = "Execute body N times then continue.";
    LiteGraph.registerNodeType(CAT + "/Repeat", RepeatNode);

    // ── Forever ───────────────────────────────────────────────
    function ForeverNode() {
        this.addInput("in", "flow");
        this.addOutput("body", "flow");
        this.title = "Forever";
        this.color = COLOR; this.bgcolor = BG;

    }
    ForeverNode.title = "Forever";
    ForeverNode.desc = "Loop body indefinitely until stopped.";
    LiteGraph.registerNodeType(CAT + "/Forever", ForeverNode);

    // ── If / Else ─────────────────────────────────────────────
    function IfElseNode() {
        this.addInput("in", "flow");
        this.addInput("condition", "condition");
        this.addOutput("then", "flow");
        this.addOutput("else", "flow");
        this.title = "If / Else";
        this.color = COLOR; this.bgcolor = BG;

    }
    IfElseNode.title = "If / Else";
    IfElseNode.desc = "Branch execution based on a condition.";
    LiteGraph.registerNodeType(CAT + "/If-Else", IfElseNode);

    // ── Sequence ──────────────────────────────────────────────
    function SequenceNode() {
        this.addInput("in", "flow");
        this.addOutput("1", "flow");
        this.addOutput("2", "flow");
        this.addOutput("3", "flow");
        this.addOutput("4", "flow");
        this.title = "Sequence";
        this.color = COLOR; this.bgcolor = BG;

    }
    SequenceNode.title = "Sequence";
    SequenceNode.desc = "Execute outputs in sequence (1→2→3→4).";
    LiteGraph.registerNodeType(CAT + "/Sequence", SequenceNode);

    // ── Execute Command ───────────────────────────────────────
    function ExecCommandNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("command", "/say Hello");
        this.addWidget("text", "Command", "/say Hello", (v) => { this.properties.command = v; });
        this.title = "Execute Command";
        this.color = COLOR; this.bgcolor = BG;

    }
    ExecCommandNode.title = "Execute Command";
    ExecCommandNode.desc = "Run an arbitrary server command.";
    LiteGraph.registerNodeType(CAT + "/ExecuteCommand", ExecCommandNode);

})();
