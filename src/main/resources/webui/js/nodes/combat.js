/* ═══════════════════════════════════════════════════════════════
   Combat Nodes
   ═══════════════════════════════════════════════════════════════ */
(function () {
    const CAT = "Combat";
    const COLOR = "#f43f5e";
    const BG    = "#2d2025";

    // ── Attack ────────────────────────────────────────────────
    function AttackNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("ticks", 1);
        this.addProperty("continuous", false);
        this.addWidget("number", "Ticks", 1, (v) => { this.properties.ticks = v; },
            { min: 1, max: 6000, step: 1, precision: 0 });
        this.addWidget("toggle", "Continuous", false, (v) => { this.properties.continuous = v; });
        this.title = "Attack";
        this.color = COLOR; this.bgcolor = BG;

    }
    AttackNode.title = "Attack";
    AttackNode.desc = "Left-click attack. Continuous = spam click.";
    LiteGraph.registerNodeType(CAT + "/Attack", AttackNode);

    // ── Critical Attack ───────────────────────────────────────
    function CritAttackNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("ticks", 15);
        this.addWidget("number", "Ticks", 15, (v) => { this.properties.ticks = v; },
            { min: 10, max: 100, step: 1, precision: 0 });
        this.title = "Critical Attack";
        this.color = COLOR; this.bgcolor = BG;

    }
    CritAttackNode.title = "Critical Attack";
    CritAttackNode.desc = "Jump → fall → hit for critical damage.";
    LiteGraph.registerNodeType(CAT + "/CritAttack", CritAttackNode);

    // ── Sword Block ───────────────────────────────────────────
    function SwordBlockNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("ticks", 40);
        this.addWidget("number", "Ticks", 40, (v) => { this.properties.ticks = v; },
            { min: 1, max: 6000, step: 1, precision: 0 });
        this.title = "Sword Block";
        this.color = COLOR; this.bgcolor = BG;

    }
    SwordBlockNode.title = "Sword Block";
    SwordBlockNode.desc = "Block with sword (Carpet-PvP sword blocking).";
    LiteGraph.registerNodeType(CAT + "/SwordBlock", SwordBlockNode);

    // ── Shield Block ──────────────────────────────────────────
    function ShieldBlockNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("ticks", 40);
        this.addWidget("number", "Ticks", 40, (v) => { this.properties.ticks = v; },
            { min: 1, max: 6000, step: 1, precision: 0 });
        this.title = "Shield Block";
        this.color = COLOR; this.bgcolor = BG;

    }
    ShieldBlockNode.title = "Shield Block";
    ShieldBlockNode.desc = "Block with shield in offhand (USE action).";
    LiteGraph.registerNodeType(CAT + "/ShieldBlock", ShieldBlockNode);

    // ── Use Item ──────────────────────────────────────────────
    function UseItemNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("ticks", 1);
        this.addProperty("continuous", false);
        this.addWidget("number", "Ticks", 1, (v) => { this.properties.ticks = v; },
            { min: 1, max: 6000, step: 1, precision: 0 });
        this.addWidget("toggle", "Continuous", false, (v) => { this.properties.continuous = v; });
        this.title = "Use Item";
        this.color = COLOR; this.bgcolor = BG;

    }
    UseItemNode.title = "Use Item";
    UseItemNode.desc = "Right-click use (eat, throw, place, interact).";
    LiteGraph.registerNodeType(CAT + "/UseItem", UseItemNode);

})();
