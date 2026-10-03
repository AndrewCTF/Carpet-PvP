/* ═══════════════════════════════════════════════════════════════
   Node Compiler — Converts LiteGraph → CarpetLogic JSON actions
   ═══════════════════════════════════════════════════════════════ */
const NodeCompiler = (() => {

    // ── Node-type → ActionType mapping ───────────────────────
    const TYPE_MAP = {
        // Control
        "Control/Start":          null,
        "Control/Delay":          "DELAY",
        "Control/Repeat":         "LOOP",
        "Control/Forever":        "FOREVER",
        "Control/If-Else":        "IF_THEN_ELSE",
        "Control/Sequence":       "SEQUENCE",
        "Control/ExecuteCommand": "EXECUTE_COMMAND",
        // Movement
        "Movement/Move":          "MOVE",
        "Movement/Sprint":        "SPRINT",
        "Movement/Sneak":         "SNEAK",
        "Movement/Jump":          "JUMP",
        "Movement/Strafe":        "STRAFE",
        "Movement/Mount":         "MOUNT",
        "Movement/Dismount":      "DISMOUNT",
        "Movement/StopMovement":  "STOP_MOVEMENT",
        // Combat
        "Combat/Attack":          "ATTACK",
        "Combat/CritAttack":      "ATTACK_CRIT",
        "Combat/SwordBlock":      "SWORD_BLOCK",
        "Combat/ShieldBlock":     "SHIELD_BLOCK",
        "Combat/UseItem":         "USE",
        // Equipment
        "Equipment/Hotbar":       "HOTBAR",
        "Equipment/EquipArmor":   "EQUIP_ARMOR",
        "Equipment/EquipSlot":    "EQUIP_SLOT",
        "Equipment/Unequip":      "UNEQUIP",
        "Equipment/Drop":         "DROP",
        "Equipment/DropStack":    "DROP_STACK",
        "Equipment/SwapHands":    "SWAP_HANDS",
        // Look
        "Look/LookDirection":     "LOOK_DIRECTION",
        "Look/LookAt":            "LOOK_AT",
        "Look/LookAtPlayer":      "LOOK_AT",
        "Look/LookYawPitch":      "LOOK_YAW_PITCH",
        "Look/Turn":              "TURN",
        // Navigation
        "Navigation/NavGoto":     "NAV_GOTO",
        "Navigation/NavStop":     "NAV_STOP",
        "Navigation/NavMode":     "NAV_MODE",
        "Navigation/FollowPlayer":"FOLLOW_PLAYER",
        "Navigation/FleeFrom":    "FLEE_FROM",
        "Navigation/Wander":      "WANDER",
        // Elytra
        "Elytra/GlideStart":     "GLIDE_START",
        "Elytra/GlideStop":      "GLIDE_STOP",
        "Elytra/GlideGoto":      "GLIDE_GOTO",
        "Elytra/GlideHeading":   "GLIDE_HEADING",
        "Elytra/GlideSpeed":     "GLIDE_SPEED",
        "Elytra/GlideFreeze":    "GLIDE_FREEZE",
        "Elytra/GlideLand":      "GLIDE_LAND",
        // Crystal PvP
        "Crystal/PlaceCrystal":  "PLACE_CRYSTAL",
        "Crystal/DetonateCrystal":"DETONATE_CRYSTAL",
        "Crystal/PlaceBlock":    "PLACE_BLOCK",
        "Crystal/CrystalCombo":  null,       // macro — expanded inline
        "Crystal/AutoCrystal":   null,       // macro — expanded inline
        // Conditions
        "Conditions/Health":     "CONDITION_HEALTH",
        "Conditions/Distance":   "CONDITION_DISTANCE",
        "Conditions/Food":       "CONDITION_FOOD",
        "Conditions/Random":     "CONDITION_RANDOM",
        "Conditions/HasItem":    "CONDITION_HAS_ITEM",
        "Conditions/IsFlying":   "CONDITION_IS_FLYING",
        "Conditions/IsSneaking": "CONDITION_IS_SNEAKING",
        "Conditions/IsSprinting":"CONDITION_IS_SPRINTING",
        "Conditions/IsInWater":  "CONDITION_IS_IN_WATER",
        "Conditions/Armor":      "CONDITION_ARMOR",
    };

    // ── Find Start node ──────────────────────────────────────
    function findStartNode(graph) {
        const nodes = graph._nodes || [];
        return nodes.find(n => n.type === "Control/Start");
    }

    // ── Compile graph to actions array ───────────────────────
    function compile(graph) {
        const start = findStartNode(graph);
        if (!start) throw new Error("No Start node found. Add a Control/Start node.");
        const visited = new Set();
        return followChain(graph, start, 0, visited);
    }

    // ── Follow the output chain ─────────────────────────────
    function followChain(graph, node, outputIndex, visited) {
        const actions = [];
        let current = node;
        let outIdx = outputIndex;

        while (current) {
            if (visited.has(current.id)) break;
            visited.add(current.id);

            const action = compileNode(graph, current, visited);
            if (action) {
                if (Array.isArray(action)) actions.push(...action);
                else actions.push(action);
            }

            // Follow the "next" or first output
            const nextNode = getConnectedNode(graph, current, outIdx);
            current = nextNode;
            outIdx = 0;
        }
        return actions;
    }

    // ── Compile a single node ────────────────────────────────
    function compileNode(graph, node, visited) {
        const type = node.type;
        const props = node.properties || {};

        // Start node — skip
        if (type === "Control/Start") return null;

        // Crystal Combo macro — expand to sequence
        if (type === "Crystal/CrystalCombo") {
            return expandCrystalCombo(props);
        }

        // Auto Crystal macro — expand to loop
        if (type === "Crystal/AutoCrystal") {
            return expandAutoCrystal(props);
        }

        const actionType = TYPE_MAP[type];
        if (!actionType) return null;

        const action = { type: actionType, params: {} };

        // Duration
        if (props.ticks !== undefined) action.duration = Number(props.ticks);

        // Build params based on node type
        switch (type) {
            case "Control/Delay":
                action.duration = Number(props.ticks) || 20;
                break;

            case "Control/Repeat":
                action.type = "LOOP";
                action.params.count = Number(props.count) || 3;
                action.children = followChain(graph, node, 0, new Set(visited)) ;
                // body output is index 0, done is index 1
                const bodyNode = getConnectedNode(graph, node, 0);
                if (bodyNode) action.children = followChain(graph, bodyNode, 0, new Set(visited));
                break;

            case "Control/Forever":
                action.children = [];
                const foreverBody = getConnectedNode(graph, node, 0);
                if (foreverBody) action.children = followChain(graph, foreverBody, 0, new Set(visited));
                break;

            case "Control/If-Else":
                action.children = [];
                action.elseChildren = [];
                // then = output 0, else = output 1
                const thenNode = getConnectedNode(graph, node, 0);
                if (thenNode) action.children = followChain(graph, thenNode, 0, new Set(visited));
                const elseNode = getConnectedNode(graph, node, 1);
                if (elseNode) action.elseChildren = followChain(graph, elseNode, 0, new Set(visited));
                // condition input (index 1)
                const condNode = getConnectedInput(graph, node, 1);
                if (condNode) action.condition = compileNode(graph, condNode, new Set(visited));
                break;

            case "Control/Sequence":
                action.children = [];
                for (let i = 0; i < 4; i++) {
                    const seqNode = getConnectedNode(graph, node, i);
                    if (seqNode) {
                        action.children.push(...followChain(graph, seqNode, 0, new Set(visited)));
                    }
                }
                break;

            case "Control/ExecuteCommand":
                action.params.command = props.command || "/say Hello";
                break;

            // Movement
            case "Movement/Move":
                action.params.direction = props.direction || "forward";
                action.duration = Number(props.ticks) || 20;
                break;
            case "Movement/Sprint":
                action.params.enabled = props.enabled !== false;
                action.duration = Number(props.ticks) || 40;
                break;
            case "Movement/Sneak":
                action.params.enabled = props.enabled !== false;
                action.duration = Number(props.ticks) || 20;
                break;
            case "Movement/Jump":
                action.duration = Number(props.ticks) || 1;
                break;
            case "Movement/Strafe":
                action.params.direction = props.direction || "left";
                action.duration = Number(props.ticks) || 10;
                break;
            case "Movement/Mount":
                action.params.onlyRideables = props.onlyRideables !== false;
                break;

            // Combat
            case "Combat/Attack":
                action.params.continuous = props.continuous === true;
                action.duration = Number(props.ticks) || 1;
                break;
            case "Combat/CritAttack":
                action.duration = Number(props.ticks) || 15;
                break;
            case "Combat/SwordBlock":
            case "Combat/ShieldBlock":
                action.duration = Number(props.ticks) || 40;
                break;
            case "Combat/UseItem":
                action.params.continuous = props.continuous === true;
                action.duration = Number(props.ticks) || 1;
                break;

            // Equipment
            case "Equipment/Hotbar":
                action.params.slot = Number(props.slot) || 0;
                break;
            case "Equipment/EquipArmor":
                action.params.armorSet = props.armorSet || "diamond";
                break;
            case "Equipment/EquipSlot":
                action.params.slot = props.slot || "mainhand";
                action.params.item = props.item || "diamond_sword";
                break;
            case "Equipment/Drop":
            case "Equipment/DropStack":
                action.duration = Number(props.ticks) || 1;
                break;

            // Look
            case "Look/LookDirection":
                action.params.direction = props.direction || "north";
                break;
            case "Look/LookAt":
                action.params.x = Number(props.x) || 0;
                action.params.y = Number(props.y) || 64;
                action.params.z = Number(props.z) || 0;
                break;
            case "Look/LookAtPlayer":
                action.params.player = props.player || "";
                break;
            case "Look/LookYawPitch":
                action.params.yaw = Number(props.yaw) || 0;
                action.params.pitch = Number(props.pitch) || 0;
                break;
            case "Look/Turn":
                action.params.yaw = Number(props.yaw) || 0;
                action.params.pitch = Number(props.pitch) || 0;
                break;

            // Navigation
            case "Navigation/NavGoto":
                action.params.x = Number(props.x) || 0;
                action.params.y = Number(props.y) || 64;
                action.params.z = Number(props.z) || 0;
                break;
            case "Navigation/NavMode":
                action.params.mode = props.mode || "auto";
                break;
            case "Navigation/FollowPlayer":
                action.params.player = props.player || "";
                action.params.distance = Number(props.distance) || 3;
                action.duration = Number(props.ticks) || 200;
                break;
            case "Navigation/FleeFrom":
                action.params.player = props.player || "";
                action.params.distance = Number(props.distance) || 16;
                action.duration = Number(props.ticks) || 100;
                break;
            case "Navigation/Wander":
                action.params.radius = Number(props.radius) || 16;
                action.duration = Number(props.ticks) || 200;
                break;

            // Elytra
            case "Elytra/GlideGoto":
                action.params.x = Number(props.x) || 0;
                action.params.y = Number(props.y) || 100;
                action.params.z = Number(props.z) || 0;
                break;
            case "Elytra/GlideHeading":
                action.params.yaw = Number(props.yaw) || 0;
                action.params.pitch = Number(props.pitch) || -5;
                break;
            case "Elytra/GlideSpeed":
                action.params.speed = Number(props.speed) || 1.0;
                break;

            // Crystal
            case "Crystal/PlaceCrystal":
            case "Crystal/DetonateCrystal":
            case "Crystal/PlaceBlock":
                action.duration = Number(props.ticks) || 1;
                break;

            // Conditions
            case "Conditions/Health":
                action.params.operator = props.operator || "<";
                action.params.value = Number(props.value) || 10;
                break;
            case "Conditions/Distance":
                action.params.target = props.target || "";
                action.params.operator = props.operator || "<";
                action.params.value = Number(props.value) || 5;
                break;
            case "Conditions/Food":
                action.params.operator = props.operator || "<";
                action.params.value = Number(props.value) || 10;
                break;
            case "Conditions/Random":
                action.params.chance = Number(props.chance) || 50;
                break;
            case "Conditions/HasItem":
                action.params.item = props.item || "end_crystal";
                break;
            case "Conditions/Armor":
                action.params.operator = props.operator || "<";
                action.params.value = Number(props.value) || 10;
                break;
        }

        return action;
    }

    // ── Crystal Combo macro expansion ────────────────────────
    function expandCrystalCombo(props) {
        return {
            type: "SEQUENCE",
            children: [
                { type: "LOOK_YAW_PITCH", params: { yaw: 0, pitch: Number(props.lookPitch) || 90 } },
                { type: "HOTBAR", params: { slot: Number(props.obsidianSlot) || 0 } },
                { type: "PLACE_BLOCK", duration: 1 },
                { type: "DELAY", duration: 2 },
                { type: "HOTBAR", params: { slot: Number(props.crystalSlot) || 1 } },
                { type: "PLACE_CRYSTAL", duration: 1 },
                { type: "DELAY", duration: 1 },
                { type: "HOTBAR", params: { slot: Number(props.swordSlot) || 2 } },
                { type: "DETONATE_CRYSTAL", duration: 1 },
            ]
        };
    }

    // ── Auto Crystal macro expansion ─────────────────────────
    function expandAutoCrystal(props) {
        return {
            type: "LOOP",
            params: { count: Math.floor((Number(props.ticks) || 100) / (Number(props.speed) || 2)) },
            children: [
                { type: "HOTBAR", params: { slot: Number(props.crystalSlot) || 1 } },
                { type: "PLACE_CRYSTAL", duration: 1 },
                { type: "DELAY", duration: Math.max(1, Number(props.speed) - 1) },
                { type: "DETONATE_CRYSTAL", duration: 1 },
            ]
        };
    }

    // ── Graph helpers ────────────────────────────────────────
    function getConnectedNode(graph, node, outputIndex) {
        if (!node.outputs || !node.outputs[outputIndex]) return null;
        const links = node.outputs[outputIndex].links;
        if (!links || links.length === 0) return null;
        const link = graph.links[links[0]];
        if (!link) return null;
        return graph.getNodeById(link.target_id);
    }

    function getConnectedInput(graph, node, inputIndex) {
        if (!node.inputs || !node.inputs[inputIndex]) return null;
        const linkId = node.inputs[inputIndex].link;
        if (linkId == null) return null;
        const link = graph.links[linkId];
        if (!link) return null;
        return graph.getNodeById(link.origin_id);
    }

    // ── Compile to JSON string ───────────────────────────────
    function compileToJSON(graph) {
        const actions = compile(graph);
        return JSON.stringify(actions, null, 2);
    }

    return { compile, compileToJSON, findStartNode, TYPE_MAP };
})();
