/* ═══════════════════════════════════════════════════════════════
   Nodes — the editor's node types, generated from the action schema

   A node's properties and widgets are exactly the parameters the schema
   declares for the action it compiles to, so a node can never offer a
   setting the server does not know. Only what the schema cannot say is
   written here: titles, descriptions and the sockets of control nodes.
   ═══════════════════════════════════════════════════════════════ */
const Nodes = (() => {

    const CATEGORY_STYLE = {
        Control:    { color: "#f59e0b", bg: "#2d2820" },
        Movement:   { color: "#06b6d4", bg: "#202830" },
        Combat:     { color: "#f43f5e", bg: "#2d2025" },
        Equipment:  { color: "#f97316", bg: "#2d2820" },
        Look:       { color: "#8b5cf6", bg: "#252030" },
        Navigation: { color: "#22c55e", bg: "#202d22" },
        Elytra:     { color: "#10b981", bg: "#202d28" },
        Crystal:    { color: "#ec4899", bg: "#2d2028" },
        Conditions: { color: "#0ea5e9", bg: "#202530" },
        Variables:  { color: "#eab308", bg: "#2d2b1c" },
        Events:     { color: "#d946ef", bg: "#2d2030" },
    };

    const FLOW_IN = [["in", "flow"]];
    const FLOW_OUT = [["next", "flow"]];

    // Sockets default to one flow input and one "next" output; condition nodes have a single condition output.
    const NODES = {
        "Control/Start":           { title: "Start", desc: "Program entry point: execution begins here.", inputs: [] },
        "Control/Delay":           { title: "Delay", desc: "Wait for a number of ticks (20 ticks = 1 second)." },
        "Control/WaitUntil":       { title: "Wait Until", desc: "Hold here until the condition is true, or the timeout runs out. Ticks is the timeout.",
                                     inputs: [["in", "flow"], ["condition", "condition"]] },
        "Control/Repeat":          { title: "Repeat", desc: "Run the body a number of times, then continue.",
                                     outputs: [["body", "flow"], ["done", "flow"]] },
        "Control/Forever":         { title: "Forever", desc: "Run the body again and again until the program is stopped.",
                                     outputs: [["body", "flow"]] },
        "Control/If-Else":         { title: "If / Else", desc: "Run one branch or the other, depending on a condition, then continue.",
                                     inputs: [["in", "flow"], ["condition", "condition"]],
                                     outputs: [["then", "flow"], ["else", "flow"], ["done", "flow"]] },
        "Control/Sequence":        { title: "Sequence", desc: "Run the outputs in order, 1 to 4.",
                                     outputs: [["1", "flow"], ["2", "flow"], ["3", "flow"], ["4", "flow"]] },
        "Control/ExecuteCommand":  { title: "Execute Command", desc: "Run a command as you, with your permissions." },

        "Variables/Set":           { title: "Set Variable", desc: "Give a variable a number. Any number field takes one instead of a number." },
        "Variables/Add":           { title: "Add To Variable", desc: "Add a number to a variable, or take it away.", },

        "Movement/Move":           { title: "Move", desc: "Walk in a direction for a number of ticks." },
        "Movement/Strafe":         { title: "Strafe", desc: "Step sideways for a number of ticks." },
        "Movement/Sprint":         { title: "Sprint", desc: "Turn sprinting on or off. Ticks is how long to wait before the next node." },
        "Movement/Sneak":          { title: "Sneak", desc: "Turn sneaking on or off. Ticks is how long to wait before the next node." },
        "Movement/Jump":           { title: "Jump", desc: "Jump once." },
        "Movement/Mount":          { title: "Mount", desc: "Mount the nearest entity (horse, boat, minecart)." },
        "Movement/Dismount":       { title: "Dismount", desc: "Leave the current vehicle." },
        "Movement/StopMovement":   { title: "Stop All Movement", desc: "Stop walking, sprinting and sneaking." },

        "Combat/Attack":           { title: "Attack", desc: "Left click: once, or repeatedly for a number of ticks." },
        "Combat/CritAttack":       { title: "Critical Attack", desc: "Jump and hit while falling." },
        "Combat/SwordBlock":       { title: "Sword Block", desc: "Block with a sword (needs the swordBlockHitting rule)." },
        "Combat/ShieldBlock":      { title: "Shield Block", desc: "Hold a shield up for a number of ticks." },
        "Combat/UseItem":          { title: "Use Item", desc: "Right click: eat, throw, place or interact." },
        "Combat/CombatStart":      { title: "Start Combat AI", desc: "Hand the bot to its combat AI. While a combat node is active the brain drives the body, so movement and click steps around it are skipped. CombatStop gives the body back." },
        "Combat/CombatStop":       { title: "Stop Combat AI", desc: "Turn the combat AI off and release everything the style left running." },
        "Combat/Fight":            { title: "Fight", desc: "Start Combat AI, wait out the fight, then Stop: one node. Ends when the target or the bot is gone, when the target stays out of range for rangeTicks, or at the timeout." },
        "Combat/CombatOption":     { title: "Set Combat Option", desc: "One setting of the bot's combat AI, by the name /bot option takes, including the options only one style reads." },
        "Combat/GiveKit":          { title: "Give Kit", desc: "Put a kit on the bot, clearing what it was carrying, as /bot kit give does." },

        "Equipment/Hotbar":        { title: "Hotbar Select", desc: "Switch to a hotbar slot (1-9)." },
        "Equipment/EquipArmor":    { title: "Equip Armor", desc: "Put on a full armor set." },
        "Equipment/EquipSlot":     { title: "Equip Slot", desc: "Put an item in an equipment slot." },
        "Equipment/Unequip":       { title: "Unequip", desc: "Empty one equipment slot, or all of them." },
        "Equipment/Drop":          { title: "Drop Item", desc: "Drop one item from the selected slot." },
        "Equipment/DropStack":     { title: "Drop Stack", desc: "Drop the whole stack from the selected slot." },
        "Equipment/SwapHands":     { title: "Swap Hands", desc: "Swap the main hand and off hand items." },

        "Look/LookDirection":      { title: "Look Direction", desc: "Face a cardinal direction, up or down." },
        "Look/LookAt":             { title: "Look At Position", desc: "Look at world coordinates." },
        "Look/LookAtPlayer":       { title: "Look At Player", desc: "Look at a player. Leave the name empty for the nearest one." },
        "Look/LookYawPitch":       { title: "Look Yaw/Pitch", desc: "Set exact yaw and pitch angles." },
        "Look/Turn":               { title: "Turn", desc: "Turn by a yaw and pitch relative to the current facing." },

        "Navigation/NavGoto":      { title: "Navigate To", desc: "Pathfind to world coordinates, then continue." },
        "Navigation/NavStop":      { title: "Nav Stop", desc: "Cancel the current navigation." },
        "Navigation/FollowPlayer": { title: "Follow Player", desc: "Follow a player for a number of ticks. Leave the name empty for the nearest one." },
        "Navigation/ChasePlayer":  { title: "Chase Player", desc: "Run a player down and attack within range, for a number of ticks. Interval 0 attacks as fast as possible." },
        "Navigation/Patrol":       { title: "Patrol", desc: "Walk back and forth between two points for a number of ticks." },
        "Navigation/FleeFrom":     { title: "Flee From", desc: "Run away from a player until far enough or out of ticks." },
        "Navigation/Wander":       { title: "Wander", desc: "Walk to random spots within a radius for a number of ticks." },

        "Elytra/GlideStart":       { title: "Glide Start", desc: "Start elytra gliding (needs an elytra equipped)." },
        "Elytra/GlideStop":        { title: "Glide Stop", desc: "Stop elytra gliding." },
        "Elytra/GlideGoto":        { title: "Glide Goto", desc: "Fly to coordinates, then continue." },
        "Elytra/GlideHeading":     { title: "Glide Heading", desc: "Fly in a yaw/pitch direction." },
        "Elytra/GlideSpeed":       { title: "Glide Speed", desc: "Set the glide speed in blocks per tick." },
        "Elytra/GlideFreeze":      { title: "Glide Freeze", desc: "Hover in place mid-air, or release the hover." },
        "Elytra/GlideLand":        { title: "Glide Land", desc: "Land when the glide destination is reached." },

        "Crystal/PlaceCrystal":    { title: "Place Crystal", desc: "Right click with an end crystal in hand, looking at obsidian or bedrock." },
        "Crystal/DetonateCrystal": { title: "Detonate Crystal", desc: "Hit the end crystal the bot is looking at." },
        "Crystal/PlaceBlock":      { title: "Place Block", desc: "Right click with a block in hand." },

        "Events/OnEvent":          { title: "On Event", desc: "Register a reaction, then carry on. Its body takes over from the sequence when the event happens, and the sequence resumes where it was. An event that happens again while the body is still running is ignored.",
                                     outputs: [["body", "flow"], ["next", "flow"]] },

        "Conditions/Health":       { title: "Health Check", desc: "Compare the bot's health (0-20)." },
        "Conditions/IsFighting":     { title: "Is Fighting", desc: "True while the combat AI is on and the bot has a target." },
        "Conditions/HasTarget":     { title: "Has Target", desc: "True while the combat AI has a target, fighting one or not." },
        "Conditions/TargetDistance": { title: "Target Distance Check", desc: "Compare the distance to the combat target. Infinite while there is none." },
        "Conditions/TargetHealth":  { title: "Target Health Check", desc: "Compare the combat target's health. Infinite while there is none, as for the distance." },
        "Conditions/Distance":     { title: "Distance Check", desc: "Compare the distance to a player. Leave the target empty for the nearest one." },
        "Conditions/Food":         { title: "Food Check", desc: "Compare the bot's food level (0-20)." },
        "Conditions/Armor":        { title: "Armor Check", desc: "Compare the bot's armor points." },
        "Conditions/Variable":      { title: "Variable Check", desc: "Compare a variable with a number. A variable that was never set is 0." },
        "Conditions/Random":       { title: "Random Chance", desc: "True with the given probability." },
        "Conditions/HasItem":      { title: "Has Item", desc: "True when the bot carries the item." },
        "Conditions/IsFlying":     { title: "Is Flying", desc: "True while the bot is gliding." },
        "Conditions/IsSneaking":   { title: "Is Sneaking", desc: "True while the bot is sneaking." },
        "Conditions/IsSprinting":  { title: "Is Sprinting", desc: "True while the bot is sprinting." },
        "Conditions/IsInWater":    { title: "Is In Water", desc: "True while the bot is in water." },
    };

    // Macro nodes are not actions: the compiler expands each into several. Their settings are their own.
    const SLOT = { type: "int", min: 1, max: 9 };
    const MACROS = {
        "Crystal/CrystalCombo": {
            title: "Crystal Combo", desc: "Where the bot is looking: place obsidian, place a crystal on it, switch to the sword and detonate.",
            params: [
                { name: "obsidianSlot", default: 1, ...SLOT },
                { name: "crystalSlot", default: 2, ...SLOT },
                { name: "swordSlot", default: 3, ...SLOT },
            ]
        },
        "Crystal/AutoCrystal": {
            title: "Auto Crystal", desc: "Place and detonate crystals for a number of ticks. Speed is the ticks one cycle takes.",
            params: [
                { name: "crystalSlot", default: 2, ...SLOT },
                { name: "ticks", type: "int", default: 100, min: 10, max: 60000 },
                { name: "speed", type: "int", default: 2, min: 2, max: 20 },
            ]
        },
    };

    // What the server reports and the schema cannot: the kits this server's folder holds, and every name
    // /bot option takes. Both are filled in from GET /api/settings, so a kit folder or a new option needs no
    // change here. The option key stays free text in the schema, so that a key the server does not know is
    // reported by the server, with the message /bot option gives, rather than quietly lost in the editor.
    let settings = {};

    const FROM_SERVER = {
        "Combat/GiveKit": { kit: "kits" },
        "Combat/CombatOption": { key: "combatOptions" },
    };

    function labelFor(name) {
        const spaced = name.replace(/([a-z])([A-Z0-9])/g, "$1 $2");
        return spaced.charAt(0).toUpperCase() + spaced.slice(1);
    }

    function addParam(node, p, fromServer) {
        node.addProperty(p.name, p.default);
        const label = labelFor(p.name);
        // A parameter the schema marks optionsFrom names the list the server sends for it under the same name.
        const served = fromServer || p.optionsFrom;
        // The widget is bound to the property by name, so loading a graph or setting the property updates it.
        if (p.type === "bool") {
            node.addWidget("toggle", label, p.default, p.name);
        } else if (p.options) {
            node.addWidget("combo", label, p.default, p.name, { values: p.options });
        } else if (served && (settings[served] || []).length > 0) {
            // A list the server sent: a dropdown of it, but still typed into, so that a name it does not
            // know yet reaches the server to be complained about there.
            node.addWidget("combo", label, p.default, p.name, { values: settings[served] });
        } else {
            // A number is text too, so that a variable reference can be typed where a number goes.
            node.addWidget("text", label, p.default, p.name);
        }
    }

    function define(type, ui, params, isCondition) {
        const style = CATEGORY_STYLE[type.split("/")[0]];
        const inputs = ui.inputs || (isCondition ? [] : FLOW_IN);
        const outputs = ui.outputs || (isCondition ? [["condition", "condition"]] : FLOW_OUT);
        const fromServer = FROM_SERVER[type] || {};
        function Node() {
            inputs.forEach(([name, kind]) => this.addInput(name, kind));
            outputs.forEach(([name, kind]) => this.addOutput(name, kind));
            params.forEach(p => addParam(this, p, fromServer[p.name]));
            this.title = ui.title;
            this.color = style.color;
            this.bgcolor = style.bg;
        }
        Node.title = ui.title;
        Node.desc = ui.desc;
        LiteGraph.registerNodeType(type, Node);
    }

    /** Registers every node type. Only these are offered: LiteGraph's own demo nodes are removed. */
    function register(schema, serverSettings) {
        settings = serverSettings || {};
        LiteGraph.clearRegisteredTypes();
        define("Control/Start", NODES["Control/Start"], [], false);
        for (const def of Object.values(schema.actions)) {
            const ui = NODES[def.node];
            if (!ui) throw new Error("No editor node is defined for " + def.node);
            define(def.node, ui, def.params, def.kind === "condition");
        }
        for (const [type, macro] of Object.entries(MACROS)) {
            define(type, macro, macro.params, false);
        }
    }

    return { register, NODES, MACROS };
})();

if (typeof module !== "undefined") module.exports = Nodes;
