package carpet.logic.bot;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.helpers.EntityPlayerActionPack.Action;
import carpet.helpers.EntityPlayerActionPack.ActionType;
import carpet.logic.program.Bot;
import carpet.logic.program.BotActionException;
import carpet.patches.EntityPlayerMPFake;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A fake player as the program interpreter sees it.
 */
public class BotController implements Bot
{
    private static final List<String> EQUIPMENT_SLOTS = List.of("mainhand", "offhand", "head", "chest", "legs", "feet");

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
    public void swordBlock()
    {
        actionPack.start(ActionType.USE, Action.continuous());
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
        playerCommand("equip " + armorSet);
    }

    @Override
    public void equipItem(String slot, String item)
    {
        playerCommand("equip " + slot + " " + item);
    }

    @Override
    public void unequip(String slot)
    {
        for (String each : "all".equals(slot) ? EQUIPMENT_SLOTS : List.of(slot))
        {
            playerCommand("unequip " + each);
        }
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
        String modeArgument = "auto".equals(mode) ? "" : mode + " ";
        playerCommand(String.format(Locale.ROOT, "nav goto %s%.2f %.2f %.2f %.2f", modeArgument, x, y, z, radius));
    }

    @Override
    public void follow(String playerName, double distance)
    {
        ServerPlayer target = findPlayer(playerName);
        if (target == null)
        {
            throw new BotActionException("There is no player to follow");
        }
        playerCommand(String.format(Locale.ROOT, "nav follow %s %.2f", target.getGameProfile().name(), distance));
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
        playerCommand(gliding ? "glide start" : "glide stop");
    }

    @Override
    public void glideGoto(double x, double y, double z, double radius)
    {
        playerCommand(String.format(Locale.ROOT, "glide goto %.2f %.2f %.2f %.2f", x, y, z, radius));
    }

    @Override
    public void glideHeading(float yaw, float pitch)
    {
        playerCommand(String.format(Locale.ROOT, "glide heading %.1f %.1f", yaw, pitch));
    }

    @Override
    public void glideSpeed(double speed)
    {
        playerCommand(String.format(Locale.ROOT, "glide speed %.2f", speed));
    }

    @Override
    public void glideFreeze(boolean frozen)
    {
        playerCommand("glide freeze " + frozen);
    }

    @Override
    public void glideLand()
    {
        playerCommand("glide arrival land");
    }

    @Override
    public boolean isGliding()
    {
        return actionPack.isGlideEnabled();
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

    private void playerCommand(String arguments)
    {
        MinecraftServer server = player.level().getServer();
        String command = "player " + player.getGameProfile().name() + " " + arguments;
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
