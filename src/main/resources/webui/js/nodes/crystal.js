/* ═══════════════════════════════════════════════════════════════
   Crystal PvP Nodes
   ═══════════════════════════════════════════════════════════════ */
(function () {
    const CAT = "Crystal";
    const COLOR = "#ec4899";
    const BG    = "#2d2028";

    // ── Place Crystal ─────────────────────────────────────────
    function PlaceCrystalNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("ticks", 1);
        this.addWidget("number", "Ticks", 1, (v) => { this.properties.ticks = v; },
            { min: 1, max: 100, step: 1, precision: 0 });
        this.title = "Place Crystal";
        this.color = COLOR; this.bgcolor = BG;

    }
    PlaceCrystalNode.title = "Place Crystal";
    PlaceCrystalNode.desc = "Place an End Crystal (requires crystal in hand, looking at obsidian/bedrock).";
    LiteGraph.registerNodeType(CAT + "/PlaceCrystal", PlaceCrystalNode);

    // ── Detonate Crystal ──────────────────────────────────────
    function DetonateCrystalNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("ticks", 1);
        this.addWidget("number", "Ticks", 1, (v) => { this.properties.ticks = v; },
            { min: 1, max: 100, step: 1, precision: 0 });
        this.title = "Detonate Crystal";
        this.color = COLOR; this.bgcolor = BG;

    }
    DetonateCrystalNode.title = "Detonate Crystal";
    DetonateCrystalNode.desc = "Hit (attack) an End Crystal to detonate it.";
    LiteGraph.registerNodeType(CAT + "/DetonateCrystal", DetonateCrystalNode);

    // ── Place Block ───────────────────────────────────────────
    function PlaceBlockNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("ticks", 1);
        this.addWidget("number", "Ticks", 1, (v) => { this.properties.ticks = v; },
            { min: 1, max: 100, step: 1, precision: 0 });
        this.title = "Place Block";
        this.color = COLOR; this.bgcolor = BG;

    }
    PlaceBlockNode.title = "Place Block";
    PlaceBlockNode.desc = "Place block (obsidian for crystal base, etc). Requires block in hand.";
    LiteGraph.registerNodeType(CAT + "/PlaceBlock", PlaceBlockNode);

    // ── Crystal Combo ─────────────────────────────────────────
    // This is a macro node that expands to: look → hotbar(obsidian) → place → hotbar(crystal) → place → attack
    function CrystalComboNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("obsidianSlot", 0);
        this.addProperty("crystalSlot", 1);
        this.addProperty("swordSlot", 2);
        this.addProperty("lookPitch", 90);
        this.addWidget("number", "Obsidian Slot", 0, (v) => { this.properties.obsidianSlot = Math.round(v); },
            { min: 0, max: 8, step: 1, precision: 0 });
        this.addWidget("number", "Crystal Slot", 1, (v) => { this.properties.crystalSlot = Math.round(v); },
            { min: 0, max: 8, step: 1, precision: 0 });
        this.addWidget("number", "Sword Slot", 2, (v) => { this.properties.swordSlot = Math.round(v); },
            { min: 0, max: 8, step: 1, precision: 0 });
        this.addWidget("number", "Look Pitch", 90, (v) => { this.properties.lookPitch = v; },
            { min: -90, max: 90, step: 1, precision: 0 });
        this.title = "Crystal Combo";
        this.color = COLOR; this.bgcolor = BG;

    }
    CrystalComboNode.title = "Crystal Combo";
    CrystalComboNode.desc = "Full crystal PvP combo: place obsidian → place crystal → detonate → switch to sword.";
    LiteGraph.registerNodeType(CAT + "/CrystalCombo", CrystalComboNode);

    // ── Auto Crystal ──────────────────────────────────────────
    function AutoCrystalNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("crystalSlot", 1);
        this.addProperty("ticks", 100);
        this.addProperty("speed", 2);
        this.addWidget("number", "Crystal Slot", 1, (v) => { this.properties.crystalSlot = Math.round(v); },
            { min: 0, max: 8, step: 1, precision: 0 });
        this.addWidget("number", "Duration (ticks)", 100, (v) => { this.properties.ticks = v; },
            { min: 10, max: 60000, step: 10, precision: 0 });
        this.addWidget("number", "Speed (ticks/cycle)", 2, (v) => { this.properties.speed = v; },
            { min: 1, max: 20, step: 1, precision: 0 });
        this.title = "Auto Crystal";
        this.color = COLOR; this.bgcolor = BG;

    }
    AutoCrystalNode.title = "Auto Crystal";
    AutoCrystalNode.desc = "Automated crystal place+detonate loop for fast crystal PvP cycles.";
    LiteGraph.registerNodeType(CAT + "/AutoCrystal", AutoCrystalNode);

})();
