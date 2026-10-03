package carpet.logic.program;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One node of a compiled bot program. Actions form a tree: control flow actions carry child lists.
 */
public class BotAction
{
    public enum ActionType
    {
        MOVE, SPRINT, SNEAK, JUMP, STRAFE, MOUNT, DISMOUNT, STOP_MOVEMENT,

        ATTACK, ATTACK_CRIT, SWORD_BLOCK, SHIELD_BLOCK,

        EQUIP_ARMOR, EQUIP_SLOT, UNEQUIP, HOTBAR, DROP, DROP_STACK, SWAP_HANDS,

        LOOK_DIRECTION, LOOK_AT, LOOK_YAW_PITCH, TURN,

        USE, PLACE_BLOCK, PLACE_CRYSTAL, DETONATE_CRYSTAL,

        NAV_GOTO, NAV_STOP, NAV_MODE, FOLLOW_PLAYER, FLEE_FROM, WANDER,

        GLIDE_START, GLIDE_STOP, GLIDE_GOTO, GLIDE_HEADING, GLIDE_SPEED, GLIDE_FREEZE, GLIDE_LAND,

        SEQUENCE, LOOP, FOREVER, DELAY, IF_THEN, IF_THEN_ELSE, EXECUTE_COMMAND,

        CONDITION_HEALTH, CONDITION_DISTANCE, CONDITION_RANDOM, CONDITION_FOOD, CONDITION_HAS_ITEM,
        CONDITION_IS_FLYING, CONDITION_IS_SNEAKING, CONDITION_IS_SPRINTING, CONDITION_IS_IN_WATER, CONDITION_ARMOR
    }

    private ActionType type;

    @SerializedName("params")
    private Map<String, Object> parameters = new HashMap<>();

    @SerializedName("duration")
    private int durationTicks;

    private List<BotAction> children = new ArrayList<>();

    private List<BotAction> elseChildren = new ArrayList<>();

    private BotAction condition;

    public BotAction()
    {
    }

    public BotAction(ActionType type)
    {
        this.type = type;
    }

    public BotAction withParam(String key, Object value)
    {
        parameters.put(key, value);
        return this;
    }

    public BotAction withDuration(int ticks)
    {
        this.durationTicks = ticks;
        return this;
    }

    public ActionType getType()
    {
        return type;
    }

    public Map<String, Object> getParameters()
    {
        return parameters;
    }

    public String getStringParam(String key)
    {
        Object val = parameters.get(key);
        return val != null ? val.toString() : null;
    }

    public int getIntParam(String key, int defaultValue)
    {
        return (int) getDoubleParam(key, defaultValue);
    }

    public double getDoubleParam(String key, double defaultValue)
    {
        Object val = parameters.get(key);
        if (val instanceof Number number)
        {
            return number.doubleValue();
        }
        if (val instanceof String string)
        {
            try
            {
                return Double.parseDouble(string);
            }
            catch (NumberFormatException e)
            {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public boolean getBoolParam(String key, boolean defaultValue)
    {
        Object val = parameters.get(key);
        if (val instanceof Boolean bool)
        {
            return bool;
        }
        if (val instanceof String string)
        {
            return Boolean.parseBoolean(string);
        }
        return defaultValue;
    }

    public int getDurationTicks()
    {
        return durationTicks;
    }

    public List<BotAction> getChildren()
    {
        return children;
    }

    public void setChildren(List<BotAction> children)
    {
        this.children = children;
    }

    public List<BotAction> getElseChildren()
    {
        return elseChildren;
    }

    public BotAction getCondition()
    {
        return condition;
    }

    @Override
    public String toString()
    {
        return "BotAction{type=" + type + ", params=" + parameters + ", duration=" + durationTicks + "}";
    }
}
