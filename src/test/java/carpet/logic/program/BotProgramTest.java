package carpet.logic.program;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BotProgramTest
{
    private static final String PROGRAM = """
            {"id": "walk", "name": "Walk", "actions": [{"type": "JUMP"}],
             "graphData": {"last_node_id": 2, "nodes": [{"id": 1, "type": "Control/Start", "pos": [200, 250]}], "links": []}}""";

    @Test
    void graphDataSurvivesJson()
    {
        Gson gson = new Gson();
        BotProgram program = gson.fromJson(PROGRAM, BotProgram.class);
        assertEquals(JsonParser.parseString(PROGRAM).getAsJsonObject().get("graphData"), program.getGraphData());
        assertEquals(program.getGraphData(), gson.fromJson(gson.toJson(program), BotProgram.class).getGraphData());
    }

    @Test
    void graphDataSurvivesSaveAndReload(@TempDir Path dir)
    {
        BotProgram program = new Gson().fromJson(PROGRAM, BotProgram.class);
        new ProgramStorage(dir, ActionSchema.load()).save(program);

        ProgramStorage reloaded = new ProgramStorage(dir, ActionSchema.load());
        reloaded.loadAll();
        BotProgram loaded = reloaded.getById("walk");
        assertNotNull(loaded);
        assertEquals(program.getGraphData(), loaded.getGraphData());
        assertEquals(1, loaded.getActions().size());
    }
}
