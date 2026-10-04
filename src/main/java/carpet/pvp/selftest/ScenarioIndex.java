package carpet.pvp.selftest;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/**
 * Scenarios that live outside {@link SelfTest}, one file per feature. Each is registered on a line of its own at
 * the end of the block below; .gitattributes merges this file by union, so branches that each add scenarios do
 * not conflict.
 */
final class ScenarioIndex
{
    /** a is the bot under test, b and c further players; origin is a clear spot of the flat world, 256 blocks from the next. */
    interface Factory
    {
        SelfTest.Scenario create(String a, String b, String c, Vec3 origin);
    }

    static final Map<String, Factory> SCENARIOS = new LinkedHashMap<>();

    private ScenarioIndex() {}

    static
    {
        SCENARIOS.put("bot_death_respawn", LifecycleScenarios::deathRespawn);
        SCENARIOS.put("bot_spawn_kit", BotScenarios::spawnKit);
        SCENARIOS.put("match_ffa", MatchScenarios::ffa);
        SCENARIOS.put("match_teams", MatchScenarios::teams);
        SCENARIOS.put("faction_persistence", MatchScenarios::factionPersistence);
        SCENARIOS.put("spectate_roundtrip", MatchScenarios::spectateRoundtrip);
        SCENARIOS.put("trace_records_fight", MatchScenarios::traceRecordsFight);
        SCENARIOS.put("drill_aim_scores", DrillScenarios::aimScores);
        SCENARIOS.put("drill_skips_without_needs", DrillScenarios::skipsWithoutWhatItNeeds);
        SCENARIOS.put("drill_stunslam_shield", DrillScenarios::stunslamShield);
        SCENARIOS.put("gui_toggle_option", GuiScenarios::toggleOption);
        SCENARIOS.put("gui_cycle_style", GuiScenarios::cycleStyle);
        SCENARIOS.put("gui_spawn", GuiScenarios::spawn);
        SCENARIOS.put("gui_no_item_theft", GuiScenarios::noItemTheft);
        SCENARIOS.put("gui_kit_editor_roundtrip", GuiScenarios::kitEditorRoundtrip);
        SCENARIOS.put("logic_combat_start_stop", CombatNodeScenarios::combatStartStop);
        SCENARIOS.put("logic_fight_node", CombatNodeScenarios::fightNode);
        SCENARIOS.put("logic_combat_option", CombatNodeScenarios::combatOption);
        SCENARIOS.put("logic_on_kill_event", CombatNodeScenarios::onKillEvent);
        SCENARIOS.put("logic_totem_pop_event", CombatNodeScenarios::totemPopEvent);
        SCENARIOS.put("logic_stop_program_stops_fight", CombatNodeScenarios::stopProgramStopsFight);
        SCENARIOS.put("autosetup_roundtrip", AutoSetupScenarios::roundtrip);
        SCENARIOS.put("autosetup_each_mode", AutoSetupScenarios::eachMode);
        SCENARIOS.put("autosetup_crash_safe", AutoSetupScenarios::crashSafe);
        SCENARIOS.put("autosetup_rules_restored", AutoSetupScenarios::rulesRestored);
        SCENARIOS.put("ranged_kit", RangedScenarios::rangedKit);
        SCENARIOS.put("bow_hits_static", RangedScenarios::bowHitsStatic);
        SCENARIOS.put("bow_hits_moving", RangedScenarios::bowHitsMoving);
        SCENARIOS.put("crossbow_cycle", RangedScenarios::crossbowCycle);
        SCENARIOS.put("trident_throw", RangedScenarios::tridentThrow);
        SCENARIOS.put("spear_reach", RangedScenarios::spearReach);
        SCENARIOS.put("tnt_cart_safe", RangedScenarios::tntCartSafe);
        SCENARIOS.put("ranged_keeps_distance", RangedScenarios::rangedKeepsDistance);
        SCENARIOS.put("ranged_duel", RangedScenarios::rangedDuel);
        SCENARIOS.put("sword_damage_rate", SwordScenarios::damageRate);
        SCENARIOS.put("sword_ladder", SwordScenarios::ladder);
        SCENARIOS.put("sword_catches_runner", SwordScenarios::catchesRunner);
        SCENARIOS.put("sword_shield_play", SwordScenarios::shieldPlay);
        SCENARIOS.put("sword_only", SwordScenarios::swordOnly);
        SCENARIOS.put("sword_settings", SwordScenarios::swordSettings);
        SCENARIOS.put("crystal_damage_matches_model", CrystalScenarios::damageMatchesModel);
        SCENARIOS.put("crystal_place_and_hit", CrystalScenarios::placeAndHit);
        SCENARIOS.put("crystal_never_suicides", CrystalScenarios::neverSuicides);
        SCENARIOS.put("crystal_retotem", CrystalScenarios::reTotem);
        SCENARIOS.put("crystal_anchor", CrystalScenarios::anchor);
        SCENARIOS.put("crystal_duel", CrystalScenarios::duel);
        SCENARIOS.put("mace_launch_height", MaceScenarios::launchHeight);
        SCENARIOS.put("mace_smash_damage", MaceScenarios::smashDamage);
        SCENARIOS.put("mace_stun_slam", MaceScenarios::stunSlam);
        SCENARIOS.put("mace_no_fall_damage_on_miss", MaceScenarios::noFallDamageOnMiss);
        SCENARIOS.put("fake_player_fall_distance", FallDistanceScenarios::fallDistance);
        SCENARIOS.put("mace_swap_probe", MaceSwapScenarios::swapProbe);
        SCENARIOS.put("mace_breach_swap_probe", MaceSwapScenarios::breachSwapProbe);
        SCENARIOS.put("mace_duel", MaceScenarios::duel);
        SCENARIOS.put("sword_hits_passive_target", PassiveTargetScenarios::hitsPassiveTarget);
        SCENARIOS.put("smp_heals", SmpScenarios::heals);
        SCENARIOS.put("smp_retotem", SmpScenarios::retotem);
        SCENARIOS.put("smp_buffs", SmpScenarios::buffs);
        SCENARIOS.put("bot_stop_stats_trace", BotCommandScenarios::fightingCommands);
        SCENARIOS.put("nav_straight_line", NavMotionScenarios::straightLine);
        SCENARIOS.put("bot_patrol_waypoints", BotCommandParityScenarios::patrolWaypoints);
        SCENARIOS.put("bot_patrol_modes", BotCommandParityScenarios::patrolModes);
        SCENARIOS.put("bot_turn_rotation", BotCommandParityScenarios::turnRotation);
        SCENARIOS.put("bot_glide_commands", BotCommandParityScenarios::glide);
        SCENARIOS.put("bot_permission", BotCommandParityScenarios::permission);
        SCENARIOS.put("bot_skin_profile", SkinScenarios::profile);
    }
}
