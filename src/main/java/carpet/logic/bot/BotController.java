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
import carpet.pvp.BotEvents;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitInventory;
import carpet.pvp.kit.KitStore;
import carpet.pvp.nav.BotNavMode;
import carpet.logic.program.Bot;
import carpet.logic.program.BotActionException;
import carpet.patches.EntityPlayerMPFake;
import carpet.utils.ArmorSetDefinition;
import carpet.utils.CommandHelper;
import carpet.utils.EquipmentSlotMapping;
import carpet.utils.Messenger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A fake player as the program interpreter sees it. Everything is done through the player's action pack,
 * the same way /player does it, or directly on the player; nothing goes through a command string.
 */
public class BotController implements Bot
{
    /** How far around the bot an expression may count entities. */
    private static final double MAX_ENTITY_RADIUS = 64.0D;
    private static final List<EquipmentSlot> EQUIPMENT_SLOTS = List.of(
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private final EntityPlayerMPFake player;
    private final EntityPlayerActionPack actionPack;

    public BotController(EntityPlayerMPFake player)
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

    @Override
    public void startCombat(String style, String difficulty, String targets, String target)
    {
        BotPvpConfig.CombatStyle kind = styleOf(style);
        // Everything that can be refused is checked before anything of the bot is changed.
        LivingEntity named = target.isEmpty() ? null : findEntity(target);
        if (!target.isEmpty() && named == null)
        {
            throw new BotActionException("There is no entity named '" + target + "' to fight");
        }
        BotPvpConfig cfg = player.getPvpConfig();
        cfg.combatStyle = kind;
        String error = cfg.apply("difficulty", difficulty);
        if (error != null)
        {
            throw new BotActionException(error);
        }
        // Which kinds of entity the AI may pick. Anything but players, mobs, bots and all means none of them,
        // which is what the schema's "none" is for.
        boolean all = targets.equals("all");
        cfg.targetPlayers = all || targets.equals("players");
        cfg.targetMobs = all || targets.equals("mobs");
        cfg.targetBots = all || targets.equals("bots");
        if (named != null)
        {
            // One named entity: only what it is may be fought, and the range has to reach it. The AI then takes
            // the nearest one of that kind, which is the named one while it is the closer of the two.
            cfg.targetPlayers = named instanceof ServerPlayer && !(named instanceof EntityPlayerMPFake);
            cfg.targetMobs = named instanceof Mob;
            cfg.targetBots = named instanceof EntityPlayerMPFake;
            cfg.targetRange = Mth.clamp(player.distanceTo(named) + 1.0D, 2.0D, 64.0D);
        }
        cfg.autoTarget = true;
        cfg.combat = true;
        // The brain owns the body from here on, so the program lets go of what it was holding down.
        actionPack.stopNavigation();
        actionPack.stopAll();
    }

    private static BotPvpConfig.CombatStyle styleOf(String style)
    {
        try
        {
            return BotPvpConfig.styleOf(style);
        }
        catch (IllegalArgumentException e)
        {
            throw new BotActionException("Unknown combat style: " + style);
        }
    }

    @Override
    public void stopCombat()
    {
        player.getPvpConfig().combat = false;
        // The brain lets go of what it was driving on its next tick; nothing of it may keep the bot moving.
        actionPack.stopNavigation();
        actionPack.stopAll();
    }

    @Override
    public void combatOption(String key, String value)
    {
        String error = player.getPvpConfig().apply(key, value);
        if (error != null)
        {
            throw new BotActionException(error);
        }
    }

    @Override
    public void giveKit(String kit)
    {
        MinecraftServer server = player.level().getServer();
        KitStore store = KitStore.of(server);
        Kit found = store.get(kit).orElse(null);
        if (found == null)
        {
            throw new BotActionException("There is no kit called " + kit + ". Kits: " + String.join(", ", store.names()));
        }
        KitInventory.apply(player, found, server.registryAccess());
    }

    /**
     * A living entity of this world by name: a player by the name it plays under, anything else by the name it
     * goes by. Null when there is none, or when it is in another dimension.
     */
    private LivingEntity findEntity(String name)
    {
        MinecraftServer server = player.level().getServer();
        ServerPlayer named = server.getPlayerList().getPlayerByName(name);
        if (named != null)
        {
            return named != player && named.level() == player.level() ? named : null;
        }
        for (LivingEntity living : player.level().getEntities(EntityTypeTest.forClass(LivingEntity.class),
                living1 -> living1 != player && living1.isAlive() && name.equalsIgnoreCase(living1.getName().getString())))
        {
            return living;
        }
        return null;
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
    public String name()
    {
        return player.getGameProfile().name();
    }

    @Override
    public double health()
    {
        return player.getHealth();
    }

    @Override
    public double maxHealth()
    {
        return player.getMaxHealth();
    }

    @Override
    public double x()
    {
        return player.getX();
    }

    @Override
    public double y()
    {
        return player.getY();
    }

    @Override
    public double z()
    {
        return player.getZ();
    }

    @Override
    public double yaw()
    {
        return player.getYRot();
    }

    @Override
    public double pitch()
    {
        return player.getXRot();
    }

    @Override
    public String heldItem()
    {
        return itemId(player.getMainHandItem());
    }

    @Override
    public String offhandItem()
    {
        return itemId(player.getOffhandItem());
    }

    @Override
    public int heldCount()
    {
        return player.getMainHandItem().getCount();
    }

    @Override
    public int hotbarSlot()
    {
        return player.getInventory().getSelectedSlot() + 1;
    }

    @Override
    public boolean isOnGround()
    {
        return player.onGround();
    }

    @Override
    public boolean isBlocking()
    {
        return player.isBlocking();
    }

    @Override
    public boolean isUsingItem()
    {
        return player.isUsingItem();
    }

    @Override
    public int countItem(String item)
    {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
        {
            ItemStack stack = player.getInventory().getItem(i);
            if (itemId(stack).equals(item.toLowerCase(Locale.ROOT)))
            {
                count += stack.getCount();
            }
        }
        return count;
    }

    @Override
    public String blockAt(double x, double y, double z)
    {
        BlockPos pos = BlockPos.containing(x, y, z);
        // Asking about a block must not load the chunk it is in.
        if (!player.level().isLoaded(pos))
        {
            return "unloaded";
        }
        return BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).getBlock()).getPath();
    }

    @Override
    public int countEntities(String type, double radius)
    {
        String wanted = type.toLowerCase(Locale.ROOT);
        double reach = Math.max(0.0D, Math.min(MAX_ENTITY_RADIUS, radius));
        return player.level().getEntities(player, player.getBoundingBox().inflate(reach), entity ->
                entity.isAlive() && entity.distanceTo(player) <= reach && switch (wanted)
                {
                    case "any" -> entity instanceof LivingEntity;
                    case "hostile" -> entity instanceof Enemy;
                    default -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().equals(wanted);
                }).size();
    }

    // An item's id the way a player writes it: without the namespace the game's own items share.
    private static String itemId(ItemStack stack)
    {
        if (stack.isEmpty())
        {
            return "air";
        }
        String id = stack.getItem().toString();
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    @Override
    public String targetName()
    {
        LivingEntity target = target();
        return target == null ? "" : target.getName().getString();
    }

    @Override
    public String targetHeldItem()
    {
        LivingEntity target = target();
        return target == null ? "air" : itemId(target.getMainHandItem());
    }

    @Override
    public boolean isTargetBlocking()
    {
        LivingEntity target = target();
        return target != null && target.isBlocking();
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

    @Override
    public boolean isAlive()
    {
        return player.isAlive();
    }

    @Override
    public boolean isFighting()
    {
        return player.getPvpConfig().combat && hasTarget();
    }

    @Override
    public boolean hasTarget()
    {
        return target() != null;
    }

    @Override
    public double targetDistance()
    {
        LivingEntity target = target();
        return target == null ? Double.POSITIVE_INFINITY : player.distanceTo(target);
    }

    @Override
    public double targetHealth()
    {
        LivingEntity target = target();
        return target == null ? Double.POSITIVE_INFINITY : target.getHealth();
    }

    @Override
    public Set<BotEvents.Event> combatEvents()
    {
        return BotEvents.drain(player);
    }

    /** The entity the bot's combat AI is fighting, or null while it is fighting none. */
    private LivingEntity target()
    {
        return player.getBotBrain().target();
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
