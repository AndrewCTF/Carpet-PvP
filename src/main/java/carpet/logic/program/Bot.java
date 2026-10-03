package carpet.logic.program;

import java.util.UUID;

/**
 * What the interpreter can make a bot do, and ask about it. Player names may be empty, meaning the nearest
 * other player. An operation that cannot be carried out throws {@link BotActionException}.
 */
public interface Bot
{
    void move(float forward, float strafe);
    void strafe(float strafe);
    void stopMoving();
    void stopStrafing();
    void setSprinting(boolean sprinting);
    void setSneaking(boolean sneaking);
    void jump();
    void mount(boolean onlyRideables);
    void dismount();
    void stopMovement();

    void attack(String mode, int interval, boolean critical);
    void stopAttack();
    void use(String mode, int interval);
    /**
     * Holds right click to block with a sword, until {@link #stopUse()}.
     */
    void swordBlock();
    void stopUse();

    void selectHotbar(int slot);
    void equipArmor(String armorSet);
    void equipItem(String slot, String item);
    void unequip(String slot);
    void drop(boolean wholeStack);
    void swapHands();

    void lookDirection(String direction);
    void lookAt(double x, double y, double z);
    void lookAtPlayer(String player);
    void look(float yaw, float pitch);
    void turn(float yaw, float pitch);

    void navGoto(double x, double y, double z, String mode, double radius);
    void follow(String player, double distance);
    /**
     * Heads directly away from the player.
     *
     * @return false when there is nobody to flee from or the player is already further away than distance
     */
    boolean fleeFrom(String player, double distance);
    void wander(double radius);
    boolean isNavigating();
    void stopNavigation();

    void setGliding(boolean gliding);
    void glideGoto(double x, double y, double z, double radius);
    void glideHeading(float yaw, float pitch);
    void glideSpeed(double speed);
    void glideFreeze(boolean frozen);
    void glideLand();
    boolean isGliding();

    /**
     * @param owner the player the program runs on behalf of, or null when there is none
     */
    void executeCommand(String command, UUID owner);
    void stopAll();

    double health();
    double food();
    double armor();
    /**
     * @return the distance to the player, or infinity when there is no such player
     */
    double distanceToPlayer(String player);
    double distanceTo(double x, double y, double z);
    boolean hasItem(String item);
    boolean isFlying();
    boolean isSneaking();
    boolean isSprinting();
    boolean isInWater();
}
