package carpet.pvp.nav;

import carpet.helpers.EntityPlayerActionPack;
import carpet.helpers.EntityPlayerActionPack.Action;
import carpet.helpers.EntityPlayerActionPack.ActionType;
import carpet.helpers.EntityPlayerActionPack.GlideArrivalAction;
import carpet.patches.EntityPlayerMPFake;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fake-player navigation. One instance per {@link EntityPlayerActionPack}, ticked from its
 * {@code onUpdate()} between the action loop and the glide controller.
 *
 * <p>Rule values arrive as a {@link Settings} snapshot built once per tick by the action pack, so this class
 * never reads server settings itself. Per-bot overrides set with {@code /player <bot> nav option ...} win over
 * the snapshot; a {@code null} override means "follow the rule".</p>
 */
public final class NavController
{
    /**
     * Every carpet rule navigation reads, snapshotted by the action pack once per tick.
     */
    public record Settings(
            boolean enabled,
            boolean elytraGlide,
            boolean breakBlocks,
            boolean placeBlocks,
            boolean autoTool,
            boolean autoEat,
            int autoEatBelow,
            boolean avoidLava,
            boolean avoidFire,
            boolean avoidCobwebs,
            boolean breakCobwebs,
            boolean avoidPowderSnow,
            boolean allowParkour,
            boolean allowPillar,
            boolean allowBreakThrough,
            boolean allowDescendMine,
            boolean allowSprint,
            boolean mobAvoidance,
            int mobAvoidanceRadius,
            int maxFallHeight,
            boolean avoidSoulSand,
            boolean allowOpenDoors,
            boolean allowOpenFenceGates,
            boolean allowSwimming
    ) {}

    private static final ElytraAStarPathfinder ELYTRA_PATHFINDER = new ElytraAStarPathfinder();
    private static final NavAStarPathfinder ASTAR = new NavAStarPathfinder();

    public static final int CHASE_CRIT_JUMP_LEAD_TICKS = 6;
    private static final int CHASE_REPATH_INTERVAL = 10;
    private static final double DEFAULT_CHASE_ATTACK_RANGE = 2.5D;

    private enum AirArrival
    {
        LAND,
        DROP
    }

    private final EntityPlayerActionPack pack;
    private final ServerPlayer player;

    private boolean navEnabled = false;
    private BotNavMode navMode = BotNavMode.AUTO;
    private Vec3 navTargetPos = null;
    private double navArrivalRadius = 1.0D;

    private AirArrival navAirArrival = AirArrival.LAND;
    private Vec3 navAirRequestedTargetPos = null;

    private List<BlockPos> navNodes = null;
    private List<Vec3> navWaypoints = null;
    private int navWaypointIndex = 0;
    private int navRepathCooldownTicks = 0;
    private boolean navNeedsRepath = false;

    private double navLastDistanceToNext = Double.POSITIVE_INFINITY;
    private int navNoProgressTicks = 0;
    private int navJumpCooldownTicks = 0;
    private boolean navWaterJumping = false;

    // Navigation behavior overrides (null = use the settings snapshot).
    private Boolean navAllowBreakBlocks;
    private Boolean navAllowPlaceBlocks;
    private Boolean navAutoTool;
    private Boolean navAutoEat;
    private Integer navAutoEatBelow;
    private Boolean navAvoidLava;
    private Boolean navAvoidFire;
    private Boolean navAvoidCobwebs;
    private Boolean navBreakCobwebs;
    private Boolean navAvoidPowderSnow;

    // Extended Baritone-like behavior overrides.
    private Boolean navAllowParkour;
    private Boolean navAllowPillar;
    private Boolean navAllowBreakThrough;
    private Boolean navAllowDescendMine;
    private Boolean navAllowSprint;
    private Boolean navMobAvoidance;
    private Integer navMobAvoidanceRadius;
    private Integer navMaxFallHeight;
    private Boolean navAvoidSoulSand;
    private Boolean navAllowOpenDoors;
    private Boolean navAllowOpenFenceGates;
    private Boolean navAllowSwimming;

    // Follow mode fields.
    private UUID navFollowTarget = null;
    private double navFollowRadius = 3.0D;
    private int navFollowRepathInterval = 20;
    private int navFollowRepathTicks = 0;

    // Chase mode fields.
    private UUID navChaseTarget = null;
    private boolean navChaseCrit = false;
    private int navChaseRepathTicks = 0;
    private double navChaseAttackRange = DEFAULT_CHASE_ATTACK_RANGE;
    private int navChaseAttackInterval = 0; // 0 = continuous (every tick)
    private int navChaseAttackCooldown = 0; // ticks remaining until next allowed hit
    private boolean navChaseInRange = false; // true when target is within attack range
    private boolean navChaseInJumpRange = false; // true when target is within jump attack range
    private int navChaseTargetEntityId = -1; // entity ID for direct attack (bypass ray trace)
    private double critJumpDistance = 2.8; // Distance to start jump outside of attack range

    // Mine mode fields.
    private List<Block> navMineTargets = null;
    private int navMineRadius = 32;
    private BlockPos navMineCurrentTarget = null;
    private int navMinedCount = 0;
    private int navMineMaxCount = -1; // -1 = unlimited

    // Patrol mode fields.
    private List<Vec3> navPatrolWaypoints = null;
    private boolean navPatrolLoop = true;
    private int navPatrolIndex = 0;
    private boolean navPatrolReverse = false;

    // Node / move types for advanced movement execution.
    private List<NavAStarPathfinder.MoveType> navMoveTypes = null;

    public NavController(EntityPlayerActionPack pack)
    {
        this.pack = pack;
        this.player = pack.getPlayer();
    }

    // ====== Auto-eating ======

    public boolean maybeAutoEat(Settings settings)
    {
        if (!(player instanceof EntityPlayerMPFake))
        {
            return false;
        }
        if (!navEnabled || !allowAutoEat(settings))
        {
            return false;
        }
        FoodData foodData = player.getFoodData();
        if (foodData == null || foodData.getFoodLevel() > autoEatBelow(settings))
        {
            return false;
        }
        if (player.isUsingItem())
        {
            return true;
        }
        int slot = findBestFoodSlot();
        if (slot < 0)
        {
            return false;
        }
        if (!switchToSlotWithSwap(slot))
        {
            return false;
        }
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.get(DataComponents.FOOD) == null)
        {
            return false;
        }
        InteractionResult result = player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND);
        if (result.consumesAction())
        {
            pack.setItemUseCooldown(3);
            return true;
        }
        return player.isUsingItem();
    }

    private int findBestFoodSlot()
    {
        Inventory inv = player.getInventory();
        int bestSlot = -1;
        double bestScore = -1.0D;
        for (int i = 0; i < inv.getContainerSize(); i++)
        {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            FoodProperties props = stack.get(DataComponents.FOOD);
            if (props == null) continue;
            double score = props.nutrition();
            if (score > bestScore)
            {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private boolean switchToSlotWithSwap(int slot)
    {
        Inventory inv = player.getInventory();
        if (slot < 0 || slot >= inv.getContainerSize()) return false;

        int selected = inv.getSelectedSlot();
        if (slot >= 0 && slot <= 8)
        {
            inv.setSelectedSlot(slot);
            player.connection.send(new ClientboundSetHeldSlotPacket(slot));
            return true;
        }

        // Swap from main inventory into hotbar selected slot.
        ItemStack selectedStack = inv.getItem(selected);
        ItemStack targetStack = inv.getItem(slot);
        inv.setItem(selected, targetStack);
        inv.setItem(slot, selectedStack);
        inv.setSelectedSlot(selected);
        player.connection.send(new ClientboundSetHeldSlotPacket(selected));
        return true;
    }

    // ====== Block breaking and placing ======

    private boolean selectBestToolFor(BlockState state, Settings settings)
    {
        if (!allowAutoTool(settings)) return false;
        Inventory inv = player.getInventory();
        int bestSlot = -1;
        float bestScore = 0.0F;
        for (int i = 0; i < inv.getContainerSize(); i++)
        {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            float speed = stack.getDestroySpeed(state);
            if (stack.isCorrectToolForDrops(state))
            {
                speed += 5.0F;
            }
            if (speed > bestScore)
            {
                bestScore = speed;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) return false;
        return switchToSlotWithSwap(bestSlot);
    }

    private boolean tryBreakBlock(BlockPos pos, Direction side, Settings settings)
    {
        if (pack.getBlockHitDelay() > 0)
        {
            pack.setBlockHitDelay(pack.getBlockHitDelay() - 1);
            return false;
        }

        BlockState state = player.level().getBlockState(pos);
        if (state.isAir())
        {
            pack.setCurrentBlock(null);
            return true;
        }

        selectBestToolFor(state, settings);

        if (player.gameMode.getGameModeForPlayer().isCreative())
        {
            player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
            pack.setBlockHitDelay(5);
            return true;
        }

        if (pack.getCurrentBlock() == null || !pack.getCurrentBlock().equals(pos))
        {
            if (pack.getCurrentBlock() != null)
            {
                player.gameMode.handleBlockBreakAction(pack.getCurrentBlock(), ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
            }
            player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
            boolean notAir = !state.isAir();
            if (notAir && pack.getCurBlockDamageMP() == 0)
            {
                state.attack(player.level(), pos, player);
            }
            if (notAir && state.getDestroyProgress(player, player.level(), pos) >= 1)
            {
                pack.setCurrentBlock(null);
                return true;
            }
            pack.setCurrentBlock(pos);
            pack.setCurBlockDamageMP(0);
        }
        else
        {
            pack.setCurBlockDamageMP(pack.getCurBlockDamageMP() + state.getDestroyProgress(player, player.level(), pos));
            if (pack.getCurBlockDamageMP() >= 1)
            {
                player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                pack.setCurrentBlock(null);
                pack.setBlockHitDelay(5);
                return true;
            }
            player.level().destroyBlockProgress(-1, pos, (int) (pack.getCurBlockDamageMP() * 10));
        }
        player.resetLastActionTime();
        //~ if >=26.3 'swing(InteractionHand.MAIN_HAND)' -> 'swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)'
        player.swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        return false;
    }

    private int findPlaceableBlockSlot()
    {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++)
        {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof BlockItem blockItem)) continue;
            BlockState state = blockItem.getBlock().defaultBlockState();
            if (state.isAir()) continue;
            if (state.getCollisionShape(player.level(), BlockPos.ZERO).isEmpty()) continue;
            return i;
        }
        return -1;
    }

    private boolean tryPlaceBridgeBlock(BlockPos targetFeet, Settings settings)
    {
        if (!allowPlaceBlocks(settings)) return false;
        BlockPos placePos = targetFeet.below();
        BlockState placeState = player.level().getBlockState(placePos);
        if (!placeState.isAir()) return false;

        BlockPos supportPos = placePos.below();
        BlockState supportState = player.level().getBlockState(supportPos);
        if (supportState.getCollisionShape(player.level(), supportPos).isEmpty()) return false;

        int slot = findPlaceableBlockSlot();
        if (slot < 0) return false;
        if (!switchToSlotWithSwap(slot)) return false;

        Vec3 hitVec = new Vec3(supportPos.getX() + 0.5D, supportPos.getY() + 1.0D, supportPos.getZ() + 0.5D);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, supportPos, false);
        InteractionResult result = player.gameMode.useItemOn(player, (ServerLevel) player.level(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction())
        {
            //~ if >=26.3 'swing(InteractionHand.MAIN_HAND)' -> 'swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)'
            player.swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            pack.setItemUseCooldown(3);
            return true;
        }
        return false;
    }

    private boolean tryBreakBlockingAhead(Settings settings)
    {
        BlockHitResult hit = rayTraceBlocks(4.5D);
        if (hit == null) return false;
        BlockPos pos = hit.getBlockPos();
        BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) return false;
        return tryBreakBlock(pos, hit.getDirection(), settings);
    }

    /** Same clip as carpet.script.utils.Tracer.rayTraceBlocks(entity, partialTicks, reach, false). */
    private BlockHitResult rayTraceBlocks(double reach)
    {
        Vec3 pos = player.getEyePosition(1);
        Vec3 rotation = player.getViewVector(1);
        Vec3 reachEnd = pos.add(rotation.x * reach, rotation.y * reach, rotation.z * reach);
        return player.level().clip(new ClipContext(pos, reachEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    private static boolean isCobweb(BlockState state)
    {
        return state.is(Blocks.COBWEB);
    }

    private static boolean isLava(BlockState state)
    {
        return state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.LAVA);
    }

    private static boolean isFire(BlockState state)
    {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
    }

    private static boolean isPowderSnow(BlockState state)
    {
        return state.is(Blocks.POWDER_SNOW);
    }

    private boolean isInWaterish()
    {
        if (player.isInWater())
        {
            return true;
        }
        BlockPos feet = player.blockPosition();
        return player.level().getFluidState(feet).is(FluidTags.WATER) || player.level().getFluidState(feet.above()).is(FluidTags.WATER);
    }

    private boolean isOnIce()
    {
        BlockPos below = player.blockPosition().below();
        BlockState state = player.level().getBlockState(below);
        return state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE) || state.is(Blocks.FROSTED_ICE);
    }

    // ====== Settings overrides ======

    private boolean navOpt(Boolean overrideValue, boolean ruleValue)
    {
        return overrideValue != null ? overrideValue : ruleValue;
    }

    private int navOpt(Integer overrideValue, int ruleValue)
    {
        return overrideValue != null ? overrideValue : ruleValue;
    }

    private boolean allowBreakBlocks(Settings settings) { return navOpt(navAllowBreakBlocks, settings.breakBlocks()); }

    private boolean allowPlaceBlocks(Settings settings) { return navOpt(navAllowPlaceBlocks, settings.placeBlocks()); }

    private boolean allowAutoTool(Settings settings) { return navOpt(navAutoTool, settings.autoTool()); }

    private boolean allowAutoEat(Settings settings) { return navOpt(navAutoEat, settings.autoEat()); }

    private int autoEatBelow(Settings settings) { return navOpt(navAutoEatBelow, settings.autoEatBelow()); }

    private boolean avoidLava(Settings settings) { return navOpt(navAvoidLava, settings.avoidLava()); }

    private boolean avoidFire(Settings settings) { return navOpt(navAvoidFire, settings.avoidFire()); }

    private boolean avoidCobwebs(Settings settings) { return navOpt(navAvoidCobwebs, settings.avoidCobwebs()); }

    private boolean allowBreakCobwebs(Settings settings) { return navOpt(navBreakCobwebs, settings.breakCobwebs()); }

    private boolean avoidPowderSnow(Settings settings) { return navOpt(navAvoidPowderSnow, settings.avoidPowderSnow()); }

    private boolean allowParkour(Settings settings) { return navOpt(navAllowParkour, settings.allowParkour()); }

    private boolean allowPillar(Settings settings) { return navOpt(navAllowPillar, settings.allowPillar()); }

    private boolean allowBreakThrough(Settings settings) { return navOpt(navAllowBreakThrough, settings.allowBreakThrough()); }

    private boolean allowDescendMine(Settings settings) { return navOpt(navAllowDescendMine, settings.allowDescendMine()); }

    private boolean allowSprint(Settings settings) { return navOpt(navAllowSprint, settings.allowSprint()); }

    private boolean mobAvoidance(Settings settings) { return navOpt(navMobAvoidance, settings.mobAvoidance()); }

    private boolean avoidSoulSand(Settings settings) { return navOpt(navAvoidSoulSand, settings.avoidSoulSand()); }

    private boolean allowOpenDoors(Settings settings) { return navOpt(navAllowOpenDoors, settings.allowOpenDoors()); }

    private boolean allowOpenFenceGates(Settings settings) { return navOpt(navAllowOpenFenceGates, settings.allowOpenFenceGates()); }

    private boolean allowSwimming(Settings settings) { return navOpt(navAllowSwimming, settings.allowSwimming()); }

    public void resetOptions()
    {
        navAllowBreakBlocks = null;
        navAllowPlaceBlocks = null;
        navAutoTool = null;
        navAutoEat = null;
        navAutoEatBelow = null;
        navAvoidLava = null;
        navAvoidFire = null;
        navAvoidCobwebs = null;
        navBreakCobwebs = null;
        navAvoidPowderSnow = null;
        navAllowParkour = null;
        navAllowPillar = null;
        navAllowBreakThrough = null;
        navAllowDescendMine = null;
        navAllowSprint = null;
        navMobAvoidance = null;
        navMobAvoidanceRadius = null;
        navMaxFallHeight = null;
        navAvoidSoulSand = null;
        navAllowOpenDoors = null;
        navAllowOpenFenceGates = null;
        navAllowSwimming = null;
    }

    public boolean setOption(String option, boolean value)
    {
        return switch (option)
        {
            case "breakBlocks" -> { navAllowBreakBlocks = value; yield true; }
            case "placeBlocks" -> { navAllowPlaceBlocks = value; yield true; }
            case "autoTool" -> { navAutoTool = value; yield true; }
            case "autoEat" -> { navAutoEat = value; yield true; }
            case "avoidLava" -> { navAvoidLava = value; yield true; }
            case "avoidFire" -> { navAvoidFire = value; yield true; }
            case "avoidCobwebs" -> { navAvoidCobwebs = value; yield true; }
            case "breakCobwebs" -> { navBreakCobwebs = value; yield true; }
            case "avoidPowderSnow" -> { navAvoidPowderSnow = value; yield true; }
            case "allowParkour" -> { navAllowParkour = value; yield true; }
            case "allowPillar" -> { navAllowPillar = value; yield true; }
            case "allowBreakThrough" -> { navAllowBreakThrough = value; yield true; }
            case "allowDescendMine" -> { navAllowDescendMine = value; yield true; }
            case "allowSprint" -> { navAllowSprint = value; yield true; }
            case "mobAvoidance" -> { navMobAvoidance = value; yield true; }
            case "avoidSoulSand" -> { navAvoidSoulSand = value; yield true; }
            case "allowOpenDoors" -> { navAllowOpenDoors = value; yield true; }
            case "allowOpenFenceGates" -> { navAllowOpenFenceGates = value; yield true; }
            case "allowSwimming" -> { navAllowSwimming = value; yield true; }
            default -> false;
        };
    }

    public boolean setOption(String option, int value)
    {
        return switch (option)
        {
            case "autoEatBelow" -> { navAutoEatBelow = Mth.clamp(value, 0, 20); yield true; }
            case "mobAvoidanceRadius" -> { navMobAvoidanceRadius = Mth.clamp(value, 1, 32); yield true; }
            case "maxFallHeight" -> { navMaxFallHeight = Mth.clamp(value, 1, 64); yield true; }
            default -> false;
        };
    }

    // ====== Entry points ======

    public void stop()
    {
        navEnabled = false;
        navMode = BotNavMode.AUTO;
        navTargetPos = null;
        navArrivalRadius = 1.0D;

        navNodes = null;
        navWaypoints = null;
        navWaypointIndex = 0;
        navRepathCooldownTicks = 0;
        navNeedsRepath = false;
        navNoProgressTicks = 0;
        navLastDistanceToNext = Double.POSITIVE_INFINITY;
        navJumpCooldownTicks = 0;
        navMoveTypes = null;

        // Follow mode
        navFollowTarget = null;
        navFollowRadius = 3.0D;
        navFollowRepathTicks = 0;

        // Chase mode
        navChaseTarget = null;
        navChaseCrit = false;
        navChaseRepathTicks = 0;
        navChaseAttackRange = DEFAULT_CHASE_ATTACK_RANGE;
        navChaseAttackInterval = 0;
        navChaseAttackCooldown = 0;
        navChaseInRange = false;
        navChaseInJumpRange = false;
        navChaseTargetEntityId = -1;

        // Mine mode
        navMineTargets = null;
        navMineCurrentTarget = null;
        navMinedCount = 0;
        navMineMaxCount = -1;

        // Patrol mode
        navPatrolWaypoints = null;
        navPatrolIndex = 0;
        navPatrolLoop = true;
        navPatrolReverse = false;

        if (navWaterJumping)
        {
            player.setJumping(false);
            navWaterJumping = false;
        }

        // If we were navigating via elytra, this also stops that controller.
        pack.setGlideEnabled(false);
    }

    public void gotoTarget(Vec3 targetPos, BotNavMode mode, double arrivalRadius)
    {
        navEnabled = true;
        navMode = mode == null ? BotNavMode.AUTO : mode;
        navTargetPos = targetPos;
        navArrivalRadius = Math.max(0.0D, arrivalRadius);
        navAirArrival = AirArrival.LAND;
        navAirRequestedTargetPos = null;
        navNodes = null;
        navWaypoints = null;
        navWaypointIndex = 0;
        navNeedsRepath = true;
        navRepathCooldownTicks = 0;
        navNoProgressTicks = 0;
        navLastDistanceToNext = Double.POSITIVE_INFINITY;
    }

    public void gotoAir(Vec3 targetPos, double arrivalRadius, boolean landOnFloor)
    {
        navEnabled = true;
        navMode = BotNavMode.AIR;
        navArrivalRadius = Math.max(0.0D, arrivalRadius);
        navAirRequestedTargetPos = targetPos;
        navAirArrival = landOnFloor ? AirArrival.LAND : AirArrival.DROP;

        if (landOnFloor)
        {
            navTargetPos = resolveLandingTarget(targetPos);
        }
        else
        {
            navTargetPos = targetPos;
        }

        navNodes = null;
        navWaypoints = null;
        navWaypointIndex = 0;
        navNeedsRepath = true;
        navRepathCooldownTicks = 0;
        navNoProgressTicks = 0;
        navLastDistanceToNext = Double.POSITIVE_INFINITY;
    }

    public void follow(UUID targetUUID, double radius)
    {
        stop();
        navEnabled = true;
        navMode = BotNavMode.FOLLOW;
        navFollowTarget = targetUUID;
        navFollowRadius = Math.max(1.0D, radius);
        navFollowRepathTicks = 0;
        navArrivalRadius = radius;
    }

    public void chase(UUID targetUUID, boolean crit, double attackRange, int attackInterval)
    {
        stop();
        pack.stopAll(); // Clear any existing actions
        navEnabled = true;
        navMode = BotNavMode.CHASE;
        navChaseTarget = targetUUID;
        navChaseCrit = crit;
        navChaseRepathTicks = 0;
        navChaseAttackRange = Math.max(0.5D, Math.min(attackRange, 3.0D));
        navChaseAttackInterval = Math.max(0, attackInterval);
        navChaseAttackCooldown = 0;
        navChaseInRange = false;
        navChaseInJumpRange = false;
        navChaseTargetEntityId = -1;
        navArrivalRadius = navChaseAttackRange;
        // Start the attack action
        pack.setAttackCritical(crit);
        pack.start(ActionType.ATTACK, Action.continuous());
    }

    public void mine(List<Block> targets, int radius, int maxCount)
    {
        stop();
        navEnabled = true;
        navMode = BotNavMode.MINE;
        navMineTargets = targets;
        navMineRadius = Math.max(1, radius);
        navMineMaxCount = maxCount;
        navMinedCount = 0;
        navMineCurrentTarget = null;
        navNeedsRepath = true;
    }

    public void come(Vec3 senderPos, double arrivalRadius)
    {
        gotoTarget(senderPos, BotNavMode.AUTO, arrivalRadius);
    }

    public void patrol(List<Vec3> waypoints, boolean loop)
    {
        stop();
        navEnabled = true;
        navMode = BotNavMode.PATROL;
        navPatrolWaypoints = new ArrayList<>(waypoints);
        navPatrolLoop = loop;
        navPatrolIndex = 0;
        navPatrolReverse = false;
        navArrivalRadius = 1.5D;
        // Set first target.
        if (!navPatrolWaypoints.isEmpty())
        {
            navTargetPos = navPatrolWaypoints.get(0);
            navNeedsRepath = true;
        }
    }

    // Getters for status display.
    public boolean isEnabled() { return navEnabled; }

    public BotNavMode getMode() { return navMode; }

    public Vec3 getTargetPos() { return navTargetPos; }

    public double getArrivalRadius() { return navArrivalRadius; }

    public UUID getFollowTarget() { return navFollowTarget; }

    public UUID getChaseTarget() { return navChaseTarget; }

    public boolean isChaseCrit() { return navChaseCrit; }

    public double getChaseAttackRange() { return navChaseAttackRange; }

    public int getChaseAttackInterval() { return navChaseAttackInterval; }

    public List<Block> getMineTargets() { return navMineTargets; }

    public int getMinedCount() { return navMinedCount; }

    public int getMineMaxCount() { return navMineMaxCount; }

    public List<Vec3> getPatrolWaypoints() { return navPatrolWaypoints; }

    public int getPatrolIndex() { return navPatrolIndex; }

    public boolean isPatrolLoop() { return navPatrolLoop; }

    // Chase state read by the action pack's ATTACK action.
    public boolean isChasing() { return navMode == BotNavMode.CHASE; }

    public boolean isChaseInRange() { return navChaseInRange; }

    public boolean isChaseInJumpRange() { return navChaseInJumpRange; }

    public int getChaseTargetEntityId() { return navChaseTargetEntityId; }

    public int getChaseAttackCooldown() { return navChaseAttackCooldown; }

    public void setChaseAttackCooldown(int cooldown) { navChaseAttackCooldown = cooldown; }

    // ====== Ticking ======

    public void tick(Settings settings)
    {
        if (!navEnabled)
        {
            if (navWaterJumping)
            {
                player.setJumping(false);
                navWaterJumping = false;
            }
            return;
        }

        if (!settings.enabled())
        {
            stop();
            return;
        }

        if (!(player instanceof EntityPlayerMPFake))
        {
            stop();
            return;
        }

        if (player.isSpectator())
        {
            return;
        }

        if (navRepathCooldownTicks > 0)
        {
            navRepathCooldownTicks--;
        }
        if (navJumpCooldownTicks > 0)
        {
            navJumpCooldownTicks--;
        }

        // Dispatch to mode-specific tick handlers.
        if (navMode == BotNavMode.FOLLOW)
        {
            tickFollow(settings);
            return;
        }
        if (navMode == BotNavMode.CHASE)
        {
            tickChase(settings);
            return;
        }
        if (navMode == BotNavMode.MINE)
        {
            tickMine(settings);
            return;
        }
        if (navMode == BotNavMode.PATROL)
        {
            tickPatrol(settings);
            return;
        }

        if (navTargetPos == null)
        {
            stop();
            return;
        }

        BlockPos feetPos = player.blockPosition();
        if (allowBreakCobwebs(settings))
        {
            BlockState feetState = player.level().getBlockState(feetPos);
            BlockState headState = player.level().getBlockState(feetPos.above());
            if (isCobweb(feetState) || isCobweb(headState))
            {
                tryBreakBlock(isCobweb(headState) ? feetPos.above() : feetPos, Direction.UP, settings);
                navNeedsRepath = true;
                return;
            }
        }

        BotNavMode effectiveMode = navMode;
        if (effectiveMode == BotNavMode.AUTO)
        {
            if (isInWaterish())
            {
                effectiveMode = BotNavMode.WATER;
            }
            else
            {
                ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
                boolean canElytra = chest.is(Items.ELYTRA) && !chest.nextDamageWillBreak();
                effectiveMode = (canElytra && settings.elytraGlide()) ? BotNavMode.AIR : BotNavMode.LAND;
            }
        }

        // Completion rules: air+LAND should keep running until we actually touch down.
        if (effectiveMode != BotNavMode.AIR)
        {
            if (player.position().distanceTo(navTargetPos) <= navArrivalRadius)
            {
                stop();
                pack.stopMovement();
                return;
            }
        }
        else
        {
            if (navAirArrival == AirArrival.DROP)
            {
                if (player.position().distanceTo(navTargetPos) <= navArrivalRadius)
                {
                    // Stop gliding immediately; gravity takes over.
                    stop();
                    pack.stopMovement();
                    return;
                }
            }
            else
            {
                // LAND: stop navigation when we have landed and glide controller has ended.
                if (player.onGround() && !player.isFallFlying() && !pack.isGlideEnabled())
                {
                    stop();
                    pack.stopMovement();
                    return;
                }
            }
        }

        if (effectiveMode == BotNavMode.AIR)
        {
            if (!settings.elytraGlide())
            {
                stop();
                return;
            }

            if (navNeedsRepath && navRepathCooldownTicks <= 0)
            {
                navNeedsRepath = false;
                navRepathCooldownTicks = 20;

                BlockPos start = player.blockPosition();
                BlockPos goal = BlockPos.containing(navTargetPos);
                ElytraAStarPathfinder.Settings elytraSettings = ElytraAStarPathfinder.Settings.defaults();
                List<BlockPos> raw = ELYTRA_PATHFINDER.findPath((ServerLevel) player.level(), start, goal, elytraSettings);
                if (raw == null)
                {
                    stop();
                    return;
                }
                List<BlockPos> compressed = ElytraAStarPathfinder.compressWaypoints(raw, elytraSettings.waypointStride());
                List<Vec3> waypoints = new ArrayList<>(compressed.size());
                for (BlockPos p : compressed)
                {
                    waypoints.add(new Vec3(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D));
                }

                navWaypoints = waypoints;
                navWaypointIndex = 0;
                pack.setGlideEnabled(true);
                if (navAirArrival == AirArrival.DROP)
                {
                    // No landing behavior: stop gliding after last waypoint.
                    pack.setGlideArrivalAction(GlideArrivalAction.STOP);
                    pack.setGlideGotoWaypoints(waypoints, null, navArrivalRadius);
                }
                else
                {
                    pack.setGlideArrivalAction(GlideArrivalAction.LAND);
                    pack.setGlideGotoWaypoints(waypoints, navTargetPos, navArrivalRadius);
                }
            }
            return;
        }

        if (navNeedsRepath && navRepathCooldownTicks <= 0)
        {
            navNeedsRepath = false;
            navRepathCooldownTicks = 20;

            BlockPos start = player.blockPosition();
            BlockPos goal = BlockPos.containing(navTargetPos);
            NavAStarPathfinder.Settings pathSettings = buildPathSettings(settings);
            NavAStarPathfinder.Traversal traversal = (effectiveMode == BotNavMode.WATER) ? NavAStarPathfinder.Traversal.WATER : NavAStarPathfinder.Traversal.AMPHIBIOUS;
            NavAStarPathfinder.PathResult result = ASTAR.findPath((ServerLevel) player.level(), start, goal, traversal, pathSettings);
            if (result == null || result.positions().isEmpty())
            {
                stop();
                return;
            }

            List<BlockPos> raw = result.positions();
            // Keep node-to-node fidelity so the follower can detect jump edges.
            navNodes = raw;
            navMoveTypes = result.moveTypes();

            List<Vec3> waypoints = new ArrayList<>(raw.size());
            for (BlockPos p : raw)
            {
                waypoints.add(new Vec3(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D));
            }
            navWaypoints = waypoints;
            navWaypointIndex = 0;
        }

        if (pack.isGlideEnabled())
        {
            pack.setGlideEnabled(false);
        }

        if (navWaypoints == null || navWaypointIndex >= navWaypoints.size())
        {
            navNeedsRepath = true;
            return;
        }

        Vec3 next = navWaypoints.get(navWaypointIndex);
        double dist = player.position().distanceTo(next);

        BlockPos nextFeet = BlockPos.containing(next);
        BlockState nextState = player.level().getBlockState(nextFeet);
        BlockState nextBelow = player.level().getBlockState(nextFeet.below());
        boolean cobwebAhead = isCobweb(nextState) || isCobweb(nextBelow);
        if (avoidCobwebs(settings) && !allowBreakCobwebs(settings) && cobwebAhead)
        {
            navNeedsRepath = true;
            return;
        }
        if (avoidLava(settings) && (isLava(nextState) || isLava(nextBelow)))
        {
            navNeedsRepath = true;
            return;
        }
        if (avoidFire(settings) && (isFire(nextState) || isFire(nextBelow)))
        {
            navNeedsRepath = true;
            return;
        }
        if (avoidPowderSnow(settings) && (isPowderSnow(nextState) || isPowderSnow(nextBelow)))
        {
            navNeedsRepath = true;
            return;
        }

        if (allowBreakCobwebs(settings) && cobwebAhead && dist <= 2.0D)
        {
            tryBreakBlock(isCobweb(nextState) ? nextFeet : nextFeet.below(), Direction.UP, settings);
            navNeedsRepath = true;
            return;
        }

        if (allowPlaceBlocks(settings) && dist <= 1.6D)
        {
            if (tryPlaceBridgeBlock(nextFeet, settings))
            {
                navNeedsRepath = true;
                return;
            }
        }

        // Adaptive waypoint arrival distance: wider on ice to account for sliding.
        double arrivalDist = isOnIce() ? 1.8D : 0.85D;
        if (dist <= arrivalDist)
        {
            navWaypointIndex++;
            navNoProgressTicks = 0;
            navLastDistanceToNext = Double.POSITIVE_INFINITY;
            if (navWaterJumping)
            {
                player.setJumping(false);
                navWaterJumping = false;
            }
            return;
        }

        if (dist + 0.01D >= navLastDistanceToNext)
        {
            navNoProgressTicks++;
        }
        else
        {
            navNoProgressTicks = 0;
        }
        navLastDistanceToNext = dist;

        if (navNoProgressTicks > 60)
        {
            navNoProgressTicks = 0;
            if (allowBreakBlocks(settings) && tryBreakBlockingAhead(settings))
            {
                navNeedsRepath = true;
                return;
            }
            navNeedsRepath = true;
            return;
        }

        Vec2 rot = rotationsTowards(player.getEyePosition(1.0F), next);
        pack.look(stepYaw(player.getYRot(), rot.x, 40.0F), player.getXRot());

        // Determine the MoveType for the current waypoint.
        NavAStarPathfinder.MoveType currentMoveType = NavAStarPathfinder.MoveType.WALK;
        if (navMoveTypes != null && navWaypointIndex < navMoveTypes.size())
        {
            currentMoveType = navMoveTypes.get(navWaypointIndex);
        }

        // Handle door/fence gate opening.
        BlockState nextBlockState = player.level().getBlockState(nextFeet);
        if (nextBlockState.getBlock() instanceof DoorBlock door && allowOpenDoors(settings))
        {
            if (!door.isOpen(nextBlockState))
            {
                door.setOpen(player, player.level(), nextBlockState, nextFeet, true);
            }
        }
        if (nextBlockState.getBlock() instanceof FenceGateBlock && allowOpenFenceGates(settings))
        {
            if (!nextBlockState.getValue(FenceGateBlock.OPEN))
            {
                player.level().setBlock(nextFeet, nextBlockState.setValue(FenceGateBlock.OPEN, true), 2);
            }
        }

        // Handle break-through: mine blocks ahead.
        if (currentMoveType == NavAStarPathfinder.MoveType.BREAK_THROUGH && allowBreakBlocks(settings) && dist <= 2.5D)
        {
            BlockState feetAhead = player.level().getBlockState(nextFeet);
            BlockState headAhead = player.level().getBlockState(nextFeet.above());
            if (!feetAhead.getCollisionShape(player.level(), nextFeet).isEmpty())
            {
                tryBreakBlock(nextFeet, Direction.UP, settings);
                return;
            }
            if (!headAhead.getCollisionShape(player.level(), nextFeet.above()).isEmpty())
            {
                tryBreakBlock(nextFeet.above(), Direction.UP, settings);
                return;
            }
        }

        // Handle descend-mine: mine block below.
        if (currentMoveType == NavAStarPathfinder.MoveType.DESCEND_MINE && allowBreakBlocks(settings) && dist <= 1.5D)
        {
            BlockPos belowTarget = new BlockPos(nextFeet.getX(), nextFeet.getY(), nextFeet.getZ());
            BlockState belowState = player.level().getBlockState(belowTarget);
            if (!belowState.getCollisionShape(player.level(), belowTarget).isEmpty())
            {
                tryBreakBlock(belowTarget, Direction.UP, settings);
                return;
            }
        }

        // Handle pillar: place block at feet to go up.
        if (currentMoveType == NavAStarPathfinder.MoveType.PILLAR && allowPlaceBlocks(settings) && dist <= 1.5D)
        {
            BlockPos placeFeet = player.blockPosition();
            if (tryPlaceBridgeBlock(placeFeet, settings))
            {
                // Jump onto the placed block.
                if (player.onGround() && navJumpCooldownTicks <= 0)
                {
                    navJumpCooldownTicks = 8;
                    pack.start(ActionType.JUMP, Action.once());
                }
                return;
            }
        }

        // Sprint based on settings and move type.
        // Disable sprint on ice to prevent overshooting waypoints.
        boolean onIce = isOnIce();
        boolean shouldSprint = allowSprint(settings) && !onIce && (currentMoveType == NavAStarPathfinder.MoveType.WALK
                || currentMoveType == NavAStarPathfinder.MoveType.PARKOUR);

        // Slow down on ice when approaching waypoint to reduce overshoot.
        float forwardSpeed = 1.0F;
        if (onIce && dist <= 3.0D)
        {
            forwardSpeed = 0.4F;
        }

        pack.setSneaking(false);
        pack.setSprinting(shouldSprint);
        pack.setForward(forwardSpeed);
        pack.setStrafing(0.0F);

        boolean wantUp = next.y > player.getY() + 0.2D;
        boolean wantDown = next.y < player.getY() - 0.4D;
        if (effectiveMode == BotNavMode.WATER)
        {
            boolean inWater = isInWaterish();
            if (inWater)
            {
                if (allowSwimming(settings))
                {
                    // Full swimming mode: 3D navigation in water.
                    // Look toward the waypoint in 3D (including pitch).
                    Vec2 swimRot = rotationsTowards(player.getEyePosition(1.0F), next);
                    pack.look(stepYaw(player.getYRot(), swimRot.x, 40.0F), swimRot.y);

                    if (wantUp)
                    {
                        player.setJumping(true);
                        navWaterJumping = true;
                        pack.setSneaking(false);
                    }
                    else if (wantDown)
                    {
                        // Descend: sneak to sink in water.
                        player.setJumping(false);
                        navWaterJumping = false;
                        pack.setSneaking(true);
                    }
                    else
                    {
                        // Horizontal swimming.
                        player.setJumping(false);
                        navWaterJumping = false;
                        pack.setSneaking(false);
                    }
                    // Sprint-swim for faster underwater movement.
                    if (allowSprint(settings))
                    {
                        pack.setSprinting(true);
                    }
                }
                else
                {
                    // Floating mode (default): stay at surface, navigate horizontally.
                    // Always jump to keep at the water surface.
                    player.setJumping(true);
                    navWaterJumping = true;
                    pack.setSneaking(false);
                }
            }
            else if (navWaterJumping)
            {
                player.setJumping(false);
                navWaterJumping = false;
            }
        }
        else
        {
            boolean needsPlannedJump = false;
            if (navNodes != null && navWaypointIndex < navNodes.size())
            {
                BlockPos cur = player.blockPosition();
                BlockPos planned = navNodes.get(navWaypointIndex);
                int dx = Math.abs(planned.getX() - cur.getX());
                int dz = Math.abs(planned.getZ() - cur.getZ());
                // A 2-block cardinal move or parkour move is a planned gap-jump.
                needsPlannedJump = (dx + dz) >= 2 && (dx == 0 || dz == 0);
            }

            // Parkour: always sprint-jump.
            if (currentMoveType == NavAStarPathfinder.MoveType.PARKOUR)
            {
                needsPlannedJump = true;
                pack.setSprinting(true);
            }

            boolean shouldJump = wantUp || (needsPlannedJump && dist <= 1.35D);
            if (shouldJump && player.onGround() && navJumpCooldownTicks <= 0)
            {
                // Check head clearance: don't jump if there's a solid block above the player's head
                BlockPos headAbove = player.blockPosition().above(2);
                BlockState headAboveState = player.level().getBlockState(headAbove);
                if (!headAboveState.getCollisionShape(player.level(), headAbove).isEmpty())
                {
                    // Can't jump - ceiling too low; try to path around instead
                    navNeedsRepath = true;
                }
                else
                {
                    navJumpCooldownTicks = 8;
                    pack.start(ActionType.JUMP, Action.once());
                }
            }
        }
    }

    // --- Follow mode: re-path periodically to stay near a target player ---
    private void tickFollow(Settings settings)
    {
        if (navFollowTarget == null)
        {
            stop();
            return;
        }

        ServerLevel level = (ServerLevel) player.level();
        Entity target = level.getEntity(navFollowTarget);
        if (target == null)
        {
            // Try finding by UUID among players.
            target = level.getServer().getPlayerList().getPlayer(navFollowTarget);
        }
        if (target == null)
        {
            stop();
            return;
        }

        double distToTarget = player.position().distanceTo(target.position());

        // Already close enough.
        if (distToTarget <= navFollowRadius)
        {
            pack.stopMovement();
            navWaypoints = null;
            navNodes = null;
            navMoveTypes = null;
            // Still keep following – just wait.
            navFollowRepathTicks = Math.min(navFollowRepathTicks, 10);
        }

        navFollowRepathTicks--;
        if (navFollowRepathTicks <= 0 && distToTarget > navFollowRadius)
        {
            navFollowRepathTicks = navFollowRepathInterval;
            navTargetPos = target.position();

            BlockPos start = player.blockPosition();
            BlockPos goal = BlockPos.containing(navTargetPos);
            NavAStarPathfinder.Settings pathSettings = buildPathSettings(settings);
            NavAStarPathfinder.Traversal traversal = isInWaterish() ? NavAStarPathfinder.Traversal.WATER : NavAStarPathfinder.Traversal.AMPHIBIOUS;
            NavAStarPathfinder.PathResult result = ASTAR.findPath(level, start, goal, traversal, pathSettings);
            if (result == null || result.positions().isEmpty())
            {
                navWaypoints = null;
                navNodes = null;
                navMoveTypes = null;
                return;
            }
            navNodes = result.positions();
            navMoveTypes = result.moveTypes();
            List<Vec3> waypoints = new ArrayList<>(navNodes.size());
            for (BlockPos p : navNodes)
            {
                waypoints.add(new Vec3(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D));
            }
            navWaypoints = waypoints;
            navWaypointIndex = 0;
            navNoProgressTicks = 0;
            navLastDistanceToNext = Double.POSITIVE_INFINITY;
        }

        // Execute movement along waypoints (shared logic).
        tickWaypointFollowing(BotNavMode.LAND, settings);
    }

    // --- Chase mode: follow a player and attack them ---
    private void tickChase(Settings settings)
    {
        if (navChaseTarget == null)
        {
            pack.stopAll();
            stop();
            return;
        }

        ServerLevel level = (ServerLevel) player.level();
        Entity target = level.getEntity(navChaseTarget);
        if (target == null)
        {
            target = level.getServer().getPlayerList().getPlayer(navChaseTarget);
        }
        if (target == null || !target.isAlive()
            || (target instanceof ServerPlayer sp && sp.isDeadOrDying()))
        {
            // Target died, disconnected, or is otherwise gone — stop everything.
            pack.stopAll();
            stop();
            return;
        }

        double distToTarget = player.position().distanceTo(target.position());

        // Always look at the target when chasing.
        pack.lookAt(target.position().add(0, target.getBbHeight() * 0.5, 0));

        // Update in-range state and entity ID for the ATTACK action.
        if (distToTarget <= navChaseAttackRange)
        {
            navChaseInRange = true;
            navChaseTargetEntityId = target.getId();

            // Stop moving, face target, let the attack action handle the rest.
            pack.stopMovement();
            navWaypoints = null;
            navNodes = null;
            navMoveTypes = null;
            navChaseRepathTicks = Math.min(navChaseRepathTicks, 5);
        }
        else
        {
            navChaseInJumpRange = (distToTarget <= navChaseAttackRange + critJumpDistance);
            navChaseInRange = false;
            navChaseTargetEntityId = -1;
        }

        // Re-path to target if needed.
        navChaseRepathTicks--;
        if (navChaseRepathTicks <= 0 && distToTarget > navChaseAttackRange)
        {
            navChaseRepathTicks = CHASE_REPATH_INTERVAL;
            navTargetPos = target.position();

            BlockPos start = player.blockPosition();
            BlockPos goal = BlockPos.containing(navTargetPos);
            NavAStarPathfinder.Settings pathSettings = buildPathSettings(settings);
            NavAStarPathfinder.Traversal traversal = isInWaterish() ? NavAStarPathfinder.Traversal.WATER : NavAStarPathfinder.Traversal.AMPHIBIOUS;
            NavAStarPathfinder.PathResult result = ASTAR.findPath(level, start, goal, traversal, pathSettings);
            if (result == null || result.positions().isEmpty())
            {
                navWaypoints = null;
                navNodes = null;
                navMoveTypes = null;
                return;
            }
            navNodes = result.positions();
            navMoveTypes = result.moveTypes();
            List<Vec3> waypoints = new ArrayList<>(navNodes.size());
            for (BlockPos p : navNodes)
            {
                waypoints.add(new Vec3(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D));
            }
            navWaypoints = waypoints;
            navWaypointIndex = 0;
            navNoProgressTicks = 0;
            navLastDistanceToNext = Double.POSITIVE_INFINITY;
        }

        // Execute movement along waypoints (shared logic).
        if (distToTarget > navChaseAttackRange)
        {
            tickWaypointFollowing(BotNavMode.LAND, settings);
        }
    }

    // --- Mine mode: find target block, navigate to it, mine it, repeat ---
    private void tickMine(Settings settings)
    {
        if (navMineTargets == null || navMineTargets.isEmpty())
        {
            stop();
            return;
        }
        if (navMineMaxCount > 0 && navMinedCount >= navMineMaxCount)
        {
            stop();
            return;
        }

        ServerLevel level = (ServerLevel) player.level();

        // If we don't have a target, find one.
        if (navMineCurrentTarget == null)
        {
            navMineCurrentTarget = NavAStarPathfinder.findNearestBlock(level, player.blockPosition(), navMineTargets, navMineRadius);
            if (navMineCurrentTarget == null)
            {
                stop();
                return;
            }
            navTargetPos = new Vec3(navMineCurrentTarget.getX() + 0.5D, navMineCurrentTarget.getY(), navMineCurrentTarget.getZ() + 0.5D);
            navNeedsRepath = true;
            navRepathCooldownTicks = 0;
        }

        double distToTarget = player.position().distanceTo(navTargetPos);

        // Close enough to mine.
        if (distToTarget <= 4.0D)
        {
            BlockState targetState = level.getBlockState(navMineCurrentTarget);
            boolean isTargetBlock = false;
            for (Block b : navMineTargets)
            {
                if (targetState.is(b)) { isTargetBlock = true; break; }
            }
            if (!isTargetBlock)
            {
                // Block was already mined or changed; find new target.
                navMinedCount++;
                navMineCurrentTarget = null;
                navWaypoints = null;
                navNodes = null;
                navMoveTypes = null;
                return;
            }
            tryBreakBlock(navMineCurrentTarget, Direction.UP, settings);
            pack.stopMovement();
            return;
        }

        // Path to the target block.
        if (navNeedsRepath && navRepathCooldownTicks <= 0)
        {
            navNeedsRepath = false;
            navRepathCooldownTicks = 20;

            BlockPos start = player.blockPosition();
            BlockPos goal = navMineCurrentTarget;
            NavAStarPathfinder.Settings pathSettings = buildPathSettings(settings);
            NavAStarPathfinder.PathResult result = ASTAR.findPath(level, start, goal, NavAStarPathfinder.Traversal.AMPHIBIOUS, pathSettings);
            if (result == null || result.positions().isEmpty())
            {
                // Can't reach; try another target.
                navMineCurrentTarget = null;
                navNeedsRepath = true;
                return;
            }
            navNodes = result.positions();
            navMoveTypes = result.moveTypes();
            List<Vec3> waypoints = new ArrayList<>(navNodes.size());
            for (BlockPos p : navNodes)
            {
                waypoints.add(new Vec3(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D));
            }
            navWaypoints = waypoints;
            navWaypointIndex = 0;
            navNoProgressTicks = 0;
            navLastDistanceToNext = Double.POSITIVE_INFINITY;
        }

        tickWaypointFollowing(BotNavMode.LAND, settings);
    }

    // --- Patrol mode: walk between waypoints ---
    private void tickPatrol(Settings settings)
    {
        if (navPatrolWaypoints == null || navPatrolWaypoints.isEmpty())
        {
            stop();
            return;
        }

        // Check if we've arrived at current patrol waypoint.
        if (navTargetPos != null && player.position().distanceTo(navTargetPos) <= navArrivalRadius)
        {
            // Advance to next patrol waypoint.
            if (navPatrolLoop)
            {
                navPatrolIndex = (navPatrolIndex + 1) % navPatrolWaypoints.size();
            }
            else if (!navPatrolReverse)
            {
                navPatrolIndex++;
                if (navPatrolIndex >= navPatrolWaypoints.size())
                {
                    stop();
                    pack.stopMovement();
                    return;
                }
            }
            else
            {
                navPatrolIndex--;
                if (navPatrolIndex < 0)
                {
                    stop();
                    pack.stopMovement();
                    return;
                }
            }
            navTargetPos = navPatrolWaypoints.get(navPatrolIndex);
            navNeedsRepath = true;
            navRepathCooldownTicks = 0;
        }

        if (navTargetPos == null)
        {
            navTargetPos = navPatrolWaypoints.get(navPatrolIndex);
            navNeedsRepath = true;
        }

        // Path to current patrol target.
        if (navNeedsRepath && navRepathCooldownTicks <= 0)
        {
            navNeedsRepath = false;
            navRepathCooldownTicks = 20;

            BlockPos start = player.blockPosition();
            BlockPos goal = BlockPos.containing(navTargetPos);
            NavAStarPathfinder.Settings pathSettings = buildPathSettings(settings);
            NavAStarPathfinder.Traversal traversal = isInWaterish() ? NavAStarPathfinder.Traversal.WATER : NavAStarPathfinder.Traversal.AMPHIBIOUS;
            NavAStarPathfinder.PathResult result = ASTAR.findPath((ServerLevel) player.level(), start, goal, traversal, pathSettings);
            if (result == null || result.positions().isEmpty())
            {
                // Skip this waypoint.
                if (navPatrolLoop)
                {
                    navPatrolIndex = (navPatrolIndex + 1) % navPatrolWaypoints.size();
                    navTargetPos = navPatrolWaypoints.get(navPatrolIndex);
                    navNeedsRepath = true;
                }
                else
                {
                    stop();
                }
                return;
            }
            navNodes = result.positions();
            navMoveTypes = result.moveTypes();
            List<Vec3> waypoints = new ArrayList<>(navNodes.size());
            for (BlockPos p : navNodes)
            {
                waypoints.add(new Vec3(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D));
            }
            navWaypoints = waypoints;
            navWaypointIndex = 0;
            navNoProgressTicks = 0;
            navLastDistanceToNext = Double.POSITIVE_INFINITY;
        }

        tickWaypointFollowing(BotNavMode.LAND, settings);
    }

    /**
     * Shared waypoint-following movement logic for follow/mine/patrol modes.
     * Handles walking, sprinting, jumping, parkour, door-opening, etc.
     */
    private void tickWaypointFollowing(BotNavMode effectiveMode, Settings settings)
    {
        if (navWaypoints == null || navWaypointIndex >= navWaypoints.size())
        {
            return;
        }

        Vec3 next = navWaypoints.get(navWaypointIndex);
        double dist = player.position().distanceTo(next);

        if (dist <= 0.85D)
        {
            navWaypointIndex++;
            navNoProgressTicks = 0;
            navLastDistanceToNext = Double.POSITIVE_INFINITY;
            if (navWaterJumping)
            {
                player.setJumping(false);
                navWaterJumping = false;
            }
            return;
        }

        if (dist + 0.01D >= navLastDistanceToNext)
        {
            navNoProgressTicks++;
        }
        else
        {
            navNoProgressTicks = 0;
        }
        navLastDistanceToNext = dist;

        if (navNoProgressTicks > 60)
        {
            navNoProgressTicks = 0;
            navNeedsRepath = true;
            return;
        }

        Vec2 rot2 = rotationsTowards(player.getEyePosition(1.0F), next);
        pack.look(stepYaw(player.getYRot(), rot2.x, 40.0F), player.getXRot());

        NavAStarPathfinder.MoveType moveType = NavAStarPathfinder.MoveType.WALK;
        if (navMoveTypes != null && navWaypointIndex < navMoveTypes.size())
        {
            moveType = navMoveTypes.get(navWaypointIndex);
        }

        boolean shouldSprint = allowSprint(settings) && (moveType == NavAStarPathfinder.MoveType.WALK
                || moveType == NavAStarPathfinder.MoveType.PARKOUR);
        pack.setSneaking(false);
        pack.setSprinting(shouldSprint);
        pack.setForward(1.0F);
        pack.setStrafing(0.0F);

        boolean wantUp = next.y > player.getY() + 0.2D;
        boolean needsPlannedJump = false;
        if (navNodes != null && navWaypointIndex < navNodes.size())
        {
            BlockPos cur = player.blockPosition();
            BlockPos planned = navNodes.get(navWaypointIndex);
            int ddx = Math.abs(planned.getX() - cur.getX());
            int ddz = Math.abs(planned.getZ() - cur.getZ());
            needsPlannedJump = (ddx + ddz) >= 2 && (ddx == 0 || ddz == 0);
        }
        if (moveType == NavAStarPathfinder.MoveType.PARKOUR)
        {
            needsPlannedJump = true;
            pack.setSprinting(true);
        }

        boolean shouldJump = wantUp || (needsPlannedJump && dist <= 1.35D);
        if (shouldJump && player.onGround() && navJumpCooldownTicks <= 0)
        {
            BlockPos headAbove = player.blockPosition().above(2);
            BlockState headAboveState = player.level().getBlockState(headAbove);
            if (headAboveState.getCollisionShape(player.level(), headAbove).isEmpty())
            {
                navJumpCooldownTicks = 8;
                pack.start(ActionType.JUMP, Action.once());
            }
        }
    }

    /**
     * Build NavAStarPathfinder.Settings from the settings snapshot + per-bot overrides.
     */
    private NavAStarPathfinder.Settings buildPathSettings(Settings settings)
    {
        NavAStarPathfinder.Settings base = NavAStarPathfinder.Settings.defaults();
        return new NavAStarPathfinder.Settings(
                base.maxExpanded(),
                base.maxQueued(),
                base.maxRangeXZ(),
                base.maxRangeY(),
                navOpt(navMaxFallHeight, settings.maxFallHeight()),
                base.maxStepUp(),
                base.allowDiagonal(),
                base.allowJumps(),
                base.maxJumpLength(),
                avoidLava(settings),
                avoidFire(settings),
                avoidPowderSnow(settings),
                avoidCobwebs(settings),
                // Baritone-like extensions
                allowBreakThrough(settings),
                base.breakCostBase(),
                allowPillar(settings),
                base.pillarCost(),
                allowParkour(settings),
                base.maxParkourLength(),
                allowDescendMine(settings),
                base.descendMineCost(),
                allowSprint(settings),
                base.sprintCostMultiplier(),
                mobAvoidance(settings),
                navOpt(navMobAvoidanceRadius, settings.mobAvoidanceRadius()),
                base.mobAvoidanceCost(),
                navOpt(navMaxFallHeight, settings.maxFallHeight()),
                base.jumpPenalty(),
                base.fallDamagePenalty(),
                base.allowDiagonalAscend(),
                base.allowDiagonalDescend(),
                avoidSoulSand(settings),
                allowOpenDoors(settings),
                allowOpenFenceGates(settings),
                allowSwimming(settings)
        );
    }

    private Vec3 resolveLandingTarget(Vec3 requested)
    {
        if (!(player.level() instanceof ServerLevel level))
        {
            return requested;
        }
        int x = Mth.floor(requested.x);
        int z = Mth.floor(requested.z);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
        y = Mth.clamp(y, level.getMinY() + 1, level.getMaxY() - 2);
        return new Vec3(x + 0.5D, y, z + 0.5D);
    }

    private static Vec2 rotationsTowards(Vec3 from, Vec3 to)
    {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float)(Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float)(-(Mth.atan2(dy, distXZ) * (180.0D / Math.PI)));
        return new Vec2(yaw, pitch);
    }

    private static float stepYaw(float current, float target, float maxStep)
    {
        float delta = Mth.wrapDegrees(target - current);
        float step = Mth.clamp(delta, -maxStep, maxStep);
        return current + step;
    }
}
