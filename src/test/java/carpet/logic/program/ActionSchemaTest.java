package carpet.logic.program;

import carpet.logic.program.ActionSchema.Definition;
import carpet.logic.program.ActionSchema.Param;
import carpet.logic.program.ActionSchema.Params;
import carpet.logic.program.ActionSchema.Variables;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.BotPvpConfig.CombatStyle;
import carpet.pvp.BotPvpConfig.Difficulty;
import carpet.utils.ArmorSetDefinition;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionSchemaTest
{
    private static final Gson GSON = new Gson();
    private final ActionSchema schema = ActionSchema.load();

    // A parameter map that remembers which names were asked for.
    private static final class RecordingMap extends HashMap<String, Object>
    {
        final Set<String> read = new HashSet<>();

        RecordingMap(Map<String, Object> values)
        {
            super(values);
        }

        @Override
        public Object get(Object key)
        {
            read.add((String) key);
            return super.get(key);
        }
    }

    /**
     * Every action type, with every parameter set to a valid value that is not its default, survives JSON
     * and validation, and the interpreter then reads exactly the parameters the schema declares for it.
     */
    @Test
    void everyActionTypeRoundTripsAndEveryParameterIsRead()
    {
        JsonArray tree = new JsonArray();
        for (Definition definition : schema.definitions())
        {
            JsonObject action = new JsonObject();
            action.addProperty("type", definition.type());
            JsonObject params = new JsonObject();
            for (Param param : definition.params().values())
            {
                params.add(param.name(), GSON.toJsonTree(nonDefault(param)));
            }
            action.add("params", params);
            if (definition.slots().contains("children"))
            {
                action.add("children", GSON.toJsonTree(List.of(Map.of("type", "JUMP"))));
            }
            if (definition.slots().contains("condition"))
            {
                action.add("condition", GSON.toJsonTree(Map.of("type", "CONDITION_IS_FLYING")));
            }
            if (definition.kind().equals(ActionSchema.CONDITION))
            {
                JsonObject branch = new JsonObject();
                branch.addProperty("type", "IF_THEN_ELSE");
                branch.add("condition", action);
                action = branch;
            }
            tree.add(action);
        }

        List<BotAction> actions = GSON.fromJson(GSON.toJson(tree), new TypeToken<List<BotAction>>() {}.getType());
        assertEquals(schema.definitions().size(), actions.size());
        schema.validate(actions);

        int index = 0;
        for (Definition definition : schema.definitions())
        {
            BotAction step = actions.get(index++);
            BotAction action = definition.kind().equals(ActionSchema.CONDITION) ? step.getCondition() : step;
            assertEquals(definition.type(), action.getType());
            for (Param param : definition.params().values())
            {
                assertEquals(nonDefault(param), action.getParams().get(param.name()), definition.type() + "." + param.name());
            }
            RecordingMap recorded = new RecordingMap(action.getParams());
            action.setParams(recorded);

            RecordingBot recorder = new RecordingBot();
            ProgramExecutor executor = new ProgramExecutor(schema, name -> recorder.bot, () -> 1);
            BotProgram program = new BotProgram("test", definition.type(), "");
            program.setActions(List.of(step));
            executor.startProgram("bot", program, null);
            executor.tick();

            ProgramExecutor.ProgramInfo info = executor.getPrograms().get("bot");
            assertFalse(info.status().equals("ERROR"), definition.type() + " failed: " + info.error());
            assertEquals(definition.params().keySet(), recorded.read, "parameters the interpreter read for " + definition.type());
        }
    }

    @Test
    void parametersFallBackToTheSchemaDefaultAndAreClamped()
    {
        Params defaults = schema.params(new BotAction("MOVE", Map.of()));
        assertEquals("forward", defaults.string("direction"));
        assertEquals(20, defaults.integer("ticks"));

        Params odd = schema.params(new BotAction("MOVE", Map.of("direction", "sideways", "ticks", 1.0e9)));
        assertEquals("forward", odd.string("direction"));
        assertEquals(6000, odd.integer("ticks"));

        assertEquals(1, schema.params(new BotAction("MOVE", Map.of("ticks", -5.0))).integer("ticks"));
        assertEquals(20, schema.params(new BotAction("MOVE", Map.of("ticks", "soon"))).integer("ticks"));
        assertTrue(schema.params(new BotAction("SPRINT", Map.of("enabled", "no"))).bool("enabled"));
        assertFalse(schema.params(new BotAction("SPRINT", Map.of("enabled", false))).bool("enabled"));
    }

    @Test
    void readingAParameterTheSchemaDoesNotDeclareIsAnError()
    {
        Params params = schema.params(new BotAction("MOVE", Map.of("duration", 40.0)));
        assertThrows(IllegalStateException.class, () -> params.integer("duration"));
        assertThrows(IllegalStateException.class, () -> params.number("ticks"));
        assertThrows(IllegalStateException.class, () -> params.string("ticks"));
    }

    @Test
    void aNumberParameterTakesAVariableReference()
    {
        Variables variables = schema.variables();
        assertEquals("$", variables.prefix());
        assertTrue(variables.isName("counter"));
        assertFalse(variables.isName("two words"));
        assertTrue(variables.limit() > 0);

        BotAction move = new BotAction("MOVE", Map.of("ticks", "$steps"));
        schema.validate(List.of(move));
        assertEquals(1, schema.params(move).integer("ticks"), "an unset variable reads as 0, clamped to the parameter's minimum");
        assertEquals(42, schema.params(move, Map.of("steps", 42.0)).integer("ticks"));
        assertEquals(7.5, schema.params(new BotAction("LOOK_AT", Map.of("x", "$x")), Map.of("x", 7.5)).number("x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"type\": \"TELEPORT\"}]",
            "[{}]",
            "[null]",
            "[{\"type\": \"MOVE\", \"params\": {\"duration\": 40}}]",
            "[{\"type\": \"MOVE\", \"params\": {\"ticks\": \"40\"}}]",
            "[{\"type\": \"MOVE\", \"params\": {\"ticks\": \"$two words\"}}]",
            "[{\"type\": \"MOVE\", \"params\": {\"ticks\": \"$\"}}]",
            "[{\"type\": \"MOVE\", \"params\": {\"ticks\": null}}]",
            "[{\"type\": \"MOVE\", \"params\": {\"direction\": \"up\"}}]",
            "[{\"type\": \"SPRINT\", \"params\": {\"enabled\": \"false\"}}]",
            "[{\"type\": \"SPRINT\", \"params\": {\"mode\": \"stop\"}}]",
            "[{\"type\": \"EQUIP_ARMOR\", \"params\": {\"set\": \"diamond\"}}]",
            "[{\"type\": \"MOVE\", \"children\": [{\"type\": \"JUMP\"}]}]",
            "[{\"type\": \"FOREVER\", \"elseChildren\": [{\"type\": \"JUMP\"}]}]",
            "[{\"type\": \"FOREVER\", \"condition\": {\"type\": \"CONDITION_IS_FLYING\"}}]",
            "[{\"type\": \"CONDITION_IS_FLYING\"}]",
            "[{\"type\": \"IF_THEN_ELSE\", \"children\": [{\"type\": \"JUMP\"}]}]",
            "[{\"type\": \"IF_THEN_ELSE\", \"condition\": {\"type\": \"JUMP\"}}]",
            "[{\"type\": \"FOREVER\", \"children\": [{\"type\": \"NOPE\"}]}]"})
    void programsThatDoNotFitTheSchemaAreRejected(String json)
    {
        List<BotAction> actions = GSON.fromJson(json, new TypeToken<List<BotAction>>() {}.getType());
        assertThrows(IllegalArgumentException.class, () -> schema.validate(actions));
    }

    @Test
    void programsNestedTooDeeplyAreRejected()
    {
        BotAction action = new BotAction("JUMP", Map.of());
        for (int i = 0; i < 100; i++)
        {
            BotAction outer = new BotAction("SEQUENCE", Map.of());
            outer.setChildren(List.of(action));
            action = outer;
        }
        List<BotAction> actions = List.of(action);
        assertThrows(IllegalArgumentException.class, () -> schema.validate(actions));
    }

    @Test
    void requiredRulesAreBooleanCarpetRules() throws ReflectiveOperationException
    {
        // Looked up without initialising CarpetSettings, which needs a running game.
        Class<?> settings = Class.forName("carpet.CarpetSettings", false, getClass().getClassLoader());
        int actionsWithARule = 0;
        for (Definition definition : schema.definitions())
        {
            if (definition.requires() != null)
            {
                actionsWithARule++;
                Field rule = settings.getField(definition.requires());
                assertEquals(boolean.class, rule.getType(), definition.type() + " requires " + definition.requires());
                assertTrue(Modifier.isStatic(rule.getModifiers()));
            }
        }
        assertTrue(actionsWithARule > 0);
    }

    @Test
    void theArmourSetNodeOffersEveryNameTheEquipCommandDoes()
    {
        Param param = schema.definitions().stream()
                .filter(definition -> definition.type().equals("EQUIP_ARMOR"))
                .findFirst().orElseThrow().params().get("armorSet");
        for (String name : ArmorSetDefinition.ARMOR_SETS.keySet())
        {
            assertTrue(param.options().contains(name), "EquipArmor is missing " + name);
        }
    }

    @Test
    void theCombatStyleNodeOffersEveryStyleTheBotHasAndNothingElse()
    {
        Param param = schema.definitions().stream()
                .filter(definition -> definition.type().equals("COMBAT_START"))
                .findFirst().orElseThrow().params().get("style");
        assertEquals(List.of(BotPvpConfig.styles()), param.options(), "the names /bot spawn takes, in the enum's order");
        assertEquals("sword", param.defaultValue(), "the default has to be one of them");
        for (String name : param.options())
        {
            assertEquals(name.toUpperCase(Locale.ROOT).equals("SWORD") ? CombatStyle.MELEE : CombatStyle.valueOf(name.toUpperCase(Locale.ROOT)),
                    BotPvpConfig.styleOf(name), "styleOf takes every name the node offers");
        }
        assertThrows(IllegalArgumentException.class, () -> BotPvpConfig.styleOf("chainsaw"));
    }

    @Test
    void theFightNodeOffersEveryDifficultyTheBotHas()
    {
        Param param = schema.definitions().stream()
                .filter(definition -> definition.type().equals("FIGHT"))
                .findFirst().orElseThrow().params().get("difficulty");
        assertEquals(List.of(BotPvpConfig.difficulties()), param.options());
        for (Difficulty difficulty : Difficulty.values())
        {
            assertTrue(param.options().contains(difficulty.name().toLowerCase(Locale.ROOT)),
                    "FIGHT offers " + difficulty);
        }
    }

    @Test
    void theEditorIsSentTheOptionsTheSchemaChecksAgainst()
    {
        // The web editor is handed this very file, so a list that is generated here has to be in it too.
        JsonObject json = schema.json();
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("actions").entrySet())
        {
            for (JsonElement element : entry.getValue().getAsJsonObject().getAsJsonArray("params"))
            {
                JsonObject param = element.getAsJsonObject();
                if (param.has("optionsFrom"))
                {
                    assertTrue(param.has("options"), entry.getKey() + "." + param.get("name") + " has no resolved options");
                    assertFalse(param.getAsJsonArray("options").isEmpty(), entry.getKey() + "." + param.get("name"));
                }
            }
        }
    }

    @Test
    void onlyTheStepsThatDriveTheBodySaySo()
    {
        // A combat node keeps the program from driving the action pack, so every step that would is marked.
        Set<String> moving = Set.of("MOVE", "STRAFE", "SPRINT", "SNEAK", "JUMP", "MOUNT", "DISMOUNT", "STOP_MOVEMENT",
                "ATTACK", "ATTACK_CRIT", "SWORD_BLOCK", "SHIELD_BLOCK", "USE", "PLACE_BLOCK", "PLACE_CRYSTAL",
                "DETONATE_CRYSTAL", "NAV_GOTO", "NAV_STOP", "FOLLOW_PLAYER", "CHASE_PLAYER", "PATROL", "FLEE_FROM",
                "WANDER", "GLIDE_START", "GLIDE_STOP", "GLIDE_GOTO", "GLIDE_HEADING", "GLIDE_SPEED", "GLIDE_FREEZE",
                "GLIDE_LAND");
        for (Definition definition : schema.definitions())
        {
            assertEquals(moving.contains(definition.type()), definition.drivesBody(),
                    definition.type() + (moving.contains(definition.type()) ? " drives the body" : " does not"));
        }
    }

    @Test
    void aStyleTheSchemaDoesNotKnowIsRejectedBeforeItRuns()
    {
        List<BotAction> actions = GSON.fromJson("[{\"type\": \"COMBAT_START\", \"params\": {\"style\": \"chainsaw\"}}]",
                new TypeToken<List<BotAction>>() {}.getType());
        assertThrows(IllegalArgumentException.class, () -> schema.validate(actions));
    }

    @Test
    void anOptionKeyIsFreeTextSoThatTheServerCanRefuseIt()
    {
        Param param = schema.definitions().stream()
                .filter(definition -> definition.type().equals("SET_COMBAT_OPTION"))
                .findFirst().orElseThrow().params().get("key");
        assertTrue(param.options().isEmpty(), "the option names live on the bot, not here");
        List<BotAction> actions = GSON.fromJson(
                "[{\"type\": \"SET_COMBAT_OPTION\", \"params\": {\"key\": \"chainsaw\", \"value\": \"1\"}}]",
                new TypeToken<List<BotAction>>() {}.getType());
        schema.validate(actions);
    }

    @Test
    void builtInPresetsFitTheSchema(@TempDir Path dir)
    {
        List<BotProgram> presets = new ProgramStorage(dir, schema).getPresets();
        assertFalse(presets.isEmpty());
        for (BotProgram preset : presets)
        {
            assertTrue(preset.isPreset());
            assertTrue(ProgramStorage.isValidId(preset.getId()));
            assertFalse(preset.getActions().isEmpty(), preset.getId());
            schema.validate(preset.getActions());
        }
    }

    private static Object nonDefault(Param param)
    {
        return switch (param.type())
        {
            case BOOL -> !(Boolean) param.defaultValue();
            case STRING -> param.options().isEmpty() ? "sample"
                    : param.options().stream().filter(option -> !option.equals(param.defaultValue())).findFirst().orElseThrow();
            case INT, NUMBER ->
            {
                double value = (Double) param.defaultValue();
                yield value + 1 <= param.max() ? value + 1 : value - 1;
            }
        };
    }

}
