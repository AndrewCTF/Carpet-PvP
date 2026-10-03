/* ═══════════════════════════════════════════════════════════════
   Navigation & Pathfinding Nodes
   ═══════════════════════════════════════════════════════════════ */
(function () {
    const CAT = "Navigation";
    const COLOR = "#22c55e";
    const BG    = "#202d22";

    // ── Navigate To ───────────────────────────────────────────
    function NavGotoNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("x", 0);
        this.addProperty("y", 64);
        this.addProperty("z", 0);
        this.addWidget("number", "X", 0, (v) => { this.properties.x = v; }, { step: 1, precision: 0 });
        this.addWidget("number", "Y", 64, (v) => { this.properties.y = v; }, { step: 1, precision: 0 });
        this.addWidget("number", "Z", 0, (v) => { this.properties.z = v; }, { step: 1, precision: 0 });
        this.title = "Navigate To";
        this.color = COLOR; this.bgcolor = BG;

    }
    NavGotoNode.title = "Navigate To";
    NavGotoNode.desc = "A* pathfind to world coordinates.";
    LiteGraph.registerNodeType(CAT + "/NavGoto", NavGotoNode);

    // ── Nav Stop ──────────────────────────────────────────────
    function NavStopNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.title = "Nav Stop";
        this.color = COLOR; this.bgcolor = BG;

    }
    NavStopNode.title = "Nav Stop";
    NavStopNode.desc = "Cancel current pathfinding navigation.";
    LiteGraph.registerNodeType(CAT + "/NavStop", NavStopNode);

    // ── Nav Mode ──────────────────────────────────────────────
    function NavModeNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("mode", "land");
        this.addWidget("combo", "Mode", "land", (v) => { this.properties.mode = v; },
            { values: ["land", "auto", "water", "air"] });
        this.title = "Nav Mode";
        this.color = COLOR; this.bgcolor = BG;

    }
    NavModeNode.title = "Nav Mode";
    NavModeNode.desc = "Set navigation pathfinding mode.";
    LiteGraph.registerNodeType(CAT + "/NavMode", NavModeNode);

    // ── Follow Player ─────────────────────────────────────────
    function FollowPlayerNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("player", "");
        this.addProperty("distance", 3);
        this.addProperty("ticks", 200);
        this.addWidget("text", "Player Name", "", (v) => { this.properties.player = v; });
        this.addWidget("number", "Distance", 3, (v) => { this.properties.distance = v; },
            { min: 1, max: 64, step: 1, precision: 0 });
        this.addWidget("number", "Ticks", 200, (v) => { this.properties.ticks = v; },
            { min: 1, max: 60000, step: 10, precision: 0 });
        this.title = "Follow Player";
        this.color = COLOR; this.bgcolor = BG;

    }
    FollowPlayerNode.title = "Follow Player";
    FollowPlayerNode.desc = "Follow a player maintaining distance.";
    LiteGraph.registerNodeType(CAT + "/FollowPlayer", FollowPlayerNode);

    // ── Flee From ─────────────────────────────────────────────
    function FleeFromNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("player", "");
        this.addProperty("distance", 16);
        this.addProperty("ticks", 100);
        this.addWidget("text", "Player Name", "", (v) => { this.properties.player = v; });
        this.addWidget("number", "Distance", 16, (v) => { this.properties.distance = v; },
            { min: 5, max: 128, step: 1, precision: 0 });
        this.addWidget("number", "Ticks", 100, (v) => { this.properties.ticks = v; },
            { min: 1, max: 60000, step: 10, precision: 0 });
        this.title = "Flee From";
        this.color = COLOR; this.bgcolor = BG;

    }
    FleeFromNode.title = "Flee From";
    FleeFromNode.desc = "Run away from a player.";
    LiteGraph.registerNodeType(CAT + "/FleeFrom", FleeFromNode);

    // ── Wander ────────────────────────────────────────────────
    function WanderNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("radius", 16);
        this.addProperty("ticks", 200);
        this.addWidget("number", "Radius", 16, (v) => { this.properties.radius = v; },
            { min: 5, max: 128, step: 1, precision: 0 });
        this.addWidget("number", "Ticks", 200, (v) => { this.properties.ticks = v; },
            { min: 1, max: 60000, step: 10, precision: 0 });
        this.title = "Wander";
        this.color = COLOR; this.bgcolor = BG;

    }
    WanderNode.title = "Wander";
    WanderNode.desc = "Randomly wander in a radius.";
    LiteGraph.registerNodeType(CAT + "/Wander", WanderNode);

})();
