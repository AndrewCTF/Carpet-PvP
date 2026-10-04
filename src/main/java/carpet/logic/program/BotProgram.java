package carpet.logic.program;

import com.google.gson.JsonElement;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A saved bot program: the compiled action tree that runs, plus the editor graph it was compiled from.
 * A program whose graph does not compile yet is a draft: it has the graph and the reason, and no actions.
 */
public class BotProgram
{
    private String id;
    private String name;
    private String description;
    private List<BotAction> actions = new ArrayList<>();
    // The editor's serialised node graph, stored verbatim so the program can be reopened.
    private JsonElement graphData;
    private long createdAt;
    private long updatedAt;
    private boolean isPreset;
    // Why the graph does not compile, for a draft; null for a program that runs.
    private String error;
    // Where the program is kept. Its file says so by where it is, so neither is written into it.
    private transient String folder;
    private transient Path file;

    public BotProgram()
    {
    }

    public BotProgram(String id, String name, String description)
    {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getDescription()
    {
        return description;
    }

    public List<BotAction> getActions()
    {
        return actions;
    }

    public void setActions(List<BotAction> actions)
    {
        this.actions = actions;
    }

    public JsonElement getGraphData()
    {
        return graphData;
    }

    public void setGraphData(JsonElement graphData)
    {
        this.graphData = graphData;
    }

    public long getCreatedAt()
    {
        return createdAt;
    }

    public void setCreatedAt(long createdAt)
    {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt()
    {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt)
    {
        this.updatedAt = updatedAt;
    }

    public boolean isPreset()
    {
        return isPreset;
    }

    public void setPreset(boolean preset)
    {
        this.isPreset = preset;
    }

    public int getActionCount()
    {
        return actions != null ? actions.size() : 0;
    }

    public String getError()
    {
        return error;
    }

    public void setError(String error)
    {
        this.error = error == null || error.isBlank() ? null : error;
    }

    /** The subfolder of the programs folder the program is in, or empty for the folder itself. */
    public String getFolder()
    {
        return folder == null ? "" : folder;
    }

    public void setFolder(String folder)
    {
        this.folder = folder;
    }

    public Path getFile()
    {
        return file;
    }

    public void setFile(Path file)
    {
        this.file = file;
    }
}
