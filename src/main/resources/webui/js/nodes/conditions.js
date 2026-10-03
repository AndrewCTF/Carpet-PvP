/* ═══════════════════════════════════════════════════════════════
   Condition Nodes
   ═══════════════════════════════════════════════════════════════ */
(function () {
    const CAT = "Conditions";
    const COLOR = "#0ea5e9";
    const BG    = "#202530";

    // ── Health Check ──────────────────────────────────────────
    function HealthCondNode() {
        this.addOutput("condition", "condition");
        this.addProperty("operator", "<");
        this.addProperty("value", 10);
        this.addWidget("combo", "Operator", "<", (v) => { this.properties.operator = v; },
            { values: ["<", "<=", ">", ">=", "=="] });
        this.addWidget("number", "HP", 10, (v) => { this.properties.value = v; },
            { min: 0, max: 20, step: 0.5, precision: 1 });
        this.title = "Health Check";
        this.color = COLOR; this.bgcolor = BG;

    }
    HealthCondNode.title = "Health Check";
    HealthCondNode.desc = "Check bot health (0-20 HP).";
    LiteGraph.registerNodeType(CAT + "/Health", HealthCondNode);

    // ── Distance Check ────────────────────────────────────────
    function DistanceCondNode() {
        this.addOutput("condition", "condition");
        this.addProperty("target", "");
        this.addProperty("operator", "<");
        this.addProperty("value", 5);
        this.addWidget("text", "Target", "", (v) => { this.properties.target = v; });
        this.addWidget("combo", "Operator", "<", (v) => { this.properties.operator = v; },
            { values: ["<", "<=", ">", ">=", "=="] });
        this.addWidget("number", "Distance", 5, (v) => { this.properties.value = v; },
            { min: 0, max: 256, step: 1, precision: 1 });
        this.title = "Distance Check";
        this.color = COLOR; this.bgcolor = BG;

    }
    DistanceCondNode.title = "Distance Check";
    DistanceCondNode.desc = "Check distance to a player or coordinates.";
    LiteGraph.registerNodeType(CAT + "/Distance", DistanceCondNode);

    // ── Food Check ────────────────────────────────────────────
    function FoodCondNode() {
        this.addOutput("condition", "condition");
        this.addProperty("operator", "<");
        this.addProperty("value", 10);
        this.addWidget("combo", "Operator", "<", (v) => { this.properties.operator = v; },
            { values: ["<", "<=", ">", ">=", "=="] });
        this.addWidget("number", "Food Level", 10, (v) => { this.properties.value = v; },
            { min: 0, max: 20, step: 1, precision: 0 });
        this.title = "Food Check";
        this.color = COLOR; this.bgcolor = BG;

    }
    FoodCondNode.title = "Food Check";
    FoodCondNode.desc = "Check food/hunger level (0-20).";
    LiteGraph.registerNodeType(CAT + "/Food", FoodCondNode);

    // ── Random Chance ─────────────────────────────────────────
    function RandomCondNode() {
        this.addOutput("condition", "condition");
        this.addProperty("chance", 50);
        this.addWidget("number", "Chance (%)", 50, (v) => { this.properties.chance = v; },
            { min: 0, max: 100, step: 1, precision: 0 });
        this.title = "Random Chance";
        this.color = COLOR; this.bgcolor = BG;

    }
    RandomCondNode.title = "Random Chance";
    RandomCondNode.desc = "Random true/false with configurable probability.";
    LiteGraph.registerNodeType(CAT + "/Random", RandomCondNode);

    // ── Has Item ──────────────────────────────────────────────
    function HasItemCondNode() {
        this.addOutput("condition", "condition");
        this.addProperty("item", "end_crystal");
        this.addWidget("text", "Item ID", "end_crystal", (v) => { this.properties.item = v; });
        this.title = "Has Item";
        this.color = COLOR; this.bgcolor = BG;

    }
    HasItemCondNode.title = "Has Item";
    HasItemCondNode.desc = "Check if bot has an item in inventory.";
    LiteGraph.registerNodeType(CAT + "/HasItem", HasItemCondNode);

    // ── Is Flying ─────────────────────────────────────────────
    function IsFlyingCondNode() {
        this.addOutput("condition", "condition");
        this.title = "Is Flying";
        this.color = COLOR; this.bgcolor = BG;

    }
    IsFlyingCondNode.title = "Is Flying";
    IsFlyingCondNode.desc = "Check if bot is currently flying/gliding.";
    LiteGraph.registerNodeType(CAT + "/IsFlying", IsFlyingCondNode);

    // ── Is Sneaking ───────────────────────────────────────────
    function IsSneakingCondNode() {
        this.addOutput("condition", "condition");
        this.title = "Is Sneaking";
        this.color = COLOR; this.bgcolor = BG;

    }
    IsSneakingCondNode.title = "Is Sneaking";
    IsSneakingCondNode.desc = "Check if bot is sneaking.";
    LiteGraph.registerNodeType(CAT + "/IsSneaking", IsSneakingCondNode);

    // ── Is Sprinting ──────────────────────────────────────────
    function IsSprintingCondNode() {
        this.addOutput("condition", "condition");
        this.title = "Is Sprinting";
        this.color = COLOR; this.bgcolor = BG;

    }
    IsSprintingCondNode.title = "Is Sprinting";
    IsSprintingCondNode.desc = "Check if bot is sprinting.";
    LiteGraph.registerNodeType(CAT + "/IsSprinting", IsSprintingCondNode);

    // ── Is In Water ───────────────────────────────────────────
    function IsInWaterCondNode() {
        this.addOutput("condition", "condition");
        this.title = "Is In Water";
        this.color = COLOR; this.bgcolor = BG;

    }
    IsInWaterCondNode.title = "Is In Water";
    IsInWaterCondNode.desc = "Check if bot is in water.";
    LiteGraph.registerNodeType(CAT + "/IsInWater", IsInWaterCondNode);

    // ── Armor Check ───────────────────────────────────────────
    function ArmorCondNode() {
        this.addOutput("condition", "condition");
        this.addProperty("operator", "<");
        this.addProperty("value", 10);
        this.addWidget("combo", "Operator", "<", (v) => { this.properties.operator = v; },
            { values: ["<", "<=", ">", ">=", "=="] });
        this.addWidget("number", "Armor Value", 10, (v) => { this.properties.value = v; },
            { min: 0, max: 20, step: 1, precision: 0 });
        this.title = "Armor Check";
        this.color = COLOR; this.bgcolor = BG;

    }
    ArmorCondNode.title = "Armor Check";
    ArmorCondNode.desc = "Check total armor points.";
    LiteGraph.registerNodeType(CAT + "/Armor", ArmorCondNode);

})();
