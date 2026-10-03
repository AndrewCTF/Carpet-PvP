/* ═══════════════════════════════════════════════════════════════
   Equipment & Inventory Nodes
   ═══════════════════════════════════════════════════════════════ */
(function () {
    const CAT = "Equipment";
    const COLOR = "#f97316";
    const BG    = "#2d2820";

    // ── Hotbar Select ─────────────────────────────────────────
    function HotbarNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("slot", 0);
        this.addWidget("number", "Slot (0-8)", 0, (v) => { this.properties.slot = Math.min(8, Math.max(0, Math.round(v))); },
            { min: 0, max: 8, step: 1, precision: 0 });
        this.title = "Hotbar Select";
        this.color = COLOR; this.bgcolor = BG;

    }
    HotbarNode.title = "Hotbar Select";
    HotbarNode.desc = "Switch to hotbar slot (0-8).";
    LiteGraph.registerNodeType(CAT + "/Hotbar", HotbarNode);

    // ── Equip Armor ───────────────────────────────────────────
    function EquipArmorNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("armorSet", "diamond");
        this.addWidget("combo", "Armor Set", "diamond", (v) => { this.properties.armorSet = v; },
            { values: ["leather", "chainmail", "iron", "gold", "diamond", "netherite"] });
        this.title = "Equip Armor";
        this.color = COLOR; this.bgcolor = BG;

    }
    EquipArmorNode.title = "Equip Armor";
    EquipArmorNode.desc = "Equip a full armor set.";
    LiteGraph.registerNodeType(CAT + "/EquipArmor", EquipArmorNode);

    // ── Equip Slot ────────────────────────────────────────────
    function EquipSlotNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("slot", "mainhand");
        this.addProperty("item", "diamond_sword");
        this.addWidget("combo", "Slot", "mainhand", (v) => { this.properties.slot = v; },
            { values: ["mainhand", "offhand", "head", "chest", "legs", "feet"] });
        this.addWidget("text", "Item", "diamond_sword", (v) => { this.properties.item = v; });
        this.title = "Equip Slot";
        this.color = COLOR; this.bgcolor = BG;

    }
    EquipSlotNode.title = "Equip Slot";
    EquipSlotNode.desc = "Equip a specific item to an equipment slot.";
    LiteGraph.registerNodeType(CAT + "/EquipSlot", EquipSlotNode);

    // ── Unequip ───────────────────────────────────────────────
    function UnequipNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.title = "Unequip All";
        this.color = COLOR; this.bgcolor = BG;

    }
    UnequipNode.title = "Unequip All";
    UnequipNode.desc = "Remove all equipped armor and items.";
    LiteGraph.registerNodeType(CAT + "/Unequip", UnequipNode);

    // ── Drop Item ─────────────────────────────────────────────
    function DropNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("ticks", 1);
        this.addWidget("number", "Ticks", 1, (v) => { this.properties.ticks = v; },
            { min: 1, max: 200, step: 1, precision: 0 });
        this.title = "Drop Item";
        this.color = COLOR; this.bgcolor = BG;

    }
    DropNode.title = "Drop Item";
    DropNode.desc = "Drop one item from current slot.";
    LiteGraph.registerNodeType(CAT + "/Drop", DropNode);

    // ── Drop Stack ────────────────────────────────────────────
    function DropStackNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.addProperty("ticks", 1);
        this.addWidget("number", "Ticks", 1, (v) => { this.properties.ticks = v; },
            { min: 1, max: 200, step: 1, precision: 0 });
        this.title = "Drop Stack";
        this.color = COLOR; this.bgcolor = BG;

    }
    DropStackNode.title = "Drop Stack";
    DropStackNode.desc = "Drop entire stack from current slot.";
    LiteGraph.registerNodeType(CAT + "/DropStack", DropStackNode);

    // ── Swap Hands ────────────────────────────────────────────
    function SwapHandsNode() {
        this.addInput("in", "flow");
        this.addOutput("next", "flow");
        this.title = "Swap Hands";
        this.color = COLOR; this.bgcolor = BG;

    }
    SwapHandsNode.title = "Swap Hands";
    SwapHandsNode.desc = "Swap mainhand and offhand items.";
    LiteGraph.registerNodeType(CAT + "/SwapHands", SwapHandsNode);

})();
