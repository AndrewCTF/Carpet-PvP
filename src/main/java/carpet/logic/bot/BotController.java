package carpet.logic.bot;

import carpet.CarpetServer;
import carpet.CarpetSettings;
import carpet.api.settings.CarpetRule;
import carpet.api.settings.InvalidRuleValueException;
import carpet.api.settings.SettingsManager;
import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.helpers.EntityPlayerActionPack.Action;
import carpet.helpers.EntityPlayerActionPack.ActionType;
import carpet.pvp.nav.BotNavMode;
import carpet.logic.program.Bot;
import carpet.logic.program.BotActionException;
import carpet.patches.EntityPlayerMPFake;
import carpet.utils.ArmorSetDefinition;
import carpet.utils.CommandHelper;
import carpet.utils.EquipmentSlotMapping;
import carpet.utils.Messenger;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A fake player as the program interpreter sees it. Everything is done through the player's action pack,
 * the same way /player does it, or directly on the player; nothing goes through a command string.
 */
public class BotController implements Bot
{
    private static final List<EquipmentSlot> EQUIPMENT_SLOTS = List.of(
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private final ServerPlayer player;
    private final EntityPlayerActionPack actionPack;

    public BotController(ServerPlayer player)
    {
        this.player = player;
        this.actionPack = ((ServerPlayerInterface) player).getActionPack();
    }

    @Override
    public void move(float forward, float strafe)
    {
        actionPack.setForward(forward).setStrafing(strafe);
    }

    @Override
    public void strafe(float strafe)
    {
        actionPack.setStrafing(strafe);
    }

    @Override
    public void stopMoving()
    {
        actionPack.setForward(0).setStrafing(0);
    }

    @Override
    public void stopStrafing()
    {
        actionPack.setStrafing(0);
    }

    @Override
    public void setSprinting(boolean sprinting)
    {
        actionPack.setSprinting(sprinting);
    }

    @Override
    public void setSneaking(boolean sneaking)
    {
        actionPack.setSneaking(sneaking);
    }

    @Override
    public void jump()
    {
        actionPack.start(ActionType.JUMP, Action.once());
    }

    @Override
    public void mount(boolean onlyRideables)
    {
        actionPack.mount(onlyRideables);
    }

    @Override
    public void dismount()
    {
        actionPack.dismount();
    }

    @Override
    public void stopMovement()
    {
        actionPack.stopMovement();
    }

    @Override
    public void attack(String mode, int interval, boolean critical)
    {
        actionPack.setAttackCritical(critical);
        actionPack.start(ActionType.ATTACK, critical ? Action.onceUntilSuccess() : actionFor(mode, interval));
    }

    @Override
    public void stopAttack()
    {
        actionPack.start(ActionType.ATTACK, null);
        actionPack.setAttackCritical(false);
    }

    @Override
    public void use(String mode, int interval)
    {
        actionPack.start(ActionType.USE, actionFor(mode, interval));
    }

    @Override
    public void stopUse()
    {
        actionPack.start(ActionType.USE, null);
    }

    private static Action actionFor(String mode, int interval)
    {
        return switch (mode)
        {
            case "continuous" -> Action.continuous();
            case "interval" -> Action.interval(Math.max(1, interval));
            default -> Action.once();
        };
    }

    @Override
    public void selectHotbar(int slot)
    {
        actionPack.setSlot(slot);
    }

    @Override
    public void equipArmor(String armorSet)
    {
        ArmorSetDefinition set = ArmorSetDefinition.getArmorSet(armorSet);
        if (set == null)
        {
            throw new BotActionException("Unknown armor set '" + armorSet + "'");
        }
        set.getPieces().forEach((slot, item) -> player.setItemSlot(slot, new ItemStack(item(item))));
    }

    @Override
    public void equipItem(String slot, String item)
    {
        player.setItemSlot(slot(slot), new ItemStack(item(item)));
    }

    @Override
    public void unequip(String slot)
    {
        for (EquipmentSlot each : "all".equals(slot) ? EQUIPMENT_SLOTS : List.of(slot(slot)))
        {
            player.setItemSlot(each, ItemStack.EMPTY);
        }
    }

    private static EquipmentSlot slot(String name)
    {
        EquipmentSlot slot = EquipmentSlotMapping.fromString(name);
        if (slot == null)
        {
            throw new BotActionException("Unknown equipment slot '" + name + "'");
        }
        return slot;
    }

    private Item item(String id)
    {
        Identifier identifier = Identifier.tryParse(id);
        Item item = identifier == null ? null
                : player.registryAccess().lookupOrThrow(Registries.ITEM).get(identifier).map(Holder::value).orElse(null);
        if (item == null)
        {
            throw new BotActionException("Unknown item '" + id + "'");
        }
        return item;
    }

    @Override
    public void drop(boolean wholeStack)
    {
        actionPack.drop(-1, wholeStack);
    }

    @Override
    public void swapHands()
    {
        actionPack.start(ActionType.SWAP_HANDS, Action.once());
    }

    @Override
    public void lookDirection(String direction)
    {
        Direction dir = Direction.byName(direction);
        if (dir != null)
        {
            actionPack.look(dir);
        }
    }

    @Override
    public void lookAt(double x, double y, double z)
    {
        actionPack.lookAt(new Vec3(x, y, z));
    }

    @Override
    public void lookAtPlayer(String playerName)
    {
        ServerPlayer target = findPlayer(playerName);
        if (target != null)
        {
            actionPack.lookAt(target.getEyePosition());
        }
    }

    @Override
    public void look(float yaw, float pitch)
    {
        actionPack.look(yaw, pitch);
    }

    @Override
    public void turn(float yaw, float pitch)
    {
        actionPack.turn(yaw, pitch);
    }

    @Override
    public void navGoto(double x, double y, double z, String mode, double radius)
    {
        Vec3 goal = new Vec3(x, y, z);
        if ("air".equals(mode))
        {
            actionPack.setNavGotoAir(goal, radius, true);
        }
        else
        {
            actionPack.setNavGoto(goal, BotNavMode.valueOf(mode.toUpperCase(Locale.ROOT)), radius);
        }
    }

    @Override
    public void follow(String playerName, double distance)
    {
        actionPack.setNavFollow(target(playerName, "follow").getUUID(), distance);
    }

    @Override
    public void chase(String playerName, boolean critical, double range, int interval)
    {
        actionPack.setNavChase(target(playerName, "chase").getUUID(), critical, range, interval);
    }

    @Override
    public void patrol(double x1, double y1, double z1, double x2, double y2, double z2, boolean loop)
    {
        actionPack.setNavPatrol(List.of(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2)), loop);
    }

    private ServerPlayer target(String playerName, String purpose)
    {
        ServerPlayer target = findPlayer(playerName);
        if (target == null)
        {
            throw new BotActionException(playerName.isEmpty() ? "There is no player to " + purpose : "There is no player named '" + playerName + "' to " + purpose);
        }
        return target;
    }

    @Override
    public boolean fleeFrom(String playerName, double distance)
    {
        ServerPlayer target = findPlayer(playerName);
        if (target == null || player.distanceTo(target) >= distance)
        {
            return false;
        }
        Vec3 away = player.position().subtract(target.position()).multiply(1, 0, 1);
        away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
        Vec3 goal = player.position().add(away.scale(distance));
        navGoto(goal.x, goal.y, goal.z, "land", 2.0);
        return true;
    }

    @Override
    public void wander(double radius)
    {
        double angle = Math.random() * 2 * Math.PI;
        double range = Math.random() * radius;
        navGoto(player.getX() + Math.cos(angle) * range, player.getY(), player.getZ() + Math.sin(angle) * range, "land", 2.0);
    }

    @Override
    public boolean isNavigating()
    {
        return actionPack.isNavEnabled();
    }

    @Override
    public void stopNavigation()
    {
        actionPack.stopNavigation();
    }

    @Override
    public void setGliding(boolean gliding)
    {
        actionPack.setGlideEnabled(gliding);
    }

    @Override
    public void glideGoto(double x, double y, double z, double radius)
    {
        actionPack.setGlideEnabled(true);
        actionPack.setGlideGoto(new Vec3(x, y, z), radius);
    }

    @Override
    public void glideHeading(float yaw, float pitch)
    {
        actionPack.setGlideEnabled(true);
        actionPack.setGlideHeading(yaw, pitch);
    }

    @Override
    public void glideSpeed(double speed)
    {
        actionPack.setGlideSpeed(speed);
    }

    @Override
    public void glideFreeze(boolean frozen)
    {
        actionPack.setGlideFrozen(frozen);
    }

    @Override
    public void glideLand()
    {
        actionPack.setGlideArrivalAction(EntityPlayerActionPack.GlideArrivalAction.LAND);
    }

    @Override
    public boolean isGliding()
    {
        return actionPack.isGlideEnabled();
    }

    /**
     * The action pack stops navigation and gliding by itself while their rule is off, and a sword does not block.
     * Checking here turns that silent nothing into a reason the program's author gets to read.
     */
    @Override
    public void requireRule(String ruleName, UUID owner)
    {
        SettingsManager settings = CarpetServer.settingsManager;
        CarpetRule<?> rule = settings.getCarpetRule(ruleName);
        if (Boolean.TRUE.equals(rule.value()))
        {
            return;
        }
        ServerPlayer ownerPlayer = owner == null ? null : player.level().getServer().getPlayerList().getPlayer(owner);
        if (ownerPlayer != null && !settings.locked()
                && CommandHelper.canUseCommand(ownerPlayer.createCommandSourceStack(), CarpetSettings.carpetCommandPermissionLevel))
        {
            try
            {
                rule.set(ownerPlayer.createCommandSourceStack(), "true");
                Messenger.m(ownerPlayer, "w Your bot program turned on the carpet rule ", "y " + ruleName);
                return;
            }
            catch (InvalidRuleValueException e)
            {
                // refused by the rule itself: report it as off
            }
        }
        throw new BotActionException("The carpet rule '" + ruleName + "' is off. Turn it on with /carpet " + ruleName + " true");
    }

    /**
     * Runs a command as the player the program belongs to, with that player's permissions and never the console's.
     */
    @Override
    public void executeCommand(String command, UUID owner)
    {
        MinecraftServer server = player.level().getServer();
        ServerPlayer ownerPlayer = owner == null ? null : server.getPlayerList().getPlayer(owner);
        if (ownerPlayer == null)
        {
            throw new BotActionException(owner == null
                    ? "EXECUTE_COMMAND only runs in programs a player started from the web editor"
                    : "EXECUTE_COMMAND needs the player who started this program to be online");
        }
        server.getCommands().performPrefixedCommand(ownerPlayer.createCommandSourceStack(), command);
    }

    @Override
    public void stopAll()
    {
        actionPack.stopAll();
    }

    @Override
    public double health()
    {
        return player.getHealth();
    }

    @Override
    public double food()
    {
        return player.getFoodData().getFoodLevel();
    }

    @Override
    public double armor()
    {
        return player.getArmorValue();
    }

    @Override
    public double distanceToPlayer(String playerName)
    {
        ServerPlayer target = findPlayer(playerName);
        return target == null || target.level() != player.level() ? Double.POSITIVE_INFINITY : player.distanceTo(target);
    }

    @Override
    public double distanceTo(double x, double y, double z)
    {
        return Math.sqrt(player.distanceToSqr(x, y, z));
    }

    @Override
    public boolean hasItem(String itemName)
    {
        String wanted = itemName.toLowerCase(Locale.ROOT);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
        {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem().toString().contains(wanted))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isFlying()
    {
        return player.isFallFlying();
    }

    @Override
    public boolean isSneaking()
    {
        return player.isCrouching();
    }

    @Override
    public boolean isSprinting()
    {
        return player.isSprinting();
    }

    @Override
    public boolean isInWater()
    {
        return player.isInWater();
    }

    /**
     * @param playerName a player name, or empty for the nearest other player that is not a bot
     */
    private ServerPlayer findPlayer(String playerName)
    {
        MinecraftServer server = player.level().getServer();
        if (!playerName.isEmpty())
        {
            ServerPlayer named = server.getPlayerList().getPlayerByName(playerName);
            return named == player ? null : named;
        }
        ServerPlayer nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ServerPlayer other : server.getPlayerList().getPlayers())
        {
            if (other == player || other instanceof EntityPlayerMPFake || other.level() != player.level())
            {
                continue;
            }
            double distance = player.distanceToSqr(other);
            if (distance < nearestDistance)
            {
                nearestDistance = distance;
                nearest = other;
            }
        }
        return nearest;
    }
}
