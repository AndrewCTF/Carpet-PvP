/* ═══════════════════════════════════════════════════════════════
   Look / Aim Nodes
   ═══════════════════════════════════════════════════════════════ */
(function () {
    const CAT = "Look";
    const COLOR = "#8b5cf6";
    const BG    = "#252030";

    // ── Look Direction ────────────────────────────────────────
    function LookDirectionNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("direction", "north");
        this.addWidget("combo", "Direction", "north", (v) => { this.properties.direction = v; },
            { values: ["north", "south", "east", "west", "up", "down"] });
        this.title = "Look Direction";
        this.color = COLOR; this.bgcolor = BG;

    }
    LookDirectionNode.title = "Look Direction";
    LookDirectionNode.desc = "Snap look to a cardinal direction.";
    LiteGraph.registerNodeType(CAT + "/LookDirection", LookDirectionNode);

    // ── Look At Position ──────────────────────────────────────
    function LookAtNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("x", 0);
        this.addProperty("y", 64);
        this.addProperty("z", 0);
        this.addWidget("number", "X", 0, (v) => { this.properties.x = v; }, { step: 1, precision: 1 });
        this.addWidget("number", "Y", 64, (v) => { this.properties.y = v; }, { step: 1, precision: 1 });
        this.addWidget("number", "Z", 0, (v) => { this.properties.z = v; }, { step: 1, precision: 1 });
        this.title = "Look At Position";
        this.color = COLOR; this.bgcolor = BG;

    }
    LookAtNode.title = "Look At Position";
    LookAtNode.desc = "Look at specific world coordinates.";
    LiteGraph.registerNodeType(CAT + "/LookAt", LookAtNode);

    // ── Look At Player ────────────────────────────────────────
    function LookAtPlayerNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("player", "");
        this.addWidget("text", "Player Name", "", (v) => { this.properties.player = v; });
        this.title = "Look At Player";
        this.color = COLOR; this.bgcolor = BG;

    }
    LookAtPlayerNode.title = "Look At Player";
    LookAtPlayerNode.desc = "Track and look at a player by name.";
    LiteGraph.registerNodeType(CAT + "/LookAtPlayer", LookAtPlayerNode);

    // ── Look Yaw/Pitch ────────────────────────────────────────
    function LookYawPitchNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("yaw", 0);
        this.addProperty("pitch", 0);
        this.addWidget("number", "Yaw", 0, (v) => { this.properties.yaw = v; },
            { min: -180, max: 180, step: 1, precision: 1 });
        this.addWidget("number", "Pitch", 0, (v) => { this.properties.pitch = v; },
            { min: -90, max: 90, step: 1, precision: 1 });
        this.title = "Look Yaw/Pitch";
        this.color = COLOR; this.bgcolor = BG;

    }
    LookYawPitchNode.title = "Look Yaw/Pitch";
    LookYawPitchNode.desc = "Set exact yaw and pitch angles.";
    LiteGraph.registerNodeType(CAT + "/LookYawPitch", LookYawPitchNode);

    // ── Turn ──────────────────────────────────────────────────
    function TurnNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("yaw", 90);
        this.addProperty("pitch", 0);
        this.addWidget("number", "Yaw Δ", 90, (v) => { this.properties.yaw = v; },
            { min: -360, max: 360, step: 1, precision: 1 });
        this.addWidget("number", "Pitch Δ", 0, (v) => { this.properties.pitch = v; },
            { min: -180, max: 180, step: 1, precision: 1 });
        this.title = "Turn";
        this.color = COLOR; this.bgcolor = BG;

    }
    TurnNode.title = "Turn";
    TurnNode.desc = "Turn relative yaw/pitch from current facing.";
    LiteGraph.registerNodeType(CAT + "/Turn", TurnNode);

})();
