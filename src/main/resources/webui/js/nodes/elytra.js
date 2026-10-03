/* ═══════════════════════════════════════════════════════════════
   Elytra / Flight Nodes
   ═══════════════════════════════════════════════════════════════ */
(function () {
    const CAT = "Elytra";
    const COLOR = "#10b981";
    const BG    = "#202d28";

    // ── Glide Start ───────────────────────────────────────────
    function GlideStartNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.title = "Glide Start";
        this.color = COLOR; this.bgcolor = BG;

    }
    GlideStartNode.title = "Glide Start";
    GlideStartNode.desc = "Activate elytra gliding (bot must have elytra equipped).";
    LiteGraph.registerNodeType(CAT + "/GlideStart", GlideStartNode);

    // ── Glide Stop ────────────────────────────────────────────
    function GlideStopNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.title = "Glide Stop";
        this.color = COLOR; this.bgcolor = BG;

    }
    GlideStopNode.title = "Glide Stop";
    GlideStopNode.desc = "Stop elytra gliding.";
    LiteGraph.registerNodeType(CAT + "/GlideStop", GlideStopNode);

    // ── Glide Goto ────────────────────────────────────────────
    function GlideGotoNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("x", 0);
        this.addProperty("y", 100);
        this.addProperty("z", 0);
        this.addWidget("number", "X", 0, (v) => { this.properties.x = v; }, { step: 1, precision: 0 });
        this.addWidget("number", "Y", 100, (v) => { this.properties.y = v; }, { step: 1, precision: 0 });
        this.addWidget("number", "Z", 0, (v) => { this.properties.z = v; }, { step: 1, precision: 0 });
        this.title = "Glide Goto";
        this.color = COLOR; this.bgcolor = BG;

    }
    GlideGotoNode.title = "Glide Goto";
    GlideGotoNode.desc = "Fly to coordinates using A* elytra pathfinding.";
    LiteGraph.registerNodeType(CAT + "/GlideGoto", GlideGotoNode);

    // ── Glide Heading ─────────────────────────────────────────
    function GlideHeadingNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("yaw", 0);
        this.addProperty("pitch", -5);
        this.addWidget("number", "Yaw", 0, (v) => { this.properties.yaw = v; },
            { min: -180, max: 180, step: 1, precision: 1 });
        this.addWidget("number", "Pitch", -5, (v) => { this.properties.pitch = v; },
            { min: -90, max: 90, step: 1, precision: 1 });
        this.title = "Glide Heading";
        this.color = COLOR; this.bgcolor = BG;

    }
    GlideHeadingNode.title = "Glide Heading";
    GlideHeadingNode.desc = "Fly in a direction (yaw/pitch heading mode).";
    LiteGraph.registerNodeType(CAT + "/GlideHeading", GlideHeadingNode);

    // ── Glide Speed ───────────────────────────────────────────
    function GlideSpeedNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("speed", 1.0);
        this.addWidget("number", "Speed", 1.0, (v) => { this.properties.speed = v; },
            { min: 0.1, max: 5.0, step: 0.1, precision: 1 });
        this.title = "Glide Speed";
        this.color = COLOR; this.bgcolor = BG;

    }
    GlideSpeedNode.title = "Glide Speed";
    GlideSpeedNode.desc = "Set elytra glide speed multiplier.";
    LiteGraph.registerNodeType(CAT + "/GlideSpeed", GlideSpeedNode);

    // ── Glide Freeze ──────────────────────────────────────────
    function GlideFreezeNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.title = "Glide Freeze";
        this.color = COLOR; this.bgcolor = BG;

    }
    GlideFreezeNode.title = "Glide Freeze";
    GlideFreezeNode.desc = "Freeze in place mid-air (hover).";
    LiteGraph.registerNodeType(CAT + "/GlideFreeze", GlideFreezeNode);

    // ── Glide Land ────────────────────────────────────────────
    function GlideLandNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.title = "Glide Land";
        this.color = COLOR; this.bgcolor = BG;

    }
    GlideLandNode.title = "Glide Land";
    GlideLandNode.desc = "Land safely — descend to ground.";
    LiteGraph.registerNodeType(CAT + "/GlideLand", GlideLandNode);

})();
