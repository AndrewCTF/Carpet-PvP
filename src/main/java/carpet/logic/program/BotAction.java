package carpet.logic.program;

import java.util.List;
import java.util.Map;

/**
 * One node of a compiled bot program, exactly as it appears in JSON. Actions form a tree: control actions
 * carry child lists and IF_THEN_ELSE a condition. Which types exist and which parameters they take is
 * defined by {@link ActionSchema}, and parameters are read through {@link ActionSchema.Params} only.
 */
public class BotAction
{
    private String type;
    private Map<String, Object> params;
    private List<BotAction> children;
    private List<BotAction> elseChildren;
    private BotAction condition;

    public BotAction()
    {
    }

    public BotAction(String type, Map<String, Object> params)
    {
        this.type = type;
        this.params = params;
    }

    public String getType()
    {
        return type;
    }

    public Map<String, Object> getParams()
    {
        return params == null ? Map.of() : params;
    }

    public void setParams(Map<String, Object> params)
    {
        this.params = params;
    }

    public List<BotAction> getChildren()
    {
        return children == null ? List.of() : children;
    }

    public void setChildren(List<BotAction> children)
    {
        this.children = children;
    }

    public List<BotAction> getElseChildren()
    {
        return elseChildren == null ? List.of() : elseChildren;
    }

    public void setElseChildren(List<BotAction> elseChildren)
    {
        this.elseChildren = elseChildren;
    }

    public BotAction getCondition()
    {
        return condition;
    }

    public void setCondition(BotAction condition)
    {
        this.condition = condition;
    }

    @Override
    public String toString()
    {
        return type + getParams();
    }
}
