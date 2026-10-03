package carpet.logic.bot;

import carpet.CarpetSettings;
import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.helpers.EntityPlayerActionPack.Action;
import carpet.helpers.EntityPlayerActionPack.ActionType;
import carpet.patches.EntityPlayerMPFake;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * The operations a bot program can perform on one fake player.
 */
public class BotController
{
    private final ServerPlayer player;
    private final EntityPlayerActionPack actionPack;

    private enum NavMode { NONE, GOTO, FOLLOW, FLEE, WANDER }
    private NavMode currentNavMode = NavMode.NONE;
    private String navTargetPlayer;
    private double navTargetDistance;
    private double navGoalX, navGoalY, navGoalZ;
    private double navGoalRadius;
    private int navRepathCooldown;
    private boolean navComplete;
    private int navStuckTicks;
    private double navLastX, navLastZ;

    private static final int REPATH_FOLLOW = 20;
    private static final int REPATH_FLEE = 10;
    private static final int REPATH_WANDER = 60;
    private static final int STUCK_TICKS = 40;
    private static final double STUCK_DIST = 0.25;

    public BotController(ServerPlayer player)
    {
        this.player = player;
        this.actionPack = ((ServerPlayerInterface) player).getActionPack();
    }

    public ServerPlayer getPlayer()
    {
        return player;
    }

    public void move(String direction)
    {
        switch (direction == null ? "stop" : direction.toLowerCase(Locale.ROOT))
        {
            case "forward" -> actionPack.setForward(1).setStrafing(0);
            case "backward" -> actionPack.setForward(-1).setStrafing(0);
            case "left" -> actionPack.setForward(0).setStrafing(1);
            case "right" -> actionPack.setForward(0).setStrafing(-1);
            default -> actionPack.setForward(0).setStrafing(0);
        }
    }

    public void sprint(boolean start)
    {
        actionPack.setSprinting(start);
    }

    public void sneak(boolean start)
    {
        actionPack.setSneaking(start);
    }

    public void jump()
    {
        actionPack.start(ActionType.JUMP, Action.once());
    }

    public void strafe(String direction)
    {
        switch (direction == null ? "left" : direction.toLowerCase(Locale.ROOT))
        {
            case "left" -> actionPack.setStrafing(1);
            case "right" -> actionPack.setStrafing(-1);
            default -> actionPack.setStrafing(0);
        }
    }

    public void mount(boolean onlyRideables)
    {
        actionPack.mount(onlyRideables);
    }

    public void dismount()
    {
        actionPack.dismount();
    }

    public void stopMovement()
    {
        actionPack.stopMovement();
    }

    public void attack(String mode, boolean crit, int interval)
    {
        if (crit)
        {
            actionPack.start(ActionType.JUMP, Action.once());
        }
        actionPack.start(ActionType.ATTACK, actionFor(mode, interval));
    }

    public void swordBlock()
    {
        actionPack.start(ActionType.USE, Action.once());
    }

    public void shieldBlock()
    {
        actionPack.start(ActionType.USE, Action.continuous());
    }

    public void use(String mode, int interval)
    {
        actionPack.start(ActionType.USE, actionFor(mode, interval));
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

    public void equipArmor(String armorSet)
    {
        playerCommand("equip " + armorSet);
    }

    public void equipSlot(String slot, String item)
    {
        playerCommand("equip " + slot + " " + item);
    }

    public void unequip(String slot)
    {
        playerCommand("unequip " + slot);
    }

    public void hotbar(int slot)
    {
        if (slot >= 1 && slot <= 9)
        {
            actionPack.setSlot(slot);
        }
    }

    public void drop()
    {
        actionPack.start(ActionType.DROP_ITEM, Action.once());
    }

    public void dropStack()
    {
        actionPack.start(ActionType.DROP_STACK, Action.once());
    }

    public void swapHands()
    {
        actionPack.start(ActionType.SWAP_HANDS, Action.once());
    }

    public void lookDirection(String direction)
    {
        Direction dir = direction == null ? null : Direction.byName(direction.toLowerCase(Locale.ROOT));
        if (dir != null)
        {
            actionPack.look(dir);
        }
    }

    public void lookAt(double x, double y, double z)
    {
        actionPack.lookAt(new net.minecraft.world.phys.Vec3(x, y, z));
    }

    public void lookAtPlayer(String playerName)
    {
        ServerPlayer target = findPlayer(playerName);
        if (target != null)
        {
            actionPack.lookAt(target.getEyePosition());
        }
    }

    public void lookYawPitch(float yaw, float pitch)
    {
        actionPack.look(yaw, pitch);
    }

    public void turn(String direction, float degrees)
    {
        switch (direction == null ? "right" : direction.toLowerCase(Locale.ROOT))
        {
            case "left" -> actionPack.turn(-degrees, 0);
            case "back" -> actionPack.turn(180, 0);
            default -> actionPack.turn(degrees, 0);
        }
    }

    public void navGoto(double x, double y, double z, String mode, double radius)
    {
        resetNavState();
        currentNavMode = NavMode.GOTO;
        navGoalX = x;
        navGoalY = y;
        navGoalZ = z;
        navGoalRadius = radius;
        issueNavCommand(x, y, z, mode, radius);
    }

    public void navStop()
    {
        resetNavState();
        actionPack.stopNavigation();
    }

    public void followPlayer(String playerName, double distance)
    {
        resetNavState();
        currentNavMode = NavMode.FOLLOW;
        navTargetPlayer = playerName;
        navTargetDistance = distance;
        navGoalRadius = distance;
        ServerPlayer target = findPlayer(playerName);
        if (target != null)
        {
            issueNavCommand(target.getX(), target.getY(), target.getZ(), "land", distance);
        }
        else
        {
            navComplete = true;
        }
    }

    public void fleeFrom(String playerName, double distance)
    {
        resetNavState();
        currentNavMode = NavMode.FLEE;
        navTargetPlayer = playerName;
        navTargetDistance = distance;
        navGoalRadius = 2.0;
        issueFleeCommand();
    }

    public void wander(double radius)
    {
        resetNavState();
        currentNavMode = NavMode.WANDER;
        navTargetDistance = radius;
        navGoalRadius = 2.0;
        pickWanderTarget();
    }

    /**
     * Called every tick while a navigation action is active: re-targets follow, flee and wander,
     * and re-issues the goal when the bot stops making progress.
     */
    public void tickNavigation()
    {
        if (navComplete || currentNavMode == NavMode.NONE)
        {
            return;
        }

        double dx = player.getX() - navLastX;
        double dz = player.getZ() - navLastZ;
        if (dx * dx + dz * dz < STUCK_DIST * STUCK_DIST)
        {
            if (++navStuckTicks >= STUCK_TICKS)
            {
                navStuckTicks = 0;
                reissueCurrent();
            }
        }
        else
        {
            navStuckTicks = 0;
            navLastX = player.getX();
            navLastZ = player.getZ();
        }

        if (currentNavMode == NavMode.GOTO)
        {
            if (player.distanceToSqr(navGoalX, navGoalY, navGoalZ) <= navGoalRadius * navGoalRadius)
            {
                completeNavigation();
            }
            return;
        }

        if (--navRepathCooldown > 0)
        {
            return;
        }

        switch (currentNavMode)
        {
            case FOLLOW ->
            {
                ServerPlayer target = findPlayer(navTargetPlayer);
                if (target == null)
                {
                    completeNavigation();
                    return;
                }
                actionPack.lookAt(target.getEyePosition());
                if (player.distanceTo(target) <= navTargetDistance)
                {
                    actionPack.stopMovement();
                    navRepathCooldown = 5;
                    return;
                }
                issueNavCommand(target.getX(), target.getY(), target.getZ(), "land", navTargetDistance);
                navRepathCooldown = REPATH_FOLLOW;
            }
            case FLEE ->
            {
                ServerPlayer target = findPlayer(navTargetPlayer);
                if (target == null || player.distanceTo(target) >= navTargetDistance)
                {
                    completeNavigation();
                    return;
                }
                issueFleeCommand();
                navRepathCooldown = REPATH_FLEE;
            }
            case WANDER ->
            {
                if (player.distanceToSqr(navGoalX, navGoalY, navGoalZ) <= navGoalRadius * navGoalRadius)
                {
                    pickWanderTarget();
                }
                navRepathCooldown = REPATH_WANDER;
            }
            default ->
            {
            }
        }
    }

    public boolean isNavigationComplete()
    {
        return navComplete || currentNavMode == NavMode.NONE;
    }

    private void resetNavState()
    {
        currentNavMode = NavMode.NONE;
        navTargetPlayer = null;
        navTargetDistance = 0;
        navGoalX = navGoalY = navGoalZ = 0;
        navGoalRadius = 1;
        navRepathCooldown = 0;
        navComplete = false;
        navStuckTicks = 0;
        navLastX = player.getX();
        navLastZ = player.getZ();
    }

    private void completeNavigation()
    {
        navComplete = true;
        actionPack.stopMovement();
    }

    private void issueNavCommand(double x, double y, double z, String mode, double radius)
    {
        if (mode == null || mode.isEmpty() || "auto".equalsIgnoreCase(mode))
        {
            mode = "land";
        }
        playerCommand(String.format(Locale.ROOT, "nav goto %s %.0f %.0f %.0f %.1f", mode, x, y, z, radius));
    }

    private void issueFleeCommand()
    {
        ServerPlayer target = findPlayer(navTargetPlayer);
        if (target == null)
        {
            navComplete = true;
            return;
        }
        double dx = player.getX() - target.getX();
        double dz = player.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.1)
        {
            dx = 1;
            dz = 0;
            len = 1;
        }
        issueNavCommand(player.getX() + dx / len * navTargetDistance, player.getY(), player.getZ() + dz / len * navTargetDistance, "land", 2.0);
    }

    private void pickWanderTarget()
    {
        double angle = Math.random() * 2 * Math.PI;
        double r = Math.random() * navTargetDistance;
        navGoalX = player.getX() + Math.cos(angle) * r;
        navGoalZ = player.getZ() + Math.sin(angle) * r;
        navGoalY = player.getY();
        issueNavCommand(navGoalX, navGoalY, navGoalZ, "land", navGoalRadius);
    }

    private void reissueCurrent()
    {
        switch (currentNavMode)
        {
            case GOTO -> issueNavCommand(navGoalX, navGoalY, navGoalZ, "land", navGoalRadius);
            case FOLLOW ->
            {
                ServerPlayer target = findPlayer(navTargetPlayer);
                if (target != null)
                {
                    issueNavCommand(target.getX(), target.getY(), target.getZ(), "land", navTargetDistance);
                }
            }
            case FLEE -> issueFleeCommand();
            case WANDER -> pickWanderTarget();
            default ->
            {
            }
        }
    }

    public void glideStart()
    {
        playerCommand("glide start");
    }

    public void glideStop()
    {
        playerCommand("glide stop");
    }

    public void glideGoto(double x, double y, double z, double radius)
    {
        playerCommand(String.format(Locale.ROOT, "glide goto %.0f %.0f %.0f %.1f", x, y, z, radius));
    }

    public void glideHeading(float yaw, float pitch)
    {
        playerCommand(String.format(Locale.ROOT, "glide heading %.1f %.1f", yaw, pitch));
    }

    public void glideSpeed(double speed)
    {
        playerCommand(String.format(Locale.ROOT, "glide speed %.2f", speed));
    }

    public void glideFreeze()
    {
        playerCommand("glide freeze");
    }

    public void glideLand()
    {
        playerCommand("glide arrival land");
    }

    /**
     * Arbitrary commands are not run until programs carry the identity of whoever started them.
     */
    public void executeCommand(String command)
    {
        CarpetSettings.LOG.warn("[CarpetLogic] Ignored EXECUTE_COMMAND '{}': programs have no owner to run it as", command);
    }

    /**
     * @param playerName a player name, or empty / "nearest" for the closest other player
     */
    public ServerPlayer findPlayer(String playerName)
    {
        MinecraftServer server = player.level().getServer();
        if (playerName != null && !playerName.isEmpty() && !"nearest".equalsIgnoreCase(playerName))
        {
            return server.getPlayerList().getPlayerByName(playerName);
        }
        ServerPlayer nearest = null;
        double minDist = Double.MAX_VALUE;
        for (ServerPlayer other : server.getPlayerList().getPlayers())
        {
            if (other == player || other.level() != player.level())
            {
                continue;
            }
            double dist = player.distanceToSqr(other);
            if (dist < minDist)
            {
                minDist = dist;
                nearest = other;
            }
        }
        return nearest;
    }

    public float getHealth()
    {
        return player.getHealth();
    }

    public int getFoodLevel()
    {
        return player.getFoodData().getFoodLevel();
    }

    public double getDistanceToNearestPlayer()
    {
        double minDist = Double.MAX_VALUE;
        for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers())
        {
            if (other == player || other instanceof EntityPlayerMPFake || other.level() != player.level())
            {
                continue;
            }
            minDist = Math.min(minDist, player.distanceTo(other));
        }
        return minDist;
    }

    public boolean hasItem(String itemName)
    {
        if (itemName == null)
        {
            return false;
        }
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

    public boolean isFlying()
    {
        return player.isFallFlying();
    }

    public boolean isSneaking()
    {
        return player.isCrouching();
    }

    public boolean isSprinting()
    {
        return player.isSprinting();
    }

    public boolean isInWater()
    {
        return player.isInWater();
    }

    public int getArmorValue()
    {
        return player.getArmorValue();
    }

    public void stopAll()
    {
        resetNavState();
        actionPack.stopAll();
    }

    private void playerCommand(String arguments)
    {
        MinecraftServer server = player.level().getServer();
        String command = "player " + player.getGameProfile().name() + " " + arguments;
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
