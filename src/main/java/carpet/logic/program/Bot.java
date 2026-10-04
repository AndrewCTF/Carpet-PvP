package carpet.logic.program;

import carpet.pvp.BotEvents;

import java.util.Set;
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
    void chase(String player, boolean critical, double range, int interval);
    void patrol(double x1, double y1, double z1, double x2, double y2, double z2, boolean loop);
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
     * Makes sure a boolean carpet rule an action depends on is on. If it is off, it is turned on when the
     * program's owner is allowed to change carpet rules; otherwise this throws.
     *
     * @param owner the player the program runs on behalf of, or null when there is none
     */
    void requireRule(String rule, UUID owner);

    /**
     * Turns the bot's combat AI on and lets go of the body, so that the AI is the only thing driving the
     * action pack until {@link #stopCombat()} is called.
     *
     * @param style      a combat style, by the name {@code /bot spawn} takes
     * @param difficulty a difficulty preset name
     * @param targets    which kinds of entity to fight: players, mobs, bots, all or none
     * @param target     the name of one entity to fight, or empty to take any of the kinds
     */
    void startCombat(String style, String difficulty, String targets, String target);
    /** Turns the combat AI off and releases whatever the style left running. */
    void stopCombat();
    /**
     * Applies one combat setting by the name and value {@code /bot option} takes, including the options only
     * one combat style reads. An unknown key or an unusable value throws.
     */
    void combatOption(String key, String value);
    /** Puts the kit of that name on the bot, clearing what it was carrying, the way {@code /bot kit give} does. */
    void giveKit(String kit);

    /**
     * @param owner the player the program runs on behalf of, or null when there is none
     */
    void executeCommand(String command, UUID owner);
    void stopAll();

    String name();
    double health();
    double maxHealth();
    double food();
    double armor();
    double x();
    double y();
    double z();
    double yaw();
    double pitch();
    /** The id of what is in the main hand without its namespace, or "air" for an empty hand. */
    String heldItem();
    String offhandItem();
    int heldCount();
    /** The selected hotbar slot, 1 to 9. */
    int hotbarSlot();
    boolean isOnGround();
    boolean isBlocking();
    boolean isUsingItem();
    /** How many of an item the bot carries, by the item's id without its namespace. */
    int countItem(String item);
    /** The id of the block at a position without its namespace, or "unloaded" where the world is not loaded. */
    String blockAt(double x, double y, double z);
    /**
     * @param type an entity type's id without its namespace, or "any" for every living thing, "hostile" for monsters
     * @return how many such entities other than the bot are within the radius
     */
    int countEntities(String type, double radius);
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
    boolean isAlive();

    /** True while the combat AI is on and the bot has a target it is engaging. */
    boolean isFighting();
    /** True while the bot has a target it is fighting. */
    boolean hasTarget();
    /**
     * @return the distance to the target, or infinity when there is none
     */
    double targetDistance();
    /**
     * @return the target's health, or infinity when there is no target, as there is no distance to it either
     */
    double targetHealth();
    /** The target's name, or empty when there is none. */
    String targetName();
    /** The id of what the target holds, or "air" when it holds nothing or there is no target. */
    String targetHeldItem();
    boolean isTargetBlocking();
    /** The fight events the game has reported since this was last called, and forgets them. */
    Set<BotEvents.Event> combatEvents();
}
