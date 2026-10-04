package carpet.logic.bot;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.logic.program.ProgramExecutor.ProgramInfo;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig.CombatStyle;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;

/**
 * The live state of every fake player on the server, as the web editor's panel draws it. Gathering it reads the
 * game, so it is only ever done on the server thread.
 */
public final class BotSnapshot
{
    private BotSnapshot() {}

    /**
     * Every fake player there is, by name, with the program it runs.
     */
    public static JsonObject of(BotManager botManager, Map<String, ProgramInfo> programs)
    {
        JsonObject snapshot = new JsonObject();
        for (ServerPlayer bot : botManager.getBots())
        {
            String name = bot.getGameProfile().name();
            snapshot.add(name, describe(bot, programs.get(name)));
        }
        return snapshot;
    }

    public static JsonObject describe(ServerPlayer bot, ProgramInfo program)
    {
        JsonObject json = new JsonObject();
        json.addProperty("name", bot.getGameProfile().name());
        json.addProperty("dimension", bot.level().dimension().identifier().toString());
        json.addProperty("x", bot.getX());
        json.addProperty("y", bot.getY());
        json.addProperty("z", bot.getZ());
        json.addProperty("yaw", bot.getYRot());
        json.addProperty("pitch", bot.getXRot());
        json.addProperty("health", bot.getHealth());
        json.addProperty("maxHealth", bot.getMaxHealth());
        json.addProperty("absorption", bot.getAbsorptionAmount());
        json.addProperty("foodLevel", bot.getFoodData().getFoodLevel());
        json.addProperty("armor", bot.getArmorValue());
        json.addProperty("alive", bot.isAlive());
        json.addProperty("gamemode", bot.gameMode.getGameModeForPlayer().getName());
        json.addProperty("sprinting", bot.isSprinting());
        json.addProperty("sneaking", bot.isCrouching());
        json.add("equipment", equipment(bot));
        json.add("pvp", bot instanceof EntityPlayerMPFake fake
                ? combat(fake.getPvpConfig().combat, fake.getPvpConfig().combatStyle) : JsonNull.INSTANCE);
        json.addProperty("target", target(bot));
        json.add("program", program == null ? JsonNull.INSTANCE : program(program));
        return json;
    }

    /**
     * The combat settings a panel shows and changes, in the shape the panel and the config route both use.
     */
    public static JsonObject combat(boolean combat, CombatStyle style)
    {
        JsonObject pvp = new JsonObject();
        pvp.addProperty("combat", combat);
        pvp.addProperty("style", style.name());
        return pvp;
    }

    private static JsonObject equipment(ServerPlayer bot)
    {
        JsonObject equipment = new JsonObject();
        for (EquipmentSlot slot : EquipmentSlot.values())
        {
            ItemStack stack = bot.getItemBySlot(slot);
            equipment.addProperty(slot.getName(), stack.isEmpty() ? "empty" : stack.getItem().toString());
        }
        return equipment;
    }

    /**
     * Who the bot is going after: the one it is chasing, which is what its combat brain picked. A chase can be
     * on a mob, so anything that is not a player is named by the entity itself.
     */
    private static String target(ServerPlayer bot)
    {
        EntityPlayerActionPack pack = ((ServerPlayerInterface) bot).getActionPack();
        UUID chased = pack == null ? null : pack.getNavChaseTarget();
        if (chased == null)
        {
            return null;
        }
        ServerPlayer player = bot.level().getServer().getPlayerList().getPlayer(chased);
        if (player != null)
        {
            return player.getGameProfile().name();
        }
        Entity entity = bot.level().getEntity(chased);
        return entity == null ? null : entity.getName().getString();
    }

    private static JsonObject program(ProgramInfo info)
    {
        JsonObject program = new JsonObject();
        program.addProperty("name", info.programName());
        program.addProperty("status", info.status());
        program.addProperty("action", info.currentAction());
        program.addProperty("error", info.error());
        program.addProperty("running", info.running());
        return program;
    }
}