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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
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
            if (INSIDE_A_LOOP.contains(definition.type()))
            {
                JsonObject loop = new JsonObject();
                loop.addProperty("type", "LOOP");
                loop.add("children", GSON.toJsonTree(List.of(action)));
                action = loop;
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
            BotAction action = definition.kind().equals(ActionSchema.CONDITION) ? step.getCondition()
                    : INSIDE_A_LOOP.contains(definition.type()) ? step.getChildren().getFirst() : step;
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
            case EXPR -> param.returns() == Expression.Type.BOOL ? "true" : param.returns() == Expression.Type.LIST ? "list(1, 2)" : "7";
        };
    }

    /** The steps that are only allowed inside a loop, and are put into one to be tried. */
    private static final Set<String> INSIDE_A_LOOP = Set.of("BREAK", "CONTINUE");

    @ParameterizedTest
    @ValueSource(strings = {
            "[{type: BREAK}]",
            "[{type: CONTINUE}]",
            "[{type: IF, params: {condition: 'true'}, children: [{type: BREAK}]}]",
            "[{type: LOOP, children: [{type: ON_EVENT, children: [{type: BREAK}]}]}]",
            "[{type: LOOP, children: [{type: JUMP}]}, {type: CONTINUE}]"})
    void breakAndContinueAreRefusedOutsideALoop(String json)
    {
        List<BotAction> actions = GSON.fromJson(json, new TypeToken<List<BotAction>>() {}.getType());
        String message = assertThrows(IllegalArgumentException.class, () -> schema.validate(actions)).getMessage();
        assertTrue(message.endsWith(" is not inside a loop"), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{type: LOOP, children: [{type: BREAK}]}]",
            "[{type: FOREVER, children: [{type: IF, params: {condition: 'true'}, children: [{type: BREAK}], elseChildren: [{type: CONTINUE}]}]}]",
            "[{type: WHILE, params: {condition: 'true'}, children: [{type: SEQUENCE, children: [{type: CONTINUE}]}]}]",
            "[{type: FOR_EACH, children: [{type: BREAK}]}]",
            "[{type: ON_EVENT, children: [{type: LOOP, children: [{type: BREAK}]}]}]"})
    void breakAndContinueAreTakenAnywhereInsideALoop(String json)
    {
        schema.validate(GSON.fromJson(json, new TypeToken<List<BotAction>>() {}.getType()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{type: IF_THEN_ELSE, condition: {type: CONDITION_ALL, conditions: [{type: JUMP}]}}]",
            "[{type: IF_THEN_ELSE, condition: {type: CONDITION_NOT}}]",
            "[{type: IF_THEN_ELSE, condition: {type: CONDITION_IS_FLYING, conditions: [{type: CONDITION_IS_FLYING}]}}]"})
    void aConditionMadeOfConditionsIsCheckedAllTheWayDown(String json)
    {
        List<BotAction> actions = GSON.fromJson(json, new TypeToken<List<BotAction>>() {}.getType());
        assertThrows(IllegalArgumentException.class, () -> schema.validate(actions));
    }

    // ── Expressions in parameters ──

    @Test
    void aNumberParameterTakesAnExpression()
    {
        BotAction move = new BotAction("MOVE", Map.of("ticks", "$steps * 2 + 1"));
        schema.validate(List.of(move));
        assertEquals(21, schema.params(move, Map.of("steps", 10.0)).integer("ticks"));
        assertEquals(6000, schema.params(move, Map.of("steps", 1.0e9)).integer("ticks"), "what it gives is clamped like a number");

        BotAction look = new BotAction("LOOK_AT", Map.of("x", "max($a, $b) / 4"));
        schema.validate(List.of(look));
        assertEquals(2.5, schema.params(look, Map.of("a", 10.0, "b", 3.0)).number("x"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "helth / 2 | MOVE.ticks: Unknown name 'helth' at 0",
            "health / | MOVE.ticks: Unexpected end of the expression",
            "health < 5 | MOVE.ticks: Expected a number but got true or false at 0",
            "held_item | MOVE.ticks: Expected a number but got text at 0",
            "40 | MOVE.ticks must be a number or an expression"})
    void anExpressionThatCannotGiveANumberIsRefusedWithItsReason(String source, String message)
    {
        BotAction move = new BotAction("MOVE", Map.of("ticks", source));
        assertEquals(message, assertThrows(IllegalArgumentException.class, () -> schema.validate(List.of(move))).getMessage());
    }

    @Test
    void anExpressionParameterIsCheckedForWhatItHasToGive()
    {
        BotAction branch = new BotAction("IF", Map.of("condition", "$count >= 3 and not ($done == true)"));
        schema.validate(List.of(branch));
        assertTrue(schema.params(branch, Map.of("count", 3.0, "done", false)).truth("condition"));
        assertFalse(schema.params(branch, Map.of("count", 3.0, "done", true)).truth("condition"));

        assertEquals("IF.condition: Expected true or false but got a number at 0", assertThrows(IllegalArgumentException.class,
                () -> schema.validate(List.of(new BotAction("IF", Map.of("condition", "health + 1"))))).getMessage());
        assertEquals("IF.condition must be an expression", assertThrows(IllegalArgumentException.class,
                () -> schema.validate(List.of(new BotAction("IF", Map.of("condition", true))))).getMessage());

        // A value that may be anything: a number, text or a list.
        BotAction set = new BotAction("SET", Map.of("name", "who", "value", "'Steve' + ' ' + $n"));
        schema.validate(List.of(set));
        assertEquals("Steve 2", schema.params(set, Map.of("n", 2.0)).value("value"));
    }

    @Test
    void anExpressionThatFailsWhenItIsEvaluatedSaysWhichParameter()
    {
        BotAction move = new BotAction("MOVE", Map.of("ticks", "10 / $n"));
        schema.validate(List.of(move));
        assertEquals("MOVE.ticks: Division by zero at 3",
                assertThrows(BotActionException.class, () -> schema.params(move, Map.of()).integer("ticks")).getMessage());

        // A variable can hold anything, so what it holds is only known when it is read.
        BotAction wait = new BotAction("DELAY", Map.of("ticks", "$name"));
        schema.validate(List.of(wait));
        assertEquals("DELAY.ticks: Expected a number but got text",
                assertThrows(BotActionException.class, () -> schema.params(wait, Map.of("name", "Steve")).integer("ticks")).getMessage());

        BotAction health = new BotAction("DELAY", Map.of("ticks", "health"));
        schema.validate(List.of(health));
        assertEquals("DELAY.ticks: 'health' can only be read while a program runs",
                assertThrows(BotActionException.class, () -> schema.params(health).integer("ticks")).getMessage());
    }

    @Test
    void everyNameAndFunctionAnExpressionKnowsIsAnsweredByARunningProgram()
    {
        // A program whose one step sets a variable from the name or the call, run on a bot that answers anything.
        Expression.Vocabulary vocabulary = schema.vocabulary();
        Map<String, String> calls = Map.of("distance", "distance(1, 2, 3)", "player_distance", "player_distance('Steve')",
                "count", "count('arrow')", "block", "block(1, 2, 3)", "entities", "entities('zombie', 8)");
        List<String> sources = new java.util.ArrayList<>(vocabulary.values().keySet());
        sources.addAll(calls.values());
        assertEquals(31 + 5, sources.size());
        for (String source : sources)
        {
            RecordingBot recorder = new RecordingBot();
            ProgramExecutor executor = new ProgramExecutor(schema, name -> recorder.bot, () -> 1);
            BotProgram program = new BotProgram("test", source, "");
            program.setActions(List.of(new BotAction("SET", Map.of("name", "read", "value", source))));
            schema.validate(program.getActions());
            executor.startProgram("bot", program, null);
            executor.tick();

            ProgramExecutor.ProgramInfo info = executor.getPrograms().get("bot");
            assertEquals("COMPLETED", info.status(), source + ": " + info.error());
        }
    }

    private static List<BotAction> scarpet(String code)
    {
        return GSON.fromJson(GSON.toJson(List.of(Map.of("type", "SCARPET", "params", Map.of("code", code)))),
                new TypeToken<List<BotAction>>() {}.getType());
    }

    @Test
    void aSnippetLongerThanItsParameterHoldsIsRefused()
    {
        String fits = "x".repeat(4096);
        schema.validate(scarpet(fits));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> schema.validate(scarpet(fits + "x")));
        assertEquals("SCARPET.code must be text of at most 4096 characters", refused.getMessage());
    }

    @Test
    void theActionsMarkedUnbudgetedAreTheOnesThatHoldScarpet() throws IOException
    {
        JsonObject actions;
        try (Reader reader = new InputStreamReader(ActionSchema.class.getResourceAsStream("/carpetlogic/actions.json"), StandardCharsets.UTF_8))
        {
            actions = GSON.fromJson(reader, JsonObject.class).getAsJsonObject("actions");
        }
        Set<String> marked = new HashSet<>();
        for (String type : actions.keySet())
        {
            JsonObject action = actions.getAsJsonObject(type);
            boolean holdsScarpet = false;
            for (JsonElement param : action.getAsJsonArray("params"))
            {
                holdsScarpet |= param.getAsJsonObject().has("code");
            }
            assertEquals(holdsScarpet, action.has("unbudgeted") && action.get("unbudgeted").getAsBoolean(), type);
            if (holdsScarpet)
            {
                marked.add(type);
            }
        }
        assertEquals(Set.of("SCARPET", "CONDITION_SCARPET"), marked);
    }
}
