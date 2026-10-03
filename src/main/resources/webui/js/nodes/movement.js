/* ═══════════════════════════════════════════════════════════════
   Movement Nodes
   ═══════════════════════════════════════════════════════════════ */
(function () {
    const CAT = "Movement";
    const COLOR = "#06b6d4";
    const BG    = "#202830";

    // ── Move ──────────────────────────────────────────────────
    function MoveNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("direction", "forward");
        this.addProperty("ticks", 20);
        this.addWidget("combo", "Direction", "forward", (v) => { this.properties.direction = v; },
            { values: ["forward", "backward", "left", "right"] });
        this.addWidget("number", "Ticks", 20, (v) => { this.properties.ticks = v; },
            { min: 1, max: 6000, step: 1, precision: 0 });
        this.title = "Move";
        this.color = COLOR; this.bgcolor = BG;

    }
    MoveNode.title = "Move";
    MoveNode.desc = "Move in a direction for a duration.";
    LiteGraph.registerNodeType(CAT + "/Move", MoveNode);

    // ── Sprint ────────────────────────────────────────────────
    function SprintNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("enabled", true);
        this.addProperty("ticks", 40);
        this.addWidget("toggle", "Sprint", true, (v) => { this.properties.enabled = v; });
        this.addWidget("number", "Ticks", 40, (v) => { this.properties.ticks = v; },
            { min: 1, max: 6000, step: 1, precision: 0 });
        this.title = "Sprint";
        this.color = COLOR; this.bgcolor = BG;

    }
    SprintNode.title = "Sprint";
    SprintNode.desc = "Enable/disable sprinting.";
    LiteGraph.registerNodeType(CAT + "/Sprint", SprintNode);

    // ── Sneak ─────────────────────────────────────────────────
    function SneakNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("enabled", true);
        this.addProperty("ticks", 20);
        this.addWidget("toggle", "Sneak", true, (v) => { this.properties.enabled = v; });
        this.addWidget("number", "Ticks", 20, (v) => { this.properties.ticks = v; },
            { min: 1, max: 6000, step: 1, precision: 0 });
        this.title = "Sneak";
        this.color = COLOR; this.bgcolor = BG;

    }
    SneakNode.title = "Sneak";
    SneakNode.desc = "Enable/disable sneaking (shift).";
    LiteGraph.registerNodeType(CAT + "/Sneak", SneakNode);

    // ── Jump ──────────────────────────────────────────────────
    function JumpNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("ticks", 1);
        this.addWidget("number", "Ticks", 1, (v) => { this.properties.ticks = v; },
            { min: 1, max: 200, step: 1, precision: 0 });
        this.title = "Jump";
        this.color = COLOR; this.bgcolor = BG;

    }
    JumpNode.title = "Jump";
    JumpNode.desc = "Press jump key.";
    LiteGraph.registerNodeType(CAT + "/Jump", JumpNode);

    // ── Strafe ────────────────────────────────────────────────
    function StrafeNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("direction", "left");
        this.addProperty("ticks", 10);
        this.addWidget("combo", "Direction", "left", (v) => { this.properties.direction = v; },
            { values: ["left", "right"] });
        this.addWidget("number", "Ticks", 10, (v) => { this.properties.ticks = v; },
            { min: 1, max: 6000, step: 1, precision: 0 });
        this.title = "Strafe";
        this.color = COLOR; this.bgcolor = BG;

    }
    StrafeNode.title = "Strafe";
    StrafeNode.desc = "Strafe sideways while maintaining look direction.";
    LiteGraph.registerNodeType(CAT + "/Strafe", StrafeNode);

    // ── Mount ─────────────────────────────────────────────────
    function MountNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("onlyRideables", true);
        this.addWidget("toggle", "Only Rideables", true, (v) => { this.properties.onlyRideables = v; });
        this.title = "Mount";
        this.color = COLOR; this.bgcolor = BG;

    }
    MountNode.title = "Mount";
    MountNode.desc = "Mount nearest entity (horse, boat, etc).";
    LiteGraph.registerNodeType(CAT + "/Mount", MountNode);

    // ── Dismount ──────────────────────────────────────────────
    function DismountNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.title = "Dismount";
        this.color = COLOR; this.bgcolor = BG;

    }
    DismountNode.title = "Dismount";
    DismountNode.desc = "Dismount current vehicle/entity.";
    LiteGraph.registerNodeType(CAT + "/Dismount", DismountNode);

    // ── Stop All Movement ─────────────────────────────────────
    function StopMovementNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.title = "Stop All Movement";
        this.color = COLOR; this.bgcolor = BG;

    }
    StopMovementNode.title = "Stop All Movement";
    StopMovementNode.desc = "Stop all movement, sprint, and sneak.";
    LiteGraph.registerNodeType(CAT + "/StopMovement", StopMovementNode);

})();
