package carpet.pvp.nav;

import carpet.helpers.EntityPlayerActionPack;
import carpet.helpers.EntityPlayerActionPack.Action;
import carpet.helpers.EntityPlayerActionPack.ActionType;
import carpet.helpers.EntityPlayerActionPack.GlideArrivalAction;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.look.LookController;
import carpet.pvp.look.LookProfile;
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
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
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
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;


/**
 * Fake-player navigation. One instance per {@link EntityPlayerActionPack}, ticked from its
 * {@code onUpdate()} between the action loop and the glide controller.
 *
 * <p>Rule values arrive as a {@link Settings} snapshot built once per tick by the action pack, so this class
 * never reads server settings itself. Per-bot overrides set with {@code /player <bot> nav option ...} win over
 * the snapshot; a {@code null} override means "follow the rule".</p>
 *
 * <p>How a bot gets to where it is going:
 * <ul>
 *   <li>With {@link DirectSteer} it walks straight there when nothing is in the way, which is the usual case in
 *       the open and costs nothing.</li>
 *   <li>Otherwise a path is searched, a slice of the search per tick (see {@link NavSearchBudget}), so a long
 *       search never holds up the tick. While it runs the bot keeps walking the path it had.</li>
 *   <li>The path is smoothed with {@link PathSmoother} into the few straight lines a player would walk, and the
 *       jumps, drops, gaps and climbs in it stay where they are.</li>
 *   <li>Several bots chasing one player share one {@link FlowField} instead of each searching.</li>
 *   <li>Turning goes through {@link LookController}, so the view has a human's acceleration and leads the way
 *       round a corner slightly before the body takes it.</li>
 * </ul>
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
            boolean allowSwimming,
            int searchBudget,
            int searchBudgetTotal
    ) {}

    private static final ElytraAStarPathfinder ELYTRA_PATHFINDER = new ElytraAStarPathfinder();

    public static final int CHASE_CRIT_JUMP_LEAD_TICKS = 6;
    private static final int CHASE_REPATH_INTERVAL = 10;
    private static final double DEFAULT_CHASE_ATTACK_RANGE = 2.5D;
    /** Ticks between looks at whether the goal can be walked to straight. */
    private static final int DIRECT_STEER_RECHECK = 5;
    /** How close to a waypoint counts as having reached it, in blocks. */
    private static final double WAYPOINT_RADIUS = 0.9D;
    /** Past this fraction of a leg a waypoint counts as passed, which is what stops the bot steering back for one. */
    private static final double WAYPOINT_PASSED = 0.9D;
    /** How close to a corner the bot has to come before it turns, rather than cutting it or clipping the wall. */
    private static final double CORNER_RADIUS = 0.45D;
    /** Dot product of two legs at the angle where a waypoint counts as a corner rather than a straight run. */
    private static final double CORNER_ANGLE = 0.76D;
    /** Yaw error, in degrees, at which the sideways correction is at its strongest. */
    private static final double STRAFE_GAIN = 45.0D;
    /** How close the view looks at the waypoint being walked to before it looks at the one after it. */
    private static final double LOOK_LEAD_DISTANCE = 3.0D;
    /** Angle the view considers itself to be on a path waypoint, in degrees. */
    private static final float WAYPOINT_ANGLE = 2.5F;
    /** Climb rate along a ladder or a vine, in blocks per tick. */
    private static final double CLIMB_UP_SPEED = 0.2D;
    private static final double CLIMB_DOWN_SPEED = -0.18D;
    /** Horizontal speed, in blocks per tick, that counts as the momentum a long parkour gap needs. */
    private static final double SPRINT_SPEED = 0.12D;
    /** Ticks a bot will gather speed before jumping a gap that needs momentum anyway. */
    private static final int RUN_UP_TICKS = 20;
    /** Ticks a bot stops following a shared field after it has got stuck on one. */
    private static final int FIELD_BYPASS_TICKS = 40;
    /** Ticks of not getting any closer to the waypoint before the bot tries to work itself off a wall. */
    private static final int STUCK_TICKS = 8;
    /** How long the bot spends working itself off a wall once it has decided to. */
    private static final int UNSTICK_TICKS = 12;

    private enum AirArrival
    {
        LAND,
        DROP
    }

    private final EntityPlayerActionPack pack;
    private final ServerPlayer player;
    private final LookController look;
    private final NavAStarPathfinder.Search search = new NavAStarPathfinder.Search();
    private final BudgetedSearch searchRunner = new BudgetedSearch();
    private long[] smoothPath = new long[512];
    private int[] smoothMoves = new int[512];
    private long[] smoothOut = new long[512];
    private int[] smoothOutMoves = new int[512];
    /** True while the bot is following a flow field shared with the other chasers of its target. */
    private boolean navFlowField = false;
    /** True once a search has come back with no path at all, which is what makes a mode move on. */
    private boolean navRouteFailed = false;
    /** Set by chase mode on the ticks when the bot should be looking at the target rather than at the route. */
    private boolean navAimAtTarget = false;
    /** Ticks to leave the shared field alone after it has led this bot into something it could not get past. */
    private int navFieldBypass = 0;

    private boolean navEnabled = false;
    private BotNavMode navMode = BotNavMode.AUTO;
    private Vec3 navTargetPos = null;
    private double navArrivalRadius = 1.0D;

    private AirArrival navAirArrival = AirArrival.LAND;
    private Vec3 navAirRequestedTargetPos = null;

    private List<Vec3> navWaypoints = null;
    private List<NavAStarPathfinder.MoveType> navMoveTypes = null;
    private int navWaypointIndex = 0;
    private int navRepathCooldownTicks = 0;
    private boolean navNeedsRepath = false;
    private BlockPos navSearchGoal = null;
    private Vec3 navDirectTarget = null;
    private int navDirectSteerTicks = 0;
    private int navRunUpTicks = 0;

    private LevelWalkability view = null;
    private double navLastDistanceToNext = Double.POSITIVE_INFINITY;
    private int navNoProgressTicks = 0;
    private int navStuckTicks = 0;
    private int navUnstickTicks = 0;
    private float navUnstickStrafe = 0.0F;
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

    public NavController(EntityPlayerActionPack pack)
    {
        this.pack = pack;
        this.player = pack.getPlayer();
        this.look = new LookController(LookProfile.ofSkill(0.6, 0.5F), new Random(player.getUUID().hashCode()));
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

        clearPath();
        search.cancel();
        navSearchGoal = null;
        navRepathCooldownTicks = 0;
        navNeedsRepath = false;
        navNoProgressTicks = 0;
        navLastDistanceToNext = Double.POSITIVE_INFINITY;
        navJumpCooldownTicks = 0;
        releaseChaseField();
        look.clearTarget();

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

        // Let go of the inputs navigation was driving, so the bot stands still instead of walking on.
        pack.setForward(0.0F);
        pack.setStrafing(0.0F);
        pack.setSprinting(false);
        player.setJumping(false);

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
        resetRoute();
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

        resetRoute();
    }

    public void follow(UUID targetUUID, double radius)
    {
        stop();
        navEnabled = true;
        navMode = BotNavMode.FOLLOW;
        navFollowTarget = targetUUID;
        navFollowRadius = Math.max(1.0D, radius);
        navFollowRepathTicks = 0;
        navArrivalRadius = navFollowRadius;
        resetRoute();
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
        ChaseFlowFields.acquire(targetUUID);
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
        resetRoute();
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
            resetRoute();
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

    /** True while this bot's own search still has nodes to expand, which is how the self-test watches it spread over ticks. */
    public boolean isSearchRunning() { return search.searching(); }

    /** True while this bot is following a shared flow field towards its chase target. */
    public boolean isFollowingFlowField() { return navFlowField; }

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
        if (navFieldBypass > 0)
        {
            navFieldBypass--;
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
                navMoveTypes = new ArrayList<>(Collections.nCopies(waypoints.size(),
                        NavAStarPathfinder.MoveType.WALK));
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

        tickGround(settings, effectiveMode, true);
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
            clearRoute();
            // Still keep following – just wait.
            navFollowRepathTicks = Math.min(navFollowRepathTicks, 10);
        }

        navFollowRepathTicks--;
        if (navFollowRepathTicks <= 0 && distToTarget > navFollowRadius)
        {
            navFollowRepathTicks = navFollowRepathInterval;
            navNeedsRepath = true;
        }
        navTargetPos = target.position();

        tickGround(settings, BotNavMode.LAND, false);
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

        // While closing in the bot looks where it is going, so the view leads the route round obstacles; from a
        // hit away it looks at the target, which is what its swing has to be lined up on.
        navAimAtTarget = distToTarget <= navChaseAttackRange + critJumpDistance;

        // Update in-range state and entity ID for the ATTACK action.
        if (distToTarget <= navChaseAttackRange)
        {
            navChaseInRange = true;
            navChaseTargetEntityId = target.getId();

            // Stop moving, face target, let the attack action handle the rest.
            pack.stopMovement();
            clearRoute();
            navAimAtTarget = true;
            navChaseRepathTicks = Math.min(navChaseRepathTicks, 5);
        }
        else
        {
            navChaseInJumpRange = (distToTarget <= navChaseAttackRange + critJumpDistance);
            navChaseInRange = false;
            navChaseTargetEntityId = -1;
        }

        // Re-path to the target when it is time to, but keep walking the path we have while the new one is
        // being searched.
        navChaseRepathTicks--;
        if (navChaseRepathTicks <= 0)
        {
            navChaseRepathTicks = CHASE_REPATH_INTERVAL;
            navNeedsRepath = true;
        }
        navTargetPos = target.position();

        if (distToTarget > navChaseAttackRange)
        {
            tickGround(settings, BotNavMode.LAND, false);
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
                clearRoute();
                return;
            }
            tryBreakBlock(navMineCurrentTarget, Direction.UP, settings);
            pack.stopMovement();
            return;
        }

        tickGround(settings, BotNavMode.LAND, true);
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

        tickGround(settings, BotNavMode.LAND, true);
        if (navRouteFailed)
        {
            // No route to this waypoint at all: skip it rather than stand in front of it.
            if (navPatrolLoop)
            {
                navPatrolIndex = (navPatrolIndex + 1) % navPatrolWaypoints.size();
                navTargetPos = navPatrolWaypoints.get(navPatrolIndex);
                navNeedsRepath = true;
                navRepathCooldownTicks = 0;
                navRouteFailed = false;
            }
            else
            {
                stop();
            }
        }
    }

    // ====== Routing and following, shared by every land mode ======

    /**
     * Finds a route towards {@link #navTargetPos} and walks it.
     *
     * @param stopWhenUnreachable whether a bot with no route at all gives up instead of retrying
     * @return false when there is nothing to walk yet, which is while a search is still being searched
     */
    private boolean tickGround(Settings settings, BotNavMode effectiveMode, boolean stopWhenUnreachable)
    {
        ServerLevel level = (ServerLevel) player.level();
        LevelWalkability view = view(level, settings);
        BlockPos goal = goalNode(view);
        if (goal == null)
        {
            if (stopWhenUnreachable)
            {
                stop();
                pack.stopMovement();
            }
            return false;
        }

        if (pack.isGlideEnabled())
        {
            pack.setGlideEnabled(false);
        }

        route(level, view, goal, settings);

        if (navWaypoints == null || navWaypointIndex >= navWaypoints.size())
        {
            // Nothing to walk yet: a search is running or about to start, so the bot looks at the goal and waits
            // rather than setting off in a direction nothing has been planned for.
            lookAt(surface(view, goal), WAYPOINT_ANGLE);
            if (!search.searching())
            {
                navNeedsRepath = navRepathCooldownTicks <= 0;
            }
            return false;
        }

        followPath(view, settings, effectiveMode);
        return true;
    }

    /**
     * Puts something to walk: the shared field when a crowd chases one target, a straight line when the goal can
     * be walked to, and otherwise a path, found as far as this tick's budget allows. While a search runs the path
     * the bot already had stays in place, so it keeps moving.
     */
    private void route(ServerLevel level, LevelWalkability view, BlockPos goal, Settings settings)
    {
        navAimAtTarget = false;

        if (navMode == BotNavMode.CHASE && navChaseTarget != null && navFieldBypass <= 0
                && ChaseFlowFields.isShared(navChaseTarget))
        {
            navFlowField = true;
            navAimAtTarget = navChaseInRange;
            BlockPos at = feetNode(view);
            if (at != null)
            {
                ChaseFlowFields.targetMoved(navChaseTarget, goal);
                int direction = ChaseFlowFields.direction(level.getServer(), view, navChaseTarget, at,
                        settings.searchBudgetTotal());
                if (direction != FlowField.NO_ROUTE)
                {
                    int nx = at.getX() + FlowField.stepX(direction);
                    int ny = at.getY() + FlowField.stepY(direction);
                    int nz = at.getZ() + FlowField.stepZ(direction);
                    if (view.canStand(nx, ny, nz))
                    {
                        installStep(view, nx, ny, nz, stepMove(direction));
                        return;
                    }
                }
            }
        }
        navFlowField = false;

        // Straight there, when the walk is clear. The walk is re-checked every few ticks, since anything can walk
        // into it, and while it holds the bot keeps aiming at wherever the goal is now.
        if (--navDirectSteerTicks <= 0 ? recheckDirectSteer(view, goal) : navDirectTarget != null)
        {
            if (navWaypoints == null)
            {
                startDirectSteer(view, goal);
            }
            navWaypoints.set(0, surface(view, goal));
            return;
        }

        advanceSearch(level, view, goal, settings);
    }

    /**
     * Whether the bot can still walk straight at the goal, dropping the straight line and asking for a path when
     * something has got in the way of it.
     */
    private boolean recheckDirectSteer(LevelWalkability view, BlockPos goal)
    {
        navDirectSteerTicks = DIRECT_STEER_RECHECK;
        BlockPos at = feetNode(view);
        if (at != null && DirectSteer.canWalkLine(view, at.getX(), at.getY(), at.getZ(), goal.getX(),
                goal.getY(), goal.getZ()))
        {
            if (navDirectTarget == null)
            {
                startDirectSteer(view, goal);
            }
            return true;
        }
        if (navDirectTarget != null)
        {
            navDirectTarget = null;
            clearPath();
            navNeedsRepath = true;
            navRepathCooldownTicks = 0;
        }
        return false;
    }

    private NavAStarPathfinder.MoveType stepMove(int direction)
    {
        int up = FlowField.stepY(direction);
        if (up > 0) return NavAStarPathfinder.MoveType.JUMP;
        if (up < 0) return NavAStarPathfinder.MoveType.FALL;
        return NavAStarPathfinder.MoveType.WALK;
    }

    /** Steers straight at the goal, with no path, for as long as the straight walk stays clear. */
    private void startDirectSteer(LevelWalkability view, BlockPos goal)
    {
        clearPath();
        navWaypoints = new ArrayList<>(1);
        navMoveTypes = new ArrayList<>(1);
        navWaypoints.add(surface(view, goal));
        navMoveTypes.add(NavAStarPathfinder.MoveType.WALK);
        navWaypointIndex = 0;
        navDirectTarget = navWaypoints.get(0);
        navRouteFailed = false;
        search.cancel();
        navSearchGoal = null;
        navNeedsRepath = false;
        navRepathCooldownTicks = 0;
    }

    /**
     * Starts a search if there is none, gives the one that is running this tick's slice of the budget, and takes up
     * the path it produced. A search that comes back with nothing leaves {@link #navRouteFailed} set, which is what
     * tells a mode with somewhere else to go that this waypoint is out of reach.
     */
    private void advanceSearch(ServerLevel level, LevelWalkability view, BlockPos goal, Settings settings)
    {
        if (!search.searching())
        {
            if (!navNeedsRepath || navRepathCooldownTicks > 0)
            {
                return;
            }
            navNeedsRepath = false;
            navRepathCooldownTicks = 20;
            navSearchGoal = goal;
            NavAStarPathfinder.Traversal traversal = isInWaterish()
                    ? NavAStarPathfinder.Traversal.WATER : NavAStarPathfinder.Traversal.AMPHIBIOUS;
            search.begin(view, player.blockPosition(), goal, traversal, buildPathSettings(settings));
        }

        NavSearchBudget.run(level.getServer(), settings.searchBudget(), settings.searchBudgetTotal(),
                searchRunner, search);
        if (!search.done())
        {
            return;
        }

        NavAStarPathfinder.PathResult result = search.result();
        navSearchGoal = null;
        if (result == null || result.positions().isEmpty())
        {
            clearPath();
            navRouteFailed = true;
            navNeedsRepath = false;
            return;
        }
        installPath(view, result);
    }

    /** Smooths a freshly found path into the few straight legs a player would walk, and adopts it. */
    private void installPath(LevelWalkability view, NavAStarPathfinder.PathResult result)
    {
        List<BlockPos> positions = result.positions();
        int count = positions.size();
        if (smoothPath.length < count)
        {
            int size = Math.max(count, smoothPath.length * 2);
            smoothPath = new long[size];
            smoothMoves = new int[size];
            smoothOut = new long[size];
            smoothOutMoves = new int[size];
        }
        for (int i = 0; i < count; i++)
        {
            BlockPos pos = positions.get(i);
            smoothPath[i] = NavPos.pack(pos.getX(), pos.getY(), pos.getZ());
            smoothMoves[i] = result.moveTypes().get(i).ordinal();
        }
        int smoothed = PathSmoother.smooth(view, smoothPath, smoothMoves, count, smoothOut, smoothOutMoves);

        navWaypoints = new ArrayList<>(smoothed);
        navMoveTypes = new ArrayList<>(smoothed);
        for (int i = 0; i < smoothed; i++)
        {
            navWaypoints.add(surface(view, NavPos.unpackX(smoothOut[i]), NavPos.unpackY(smoothOut[i]),
                    NavPos.unpackZ(smoothOut[i])));
            navMoveTypes.add(NavAStarPathfinder.MoveType.values()[smoothOutMoves[i]]);
        }
        navWaypointIndex = 0;
        navDirectTarget = null;
        navRouteFailed = false;
        navNoProgressTicks = 0;
        navLastDistanceToNext = Double.POSITIVE_INFINITY;
        navRunUpTicks = 0;
        navRepathCooldownTicks = 0;
    }

    /** Adopts a single cell as the whole route, which is how a shared flow field is followed one step at a time. */
    private void installStep(LevelWalkability view, int x, int y, int z, NavAStarPathfinder.MoveType move)
    {
        Vec3 step = surface(view, x, y, z);
        if (navWaypoints == null || navMoveTypes == null || navWaypoints.size() != 1)
        {
            navWaypoints = new ArrayList<>(1);
            navMoveTypes = new ArrayList<>(1);
            navWaypoints.add(step);
            navMoveTypes.add(move);
        }
        else
        {
            navWaypoints.set(0, step);
            navMoveTypes.set(0, move);
        }
        navWaypointIndex = 0;
        navDirectTarget = null;
        navRouteFailed = false;
        navNoProgressTicks = 0;
        navLastDistanceToNext = Double.POSITIVE_INFINITY;
        navRunUpTicks = 0;
    }

    /**
     * Walks the current leg: opens what is in the way, mines or places what the move calls for, turns the way with
     * the look controller, and steps or jumps as the move and the ground need.
     */
    private void followPath(LevelWalkability view, Settings settings, BotNavMode effectiveMode)
    {
        Vec3 next = navWaypoints.get(navWaypointIndex);
        NavAStarPathfinder.MoveType move = navMoveTypes.get(navWaypointIndex);
        Vec3 previous = navWaypointIndex == 0 ? start() : navWaypoints.get(navWaypointIndex - 1);
        double distance = player.position().distanceTo(next);

        if (move == NavAStarPathfinder.MoveType.CLIMB_UP || move == NavAStarPathfinder.MoveType.CLIMB_DOWN)
        {
            climb(view, next, move);
            return;
        }

        if (reached(previous, next))
        {
            advanceWaypoint();
            return;
        }

        if (distance + 0.01D < navLastDistanceToNext)
        {
            navNoProgressTicks = 0;
            navStuckTicks = 0;
        }
        else
        {
            navNoProgressTicks++;
            navStuckTicks++;
        }
        navLastDistanceToNext = distance;

        if (navUnstickTicks > 0 && --navUnstickTicks == 0)
        {
            navStuckTicks = 0;
        }
        if (navUnstickTicks == 0 && navStuckTicks >= STUCK_TICKS)
        {
            startUnsticking(view, next);
        }
        if (navUnstickTicks > 0)
        {
            // Wedged: a bot that is neither reaching the waypoint nor getting any closer to it walks into
            // whatever is in the way, so it leans on it and slides along towards the side with room.
            pack.setSneaking(false);
            pack.setSprinting(false);
            pack.setForward(0.85F);
            pack.setStrafing(navUnstickStrafe);
            return;
        }

        if (navNoProgressTicks > 60)
        {
            navNoProgressTicks = 0;
            // A shared field says which cell to aim at, not how to get into it, so a bot that has stopped making
            // headway on one stops following it for a while and searches a way of its own.
            if (navFlowField)
            {
                navFieldBypass = FIELD_BYPASS_TICKS;
                clearRoute();
                return;
            }
            if (allowBreakBlocks(settings) && tryBreakBlockingAhead(settings))
            {
                navNeedsRepath = true;
                return;
            }
            navNeedsRepath = true;
            navRepathCooldownTicks = 0;
            return;
        }

        BlockPos nextFeet = BlockPos.containing(next);
        if (!handleBlockInTheWay(settings, nextFeet, move, distance))
        {
            return;
        }

        boolean parkour = move == NavAStarPathfinder.MoveType.PARKOUR
                || move == NavAStarPathfinder.MoveType.PARKOUR_RUNUP;
        boolean onIce = view.onIce(player.blockPosition().getX(), Mth.floor(player.getY()),
                player.blockPosition().getZ());
        boolean sprint = allowSprint(settings) && !onIce && (move == NavAStarPathfinder.MoveType.WALK || parkour);

        lookAt(navAimAtTarget ? navTargetPos : lookTarget(), WAYPOINT_ANGLE);

        float forward = 1.0F;
        float strafe = strafeTowards(next);
        if (onIce && distance <= 3.0D)
        {
            // Slow down on ice so the bot does not slide past the waypoint it is aiming at.
            forward = 0.4F;
            strafe = 0.0F;
        }

        pack.setSneaking(false);
        pack.setSprinting(sprint);
        pack.setForward(forward);
        pack.setStrafing(strafe);

        if (effectiveMode == BotNavMode.WATER && isInWaterish())
        {
            swim(next, settings);
            return;
        }

        boolean wantUp = next.y > player.getY() + 0.2D;
        jump(view, next, move, wantUp || (parkour && atLedge(view, next)));
    }

    /**
     * Doors to open and blocks to mine or place for the move being walked. Returns false when the bot spent this
     * tick on the block instead of walking.
     */
    private boolean handleBlockInTheWay(Settings settings, BlockPos nextFeet, NavAStarPathfinder.MoveType move,
            double distance)
    {
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

        if (move == NavAStarPathfinder.MoveType.BREAK_THROUGH && allowBreakBlocks(settings) && distance <= 2.5D)
        {
            if (!nextBlockState.getCollisionShape(player.level(), nextFeet).isEmpty())
            {
                tryBreakBlock(nextFeet, Direction.UP, settings);
                return false;
            }
            BlockState headAhead = player.level().getBlockState(nextFeet.above());
            if (!headAhead.getCollisionShape(player.level(), nextFeet.above()).isEmpty())
            {
                tryBreakBlock(nextFeet.above(), Direction.UP, settings);
                return false;
            }
        }

        if (move == NavAStarPathfinder.MoveType.DESCEND_MINE && allowBreakBlocks(settings) && distance <= 1.5D)
        {
            BlockState belowState = player.level().getBlockState(nextFeet);
            if (!belowState.getCollisionShape(player.level(), nextFeet).isEmpty())
            {
                tryBreakBlock(nextFeet, Direction.UP, settings);
                return false;
            }
        }

        if (move == NavAStarPathfinder.MoveType.PILLAR && allowPlaceBlocks(settings) && distance <= 1.5D)
        {
            if (tryPlaceBridgeBlock(player.blockPosition(), settings) && player.onGround() && navJumpCooldownTicks <= 0)
            {
                // Jump onto the block just placed.
                navJumpCooldownTicks = 8;
                pack.start(ActionType.JUMP, Action.once());
                return false;
            }
        }
        return true;
    }

    /** Whether the bot has got to this waypoint, or is far enough along the leg to have passed it. */
    private boolean reached(Vec3 previous, Vec3 next)
    {
        double sideways = horizontalDistance(player.position(), next);
        if (!carriesStraightOn()) return sideways <= CORNER_RADIUS;
        if (sideways <= WAYPOINT_RADIUS) return true;
        Vec3 leg = next.subtract(previous);
        double length = leg.lengthSqr();
        if (length < 1.0E-6D) return true;
        return player.position().subtract(previous).dot(leg) / length >= WAYPOINT_PASSED;
    }

    /**
     * True where the path carries on within about forty degrees of straight after this waypoint: there the view
     * may lead the path round a bend, and there a waypoint can be passed by a comfortable margin. Anywhere else the
     * bot comes to the point instead, because cutting a corner is what walks it into the wall the corner hugs.
     */
    private boolean carriesStraightOn()
    {
        if (navWaypointIndex + 1 >= navWaypoints.size()) return true;
        Vec3 in = horizontal(navWaypoints.get(navWaypointIndex), player.position());
        Vec3 out = horizontal(navWaypoints.get(navWaypointIndex), navWaypoints.get(navWaypointIndex + 1));
        if (in.lengthSqr() < 1.0E-6D || out.lengthSqr() < 1.0E-6D) return true;
        return in.normalize().dot(out.normalize()) >= CORNER_ANGLE;
    }

    private static Vec3 horizontal(Vec3 from, Vec3 to)
    {
        return new Vec3(to.x - from.x, 0.0D, to.z - from.z);
    }

    private void advanceWaypoint()
    {
        if (navDirectTarget != null)
        {
            // The straight line is a live target rather than a list of them: keep walking towards wherever the
            // goal is now, and the mode's own arrival test decides when to stop.
            return;
        }
        navWaypointIndex++;
        navNoProgressTicks = 0;
        navLastDistanceToNext = Double.POSITIVE_INFINITY;
        navRunUpTicks = 0;
        if (navWaterJumping)
        {
            player.setJumping(false);
            navWaterJumping = false;
        }
        if (navWaypointIndex >= navWaypoints.size())
        {
            // End of the path: ask for another one, unless the bot is walking straight at the goal, where the
            // direct-walk re-check decides whether to keep going or start searching.
            navNeedsRepath = navDirectTarget == null;
        }
    }

    /**
     * Jumps when the bot has to get up onto something, or to clear a gap from the edge of it. A gap too wide for a
     * standing jump is only taken with momentum: the bot keeps sprinting up to the edge and jumps once it is
     * actually moving, which is what a player does.
     */
    private void jump(LevelWalkability view, Vec3 next, NavAStarPathfinder.MoveType move, boolean wanted)
    {
        if (!wanted || !player.onGround() || navJumpCooldownTicks > 0)
        {
            return;
        }
        if (move == NavAStarPathfinder.MoveType.PARKOUR_RUNUP && !hasMomentum() && navRunUpTicks < RUN_UP_TICKS)
        {
            navRunUpTicks++;
            return;
        }
        BlockPos headAbove = player.blockPosition().above(2);
        BlockState headAboveState = player.level().getBlockState(headAbove);
        if (!headAboveState.getCollisionShape(player.level(), headAbove).isEmpty())
        {
            // Can't jump - ceiling too low; try to path around instead
            navNeedsRepath = true;
            return;
        }
        boolean parkour = move == NavAStarPathfinder.MoveType.PARKOUR
                || move == NavAStarPathfinder.MoveType.PARKOUR_RUNUP;
        navJumpCooldownTicks = parkour ? 10 : 8;
        navRunUpTicks = 0;
        pack.start(ActionType.JUMP, Action.once());
    }

    /** True when the bot is moving fast enough for a gap that needs momentum to be clearable. */
    private boolean hasMomentum()
    {
        if (!player.isSprinting()) return false;
        Vec3 delta = player.getDeltaMovement();
        return Math.sqrt(delta.x * delta.x + delta.z * delta.z) >= SPRINT_SPEED;
    }

    /** True when the ground the bot is standing on ends right where it is heading. */
    private boolean atLedge(LevelWalkability view, Vec3 next)
    {
        double dx = next.x - player.getX();
        double dz = next.z - player.getZ();
        int stepX = Math.abs(dx) >= Math.abs(dz) ? (int) Math.signum(dx) : 0;
        int stepZ = stepX != 0 ? 0 : (int) Math.signum(dz);
        if (stepX == 0 && stepZ == 0) return false;
        return !view.canStand(player.blockPosition().getX() + stepX, Mth.floor(player.getY()),
                player.blockPosition().getZ() + stepZ);
    }

    /**
     * Picks the side with room and starts leaning on the wall towards it. A bot that has walked into a corner
     * cannot get out by walking harder at the same waypoint, and this is what a player does instead: sidestep.
     */
    private void startUnsticking(LevelWalkability view, Vec3 aim)
    {
        int feetX = player.blockPosition().getX();
        int feetY = Mth.floor(player.getY());
        int feetZ = player.blockPosition().getZ();
        double dx = aim.x - player.getX();
        double dz = aim.z - player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        int sideX = length < 1.0E-3D ? 0 : (int) Math.round(-dz / length);
        int sideZ = length < 1.0E-3D ? 0 : (int) Math.round(dx / length);
        boolean right = view.canPass(feetX + sideX, feetY, feetZ + sideZ);
        boolean left = view.canPass(feetX - sideX, feetY, feetZ - sideZ);
        // The side vector is the one on the bot's right, and strafing left is positive.
        navUnstickStrafe = right ? -1.0F : left ? 1.0F : 1.0F;
        navUnstickTicks = UNSTICK_TICKS;
        navStuckTicks = 0;
        navNoProgressTicks = 0;
    }

    /** How far to walk sideways, which is what keeps the body on the line while the view is still turning. */
    private float strafeTowards(Vec3 aim)
    {
        Vec2 rotation = rotationsTowards(player.getEyePosition(1.0F), aim);
        return (float) Mth.clamp(-Mth.wrapDegrees(rotation.x - player.getYRot()) / STRAFE_GAIN, -1.0D, 1.0D);
    }

    /** Swimming: float at the surface by jumping, sink by sneaking, or swim in three dimensions when allowed. */
    private void swim(Vec3 next, Settings settings)
    {
        boolean wantUp = next.y > player.getY() + 0.2D;
        boolean wantDown = next.y < player.getY() - 0.4D;
        if (!allowSwimming(settings))
        {
            player.setJumping(true);
            navWaterJumping = true;
            pack.setSneaking(false);
            return;
        }
        if (wantUp)
        {
            player.setJumping(true);
            navWaterJumping = true;
            pack.setSneaking(false);
        }
        else if (wantDown)
        {
            player.setJumping(false);
            navWaterJumping = false;
            pack.setSneaking(true);
        }
        else
        {
            player.setJumping(false);
            navWaterJumping = false;
            pack.setSneaking(false);
        }
        if (allowSprint(settings))
        {
            pack.setSprinting(true);
        }
    }

    /**
     * Climbing a ladder, a vine or a scaffolding. The bot walks into the column first, then drives itself up or
     * down it while holding station in the column, because a ladder holds nothing up on its own.
     */
    private void climb(LevelWalkability view, Vec3 next, NavAStarPathfinder.MoveType move)
    {
        int columnX = Mth.floor(next.x);
        int columnZ = Mth.floor(next.z);
        int feetY = Mth.floor(player.getY());
        // Only the bot's own cell decides whether it is holding on: a climbable in the column it is heading for
        // does not hold it up until it has walked in.
        boolean inColumn = player.blockPosition().getX() == columnX && player.blockPosition().getZ() == columnZ;
        boolean holding = inColumn && (view.climbable(columnX, feetY, columnZ)
                || view.climbable(columnX, feetY + 1, columnZ));
        Vec3 column = new Vec3(columnX + 0.5D, next.y, columnZ + 0.5D);

        Direction facing = view.climbFacing(columnX, feetY, columnZ);
        lookAt(facing == null ? column : column.add(facing.getStepX() * 4.0D, 0.0D, facing.getStepZ() * 4.0D),
                WAYPOINT_ANGLE);

        pack.setSneaking(false);
        pack.setSprinting(false);
        if (!holding)
        {
            // Not on it yet: walk into the column.
            pack.setForward(1.0F);
            pack.setStrafing(strafeTowards(column));
            return;
        }

        pack.setForward(0.0F);
        pack.setStrafing(strafeTowards(new Vec3(columnX + 0.5D, player.getY(), columnZ + 0.5D)));
        player.setJumping(false);
        navWaterJumping = false;

        double rise = next.y - player.getY();
        if ((move == NavAStarPathfinder.MoveType.CLIMB_UP && rise <= 0.35D)
                || (move == NavAStarPathfinder.MoveType.CLIMB_DOWN && rise >= -0.35D))
        {
            advanceWaypoint();
            return;
        }

        Vec3 delta = player.getDeltaMovement();
        player.setDeltaMovement(delta.x,
                move == NavAStarPathfinder.MoveType.CLIMB_UP ? CLIMB_UP_SPEED : CLIMB_DOWN_SPEED, delta.z);
        player.resetFallDistance();
        navLastDistanceToNext = player.position().distanceTo(next);
    }

    /** Where the view looks: at the waypoint being walked to, or a little further on when it is nearly there. */
    private Vec3 lookTarget()
    {
        Vec3 next = navWaypoints.get(navWaypointIndex);
        if (carriesStraightOn() && navWaypointIndex + 1 < navWaypoints.size()
                && horizontalDistance(player.position(), next) < LOOK_LEAD_DISTANCE)
        {
            return navWaypoints.get(navWaypointIndex + 1);
        }
        return next;
    }

    /** Points the view at a place with the human turn of {@link LookController}, then applies it. */
    private void lookAt(Vec3 aim, float radius)
    {
        Vec2 rotation = rotationsTowards(player.getEyePosition(1.0F), aim);
        look.aimAt(rotation.x, rotation.y, radius);
        look.tick();
        pack.look(look.yaw(), look.pitch());
    }

    // ====== Small helpers ======

    private void resetRoute()
    {
        clearRoute();
        navNoProgressTicks = 0;
        navLastDistanceToNext = Double.POSITIVE_INFINITY;
        navDirectSteerTicks = 0;
        search.cancel();
        navSearchGoal = null;
        look.reset(player.getYRot(), player.getXRot());
    }

    private void clearPath()
    {
        navWaypoints = null;
        navMoveTypes = null;
        navWaypointIndex = 0;
        navRunUpTicks = 0;
    }

    /** Drops whatever route the bot was on, straight line or path, and asks for another one. */
    private void clearRoute()
    {
        clearPath();
        navDirectTarget = null;
        navRouteFailed = false;
        navNeedsRepath = true;
        navRepathCooldownTicks = 0;
    }

    private void releaseChaseField()
    {
        if (navChaseTarget != null)
        {
            ChaseFlowFields.release(navChaseTarget);
        }
        navFlowField = false;
    }

    private LevelWalkability view(ServerLevel level, Settings settings)
    {
        if (view == null || view.level() != level)
        {
            view = new LevelWalkability(level);
        }
        view.hazards(avoidLava(settings), avoidFire(settings), avoidPowderSnow(settings), avoidCobwebs(settings));
        return view;
    }

    /** The cell the bot is standing in, or null when it is nowhere navigation can work. */
    private BlockPos feetNode(LevelWalkability view)
    {
        int y = Mth.floor(player.getY());
        return view.canStand(player.blockPosition().getX(), y, player.blockPosition().getZ())
                ? new BlockPos(player.blockPosition().getX(), y, player.blockPosition().getZ()) : null;
    }

    /** The nearest cell to the goal a player can stand in, which is where a path has to end. */
    private BlockPos goalNode(LevelWalkability view)
    {
        if (navTargetPos == null) return null;
        BlockPos around = BlockPos.containing(navTargetPos);
        int y = view.standYNear(around.getX(), around.getY(), around.getZ(), 8);
        return y == LevelWalkability.NO_STAND ? null : new BlockPos(around.getX(), y, around.getZ());
    }

    /** Where a player stands at this cell: the middle of it horizontally, on whatever is underfoot. */
    private static Vec3 surface(LevelWalkability view, int x, int y, int z)
    {
        return new Vec3(x + 0.5D, view.surfaceY(x, y, z), z + 0.5D);
    }

    private static Vec3 surface(LevelWalkability view, BlockPos pos)
    {
        return surface(view, pos.getX(), pos.getY(), pos.getZ());
    }

    private Vec3 start()
    {
        return new Vec3(player.getX(), player.getY(), player.getZ());
    }

    private static double horizontalDistance(Vec3 from, Vec3 to)
    {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
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
                base.parkourRunUpLength(),
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
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, distXZ) * (180.0D / Math.PI)));
        return new Vec2(yaw, pitch);
    }
}
