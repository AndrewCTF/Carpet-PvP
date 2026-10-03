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
        SCENARIOS.put("gui_toggle_option", GuiScenarios::toggleOption);
        SCENARIOS.put("gui_cycle_style", GuiScenarios::cycleStyle);
        SCENARIOS.put("gui_spawn", GuiScenarios::spawn);
        SCENARIOS.put("gui_no_item_theft", GuiScenarios::noItemTheft);
        SCENARIOS.put("gui_kit_editor_roundtrip", GuiScenarios::kitEditorRoundtrip);
    }
}
