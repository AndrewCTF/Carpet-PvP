package carpet.helpers;

import carpet.CarpetSettings;
import carpet.fakes.ServerPlayerInterface;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import carpet.patches.EntityPlayerMPFake;
import carpet.script.utils.Tracer;
import carpet.helpers.pathfinding.BotNavMode;
import carpet.helpers.pathfinding.ElytraAStarPathfinder;
import carpet.helpers.pathfinding.NavAStarPathfinder;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.Heightmap;

public class EntityPlayerActionPack
{
    private final ServerPlayer player;

    private final Map<ActionType, Action> actions = new EnumMap<>(ActionType.class);

    private BlockPos currentBlock;
    private int blockHitDelay;
    private boolean isHittingBlock;
    private float curBlockDamageMP;

    private boolean sneaking;
    private boolean sprinting;
    private float forward;
    private float strafing;

    private int itemUseCooldown;

    private boolean attackCritical;

    private boolean critAwaitingGroundAfterHit;
    private int critPostLandingDelay;

    private boolean glideEnabled;
    private boolean glideFrozen;
    private boolean glideFreezeAtTarget;
    private GlideArrivalAction glideArrivalAction = GlideArrivalAction.LAND;
    private GlideMode glideMode = GlideMode.MANUAL;

    private double glideSpeed = 1.6D; // blocks per tick
    private float glideYawRate = 10.0F; // deg per tick
    private float glidePitchRate = 10.0F; // deg per tick

    private float glideInputForward = 1.0F;
    private float glideInputStrafe = 0.0F;
    private float glideInputUp = 0.0F;
    private boolean glideUsePitchForForward = true;

    private float glideTargetYaw;
    private float glideTargetPitch;
    private Vec3 glideTargetPos;
    private Vec3 glideLandingTargetPos;
    private double glideArrivalRadius = 1.0D;

    private List<Vec3> glideWaypoints;
    private int glideWaypointIndex;

    private Boolean glidePrevNoGravity;
    private Boolean glidePrevAbilityFlying;
    private int glideDeployDelayTicks;
    private int glideDeployAttempts;
    private int glideTakeoffTimeoutTicks;
    private boolean glideTakeoffRequested;
    private boolean glideHasDeployed;

    // Launch assist (pre-deployment) to achieve consistent elytra takeoff
    private boolean glideLaunchAssistEnabled = true;
    private float glideLaunchPitchDeg = 18.0F; // gentle nose-down
    private double glideLaunchSpeed = 0.6D;    // horizontal boost (blocks/tick)
    private int glideLaunchForwardTicks = 6;   // ticks of pre-deploy horizontal boost
    private int glideLaunchTicksRemaining = 0;

    // --- Bot navigation (Baritone-like foundation) ---
    private static final ElytraAStarPathfinder NAV_ELYTRA_PATHFINDER = new ElytraAStarPathfinder();
    private static final NavAStarPathfinder NAV_ASTAR = new NavAStarPathfinder();

    private boolean navEnabled = false;
    private BotNavMode navMode = BotNavMode.AUTO;
    private Vec3 navTargetPos = null;
    private double navArrivalRadius = 1.0D;

    private enum NavAirArrival
    {
        LAND,
        DROP
    }

    private NavAirArrival navAirArrival = NavAirArrival.LAND;
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

    // Navigation behavior overrides (null = use Carpet rules).
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

    public EntityPlayerActionPack(ServerPlayer playerIn)
    {
        player = playerIn;
        stopAll();
    }
    public void copyFrom(EntityPlayerActionPack other)
    {
        actions.putAll(other.actions);
        currentBlock = other.currentBlock;
        blockHitDelay = other.blockHitDelay;
        isHittingBlock = other.isHittingBlock;
        curBlockDamageMP = other.curBlockDamageMP;

        sneaking = other.sneaking;
        sprinting = other.sprinting;
        forward = other.forward;
        strafing = other.strafing;

        itemUseCooldown = other.itemUseCooldown;

        attackCritical = other.attackCritical;
    }

    public EntityPlayerActionPack setAttackCritical(boolean critical)
    {
        attackCritical = critical;
        if (!critical)
        {
            critAwaitingGroundAfterHit = false;
            critPostLandingDelay = 0;
        }
        return this;
    }

    public EntityPlayerActionPack setGlideEnabled(boolean enabled)
    {
        boolean wasEnabled = glideEnabled;
        glideEnabled = enabled;
        if (enabled && !wasEnabled)
        {
            glideDeployDelayTicks = 0;
            glideDeployAttempts = 0;
            glideTakeoffTimeoutTicks = 0;
            glideTakeoffRequested = false;
            glideHasDeployed = false;
            glideLaunchTicksRemaining = 0;
            if (glidePrevAbilityFlying == null)
            {
                glidePrevAbilityFlying = player.getAbilities().flying;
            }
            if (player.getAbilities().flying)
            {
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
            }
        }
        if (!enabled)
        {
            setGlideFrozen(false);
            glideMode = GlideMode.MANUAL;
            glideTargetPos = null;
            glideLandingTargetPos = null;
            glideWaypoints = null;
            glideWaypointIndex = 0;
            glideDeployDelayTicks = 0;
            glideDeployAttempts = 0;
            glideTakeoffTimeoutTicks = 0;
            glideTakeoffRequested = false;
            glideHasDeployed = false;
            glideLaunchTicksRemaining = 0;
            if (glidePrevAbilityFlying != null)
            {
                player.getAbilities().flying = glidePrevAbilityFlying;
                player.onUpdateAbilities();
                glidePrevAbilityFlying = null;
            }
        }
        return this;
    }

    public boolean isGlideEnabled()
    {
        return glideEnabled;
    }

    public EntityPlayerActionPack setGlideFrozen(boolean frozen)
    {
        glideFrozen = frozen;
        if (!frozen)
        {
            if (glidePrevNoGravity != null)
            {
                player.setNoGravity(glidePrevNoGravity);
                glidePrevNoGravity = null;
            }
        }
        return this;
    }

    public boolean isGlideFrozen()
    {
        return glideFrozen;
    }

    public EntityPlayerActionPack setGlideFreezeAtTarget(boolean freezeAtTarget)
    {
        glideFreezeAtTarget = freezeAtTarget;
        glideArrivalAction = freezeAtTarget ? GlideArrivalAction.FREEZE : GlideArrivalAction.STOP;
        return this;
    }

    public EntityPlayerActionPack setGlideArrivalAction(GlideArrivalAction action)
    {
        glideArrivalAction = action == null ? GlideArrivalAction.STOP : action;
        glideFreezeAtTarget = glideArrivalAction == GlideArrivalAction.FREEZE;
        return this;
    }

    public GlideArrivalAction getGlideArrivalAction()
    {
        return glideArrivalAction;
    }

    public EntityPlayerActionPack setGlideLaunchAssistEnabled(boolean enabled)
    {
        glideLaunchAssistEnabled = enabled;
        return this;
    }

    public EntityPlayerActionPack setGlideLaunchPitch(float pitchDeg)
    {
        glideLaunchPitchDeg = Mth.clamp(pitchDeg, -45.0F, 45.0F);
        return this;
    }

    public EntityPlayerActionPack setGlideLaunchSpeed(double speed)
    {
        glideLaunchSpeed = Math.max(0.0D, speed);
        return this;
    }

    public EntityPlayerActionPack setGlideLaunchForwardTicks(int ticks)
    {
        glideLaunchForwardTicks = Mth.clamp(ticks, 0, 20);
        return this;
    }

    public EntityPlayerActionPack setGlideSpeed(double blocksPerTick)
    {
        glideSpeed = Math.max(0.0D, blocksPerTick);
        return this;
    }

    public double getGlideSpeed()
    {
        return glideSpeed;
    }

    public EntityPlayerActionPack setGlideRates(float maxYawDegPerTick, float maxPitchDegPerTick)
    {
        glideYawRate = Math.max(0.0F, maxYawDegPerTick);
        glidePitchRate = Math.max(0.0F, maxPitchDegPerTick);
        return this;
    }

    public EntityPlayerActionPack setGlideUsePitchForForward(boolean usePitch)
    {
        glideUsePitchForForward = usePitch;
        return this;
    }

    public EntityPlayerActionPack setGlideInput(float forwardIn, float strafeIn, float upIn)
    {
        glideInputForward = Mth.clamp(forwardIn, -1.0F, 1.0F);
        glideInputStrafe = Mth.clamp(strafeIn, -1.0F, 1.0F);
        glideInputUp = Mth.clamp(upIn, -1.0F, 1.0F);
        glideMode = GlideMode.MANUAL;
        glideTargetPos = null;
        return this;
    }

    public EntityPlayerActionPack setGlideHeading(float yaw, float pitch)
    {
        glideTargetYaw = yaw;
        glideTargetPitch = pitch;
        glideMode = GlideMode.HEADING;
        glideTargetPos = null;
        return this;
    }

    public EntityPlayerActionPack setGlideGoto(Vec3 targetPos, double arrivalRadius)
    {
        glideTargetPos = targetPos;
        glideLandingTargetPos = null;
        glideWaypoints = null;
        glideWaypointIndex = 0;
        glideArrivalRadius = Math.max(0.0D, arrivalRadius);
        glideMode = GlideMode.GOTO;
        glideTakeoffRequested = true;
        glideTakeoffTimeoutTicks = 40;
        return this;
    }

    public EntityPlayerActionPack setGlideGotoWaypoints(List<Vec3> waypoints, Vec3 finalLandingTarget, double arrivalRadius)
    {
        if (waypoints == null || waypoints.isEmpty())
        {
            return setGlideGoto(finalLandingTarget, arrivalRadius);
        }
        glideWaypoints = new ArrayList<>(waypoints);
        glideWaypointIndex = 0;
        glideTargetPos = glideWaypoints.get(0);
        glideLandingTargetPos = finalLandingTarget;
        glideArrivalRadius = Math.max(0.0D, arrivalRadius);
        glideMode = GlideMode.GOTO;
        glideTakeoffRequested = true;
        glideTakeoffTimeoutTicks = 40;
        return this;
    }

    public EntityPlayerActionPack start(ActionType type, Action action)
    {
        if (action != null && action.isContinuous)
        {
            Action current = actions.get(type);
            // Only ignore if we're already running a continuous action of the same type.
            // If a one-shot or interval action is present, replace it.
            if (current != null && current.isContinuous)
            {
                return this;
            }
        }

        Action previous = actions.remove(type);
        if (previous != null) type.stop(player, previous);
        if (action != null)
        {
            actions.put(type, action);
            type.start(player, action); // noop
        }
        return this;
    }

    public EntityPlayerActionPack setSneaking(boolean doSneak)
    {
        sneaking = doSneak;
        player.setShiftKeyDown(doSneak);
//        if (sprinting && sneaking)
//            setSprinting(false);
        return this;
    }
    public EntityPlayerActionPack setSprinting(boolean doSprint)
    {
        sprinting = doSprint;
        player.setSprinting(doSprint);
//        if (sneaking && sprinting)
//            setSneaking(false);
        return this;
    }

    public EntityPlayerActionPack setForward(float value)
    {
        forward = value;
        return this;
    }
    public EntityPlayerActionPack setStrafing(float value)
    {
        strafing = value;
        return this;
    }
    public EntityPlayerActionPack look(Direction direction)
    {
        return switch (direction)
        {
            case NORTH -> look(180, 0);
            case SOUTH -> look(0, 0);
            case EAST  -> look(-90, 0);
            case WEST  -> look(90, 0);
            case UP    -> look(player.getYRot(), -90);
            case DOWN  -> look(player.getYRot(), 90);
        };
    }
    public EntityPlayerActionPack look(Vec2 rotation)
    {
        return look(rotation.x, rotation.y);
    }

    public EntityPlayerActionPack look(float yaw, float pitch)
    {
        float clampedPitch = Mth.clamp(pitch, -90, 90);
        float wrappedYaw = yaw % 360;

        player.setYRot(wrappedYaw);
        player.setXRot(clampedPitch);

        // Keep head/body in sync so clients render the direction correctly.
        player.setYHeadRot(wrappedYaw);
        player.setYBodyRot(wrappedYaw);

        syncFakePlayerRotation();
        return this;
    }

    public EntityPlayerActionPack lookAt(Vec3 position)
    {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, position);
        syncFakePlayerRotation();
        return this;
    }

    private void syncFakePlayerRotation()
    {
        if (!(player instanceof EntityPlayerMPFake))
        {
            return;
        }
        if (!(player.level() instanceof ServerLevel level))
        {
            return;
        }

        byte yawByte = (byte) Mth.floor(player.getYRot() * 256.0F / 360.0F);
        byte pitchByte = (byte) Mth.floor(player.getXRot() * 256.0F / 360.0F);
        byte headYawByte = (byte) Mth.floor(player.getYHeadRot() * 256.0F / 360.0F);

        level.getChunkSource().sendToTrackingPlayers(player, new ClientboundMoveEntityPacket.Rot(player.getId(), yawByte, pitchByte, player.onGround()));
        level.getChunkSource().sendToTrackingPlayers(player, new ClientboundRotateHeadPacket(player, headYawByte));
    }

    public EntityPlayerActionPack turn(float yaw, float pitch)
    {
        return look(player.getYRot() + yaw, player.getXRot() + pitch);
    }

    public EntityPlayerActionPack turn(Vec2 rotation)
    {
        return turn(rotation.x, rotation.y);
    }

    public EntityPlayerActionPack stopMovement()
    {
        setSneaking(false);
        setSprinting(false);
        forward = 0.0F;
        strafing = 0.0F;
        return this;
    }


    public EntityPlayerActionPack stopAll()
    {
        for (ActionType type : actions.keySet()) type.stop(player, actions.get(type));
        actions.clear();
        critAwaitingGroundAfterHit = false;
        critPostLandingDelay = 0;
        setGlideEnabled(false);
        stopNavigation();
        return stopMovement();
    }

    public EntityPlayerActionPack stopNavigation()
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

        if (navWaterJumping)
        {
            player.setJumping(false);
            navWaterJumping = false;
        }

        // If we were navigating via elytra, this also stops that controller.
        setGlideEnabled(false);
        return this;
    }

    public boolean isNavEnabled()
    {
        return navEnabled;
    }

    public BotNavMode getNavMode()
    {
        return navMode;
    }

    public Vec3 getNavTargetPos()
    {
        return navTargetPos;
    }

    public double getNavArrivalRadius()
    {
        return navArrivalRadius;
    }

    public void resetNavOptions()
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
    }

    public boolean setNavOption(String option, boolean value)
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
            default -> false;
        };
    }

    public boolean setNavOption(String option, int value)
    {
        if ("autoEatBelow".equals(option))
        {
            navAutoEatBelow = Mth.clamp(value, 0, 20);
            return true;
        }
        return false;
    }

    public EntityPlayerActionPack setNavGoto(Vec3 targetPos, BotNavMode mode, double arrivalRadius)
    {
        navEnabled = true;
        navMode = mode == null ? BotNavMode.AUTO : mode;
        navTargetPos = targetPos;
        navArrivalRadius = Math.max(0.0D, arrivalRadius);
        navAirArrival = NavAirArrival.LAND;
        navAirRequestedTargetPos = null;
        navNodes = null;
        navWaypoints = null;
        navWaypointIndex = 0;
        navNeedsRepath = true;
        navRepathCooldownTicks = 0;
        navNoProgressTicks = 0;
        navLastDistanceToNext = Double.POSITIVE_INFINITY;
        return this;
    }

    public EntityPlayerActionPack setNavGotoAir(Vec3 targetPos, double arrivalRadius, boolean landOnFloor)
    {
        navEnabled = true;
        navMode = BotNavMode.AIR;
        navArrivalRadius = Math.max(0.0D, arrivalRadius);
        navAirRequestedTargetPos = targetPos;
        navAirArrival = landOnFloor ? NavAirArrival.LAND : NavAirArrival.DROP;

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
        return this;
    }

    public EntityPlayerActionPack mount(boolean onlyRideables)
    {
        //test what happens
        List<Entity> entities;
        if (onlyRideables)
        {
            entities = player.level().getEntities(player, player.getBoundingBox().inflate(3.0D, 1.0D, 3.0D),
                    e -> e instanceof Minecart || e instanceof Boat || e instanceof AbstractHorse);
        }
            else
        {
            entities = player.level().getEntities(player, player.getBoundingBox().inflate(3.0D, 1.0D, 3.0D));
        }
        if (entities.size()==0)
            return this;
        Entity closest = null;
        double distance = Double.POSITIVE_INFINITY;
        Entity currentVehicle = player.getVehicle();
        for (Entity e: entities)
        {
            if (e == player || (currentVehicle == e))
                continue;
            double dd = player.distanceToSqr(e);
            if (dd<distance)
            {
                distance = dd;
                closest = e;
            }
        }
        if (closest == null) return this;
        if (closest instanceof AbstractHorse && onlyRideables)
            ((AbstractHorse) closest).mobInteract(player, InteractionHand.MAIN_HAND);
        else
            player.startRiding(closest);
        return this;
    }
    public EntityPlayerActionPack dismount()
    {
        player.stopRiding();
        return this;
    }

    public void onUpdate()
    {
        if (maybeAutoEat())
        {
            stopMovement();
            return;
        }

        Map<ActionType, Boolean> actionAttempts = new HashMap<>();
        actions.values().removeIf(e -> e.done);
        for (Map.Entry<ActionType, Action> e : actions.entrySet())
        {
            ActionType type = e.getKey();
            Action action = e.getValue();
            // skipping attack if use was successful
            if (!(actionAttempts.getOrDefault(ActionType.USE, false) && type == ActionType.ATTACK))
            {
                Boolean actionStatus = action.tick(this, type);
                if (actionStatus != null)
                    actionAttempts.put(type, actionStatus);
            }
            // optionally retrying use after successful attack and unsuccessful use
            if (type == ActionType.ATTACK
                    && actionAttempts.getOrDefault(ActionType.ATTACK, false)
                    && !actionAttempts.getOrDefault(ActionType.USE, true) )
            {
                // according to MinecraftClient.handleInputEvents
                Action using = actions.get(ActionType.USE);
                if (using != null) // this is always true - we know use worked, but just in case
                {
                    using.retry(this, ActionType.USE);
                }
            }
        }

        tickNavigation();

        tickGlide();

        float vel = sneaking?0.3F:1.0F;
        vel *= player.isUsingItem()?0.20F:1.0F;
        // The != 0.0F checks are needed given else real players can't control minecarts, however it works with fakes and else they don't stop immediately
        if (glideEnabled)
        {
            player.zza = 0.0F;
            player.xxa = 0.0F;
        }
        else
        {
            if (forward != 0.0F || player instanceof EntityPlayerMPFake) {
                player.zza = forward * vel;
            }
            if (strafing != 0.0F || player instanceof EntityPlayerMPFake) {
                player.xxa = strafing * vel;
            }
        }
    }

    private void tickGlide()
    {
        if (!glideEnabled)
        {
            if (glidePrevNoGravity != null)
            {
                player.setNoGravity(glidePrevNoGravity);
                glidePrevNoGravity = null;
            }
            return;
        }
        if (!CarpetSettings.fakePlayerElytraGlide)
        {
            setGlideEnabled(false);
            return;
        }
        if (!(player instanceof EntityPlayerMPFake))
        {
            // Safety: only drive bots.
            setGlideEnabled(false);
            return;
        }
        if (player.isSpectator())
        {
            return;
        }

        // If we already deployed elytra and are now on the ground, stop gliding.
        // This prevents "bounce" / redeploy spam when touching down.
        if (glideHasDeployed && player.onGround() && !player.isFallFlying())
        {
            setGlideEnabled(false);
            return;
        }

        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || chest.nextDamageWillBreak())
        {
            // Can't glide without a usable elytra.
            setGlideEnabled(false);
            return;
        }

        // Deploy elytra like a normal player would: jump, then "double tap" to deploy.
        // Server-side, that corresponds to calling tryToStartFallFlying() once airborne.
        if (!player.isFallFlying())
        {
            if (player.onGround())
            {
                // Only auto-takeoff when we're navigating somewhere (goto). Plain `glide start` should not spam-jump.
                if (!glideTakeoffRequested)
                {
                    return;
                }
                if (glideTakeoffTimeoutTicks-- <= 0)
                {
                    setGlideEnabled(false);
                    return;
                }
                // Initiate takeoff once; after jumping, wait until we become airborne.
                if (glideDeployAttempts == 0 && glideDeployDelayTicks == 0)
                {
                    // Aim towards current navigation target before jumping and apply launch pitch.
                    float yawToTarget = player.getYRot();
                    Vec3 aimPos = glideTargetPos;
                    if (glideWaypoints != null && glideWaypointIndex < (glideWaypoints.size()))
                    {
                        aimPos = glideWaypoints.get(glideWaypointIndex);
                    }
                    if (aimPos != null)
                    {
                        Vec2 rot = rotationsTowards(player.getEyePosition(1.0F), aimPos);
                        yawToTarget = rot.x;
                    }
                    look(stepYaw(player.getYRot(), yawToTarget, glideYawRate), glideLaunchPitchDeg);
                    player.jumpFromGround();
                    glideDeployDelayTicks = 1;
                    glideDeployAttempts = 0;
                    glideLaunchTicksRemaining = glideLaunchAssistEnabled ? glideLaunchForwardTicks : 0;
                }
                return;
            }
            if (glideDeployDelayTicks > 0)
            {
                glideDeployDelayTicks--;
                // While airborne and not yet fall-flying, apply a horizontal boost to help deployment.
                if (glideLaunchTicksRemaining > 0 && glideLaunchAssistEnabled)
                {
                    glideLaunchTicksRemaining--;
                    float yaw = player.getYRot();
                    Vec3 forwardHorizontal = directionFromRotation(0.0F, yaw);
                    Vec3 current = player.getDeltaMovement();
                    Vec3 boosted = new Vec3(forwardHorizontal.x * glideLaunchSpeed, current.y, forwardHorizontal.z * glideLaunchSpeed);
                    player.setDeltaMovement(boosted);
                }
                return;
            }
            if (glideDeployAttempts < 20)
            {
                glideDeployAttempts++;
                player.tryToStartFallFlying();
            }
            if (!player.isFallFlying())
            {
                // Don't apply glide velocity until elytra is actually deployed.
                return;
            }
        }

        glideHasDeployed = true;
        glideTakeoffRequested = false;

        if (glideFrozen)
        {
            if (glidePrevNoGravity == null)
            {
                glidePrevNoGravity = player.isNoGravity();
                player.setNoGravity(true);
            }
            player.setDeltaMovement(Vec3.ZERO);
            return;
        }
        else if (glidePrevNoGravity != null)
        {
            player.setNoGravity(glidePrevNoGravity);
            glidePrevNoGravity = null;
        }

        float desiredYaw = player.getYRot();
        float desiredPitch = player.getXRot();

        if (glideMode == GlideMode.HEADING)
        {
            desiredYaw = glideTargetYaw;
            desiredPitch = glideTargetPitch;
        }
        else if (glideMode == GlideMode.GOTO && glideTargetPos != null)
        {
            Vec3 from = player.getEyePosition(1.0F);
            Vec3 to = glideTargetPos;
            double dx = player.getX() - glideTargetPos.x;
            double dz = player.getZ() - glideTargetPos.z;
            double distSqXZ = dx * dx + dz * dz;
            if (distSqXZ <= glideArrivalRadius * glideArrivalRadius)
            {
                if (glideWaypoints != null)
                {
                    // Advance to next waypoint.
                    glideWaypointIndex++;
                    if (glideWaypointIndex < glideWaypoints.size())
                    {
                        glideTargetPos = glideWaypoints.get(glideWaypointIndex);
                        return;
                    }
                    // Done with air path.
                    glideWaypoints = null;
                    glideTargetPos = null;
                    if (glideLandingTargetPos != null)
                    {
                        // Start landing onto the requested destination.
                        glideMode = GlideMode.LANDING;
                        return;
                    }

                    // No landing target: treat as final arrival and apply arrival action.
                    GlideArrivalAction action = glideArrivalAction;
                    if (glideFreezeAtTarget) action = GlideArrivalAction.FREEZE;
                    if (action == GlideArrivalAction.FREEZE)
                    {
                        setGlideFrozen(true);
                    }
                    else
                    {
                        setGlideEnabled(false);
                    }
                    return;
                }

                GlideArrivalAction action = glideArrivalAction;
                // Back-compat: if old flag was set, prefer freezing.
                if (glideFreezeAtTarget) action = GlideArrivalAction.FREEZE;
                if (action == GlideArrivalAction.FREEZE)
                {
                    setGlideFrozen(true);
                }
                else if (action == GlideArrivalAction.DESCEND)
                {
                    // Controlled descent: keep elytra deployed, but pitch down gently.
                    // This prevents stalling (pitching up / no forward speed) which looks like "just falling".
                    glideTargetPos = null;
                    glideLandingTargetPos = null;
                    glideMode = GlideMode.HEADING;
                    glideTargetYaw = player.getYRot();
                    glideTargetPitch = 20.0F;
                }
                else if (action == GlideArrivalAction.CIRCLE)
                {
                    // Keep circling/holding target.
                }
                else if (action == GlideArrivalAction.LAND)
                {
                    // Dive down at the target XZ and stop on ground.
                    glideLandingTargetPos = glideTargetPos;
                    glideTargetPos = null;
                    glideMode = GlideMode.LANDING;
                }
                else
                {
                    setGlideEnabled(false);
                }
                return;
            }
            Vec2 rot = rotationsTowards(from, to);
            desiredYaw = rot.x;
            desiredPitch = rot.y;
        }
        else if (glideMode == GlideMode.LANDING && glideLandingTargetPos != null)
        {
            // Aim towards the landing XZ and pitch down.
            Vec3 from = player.getEyePosition(1.0F);
            Vec3 to = new Vec3(glideLandingTargetPos.x, from.y, glideLandingTargetPos.z);
            Vec2 rot = rotationsTowards(from, to);
            desiredYaw = rot.x;
            desiredPitch = 80.0F;
        }

        float newYaw = stepYaw(player.getYRot(), desiredYaw, glideYawRate);
        float newPitch = stepAngle(player.getXRot(), desiredPitch, glidePitchRate);
        newPitch = Mth.clamp(newPitch, -90.0F, 90.0F);
        look(newYaw, newPitch);

        Vec3 thrust = computeThrust(newYaw, newPitch);
        if (thrust.lengthSqr() < 1.0E-8D)
        {
            player.setDeltaMovement(Vec3.ZERO);
            return;
        }
        Vec3 desiredVel = thrust.normalize().scale(glideSpeed);
        player.setDeltaMovement(desiredVel);
    }

    private boolean maybeAutoEat()
    {
        if (!(player instanceof EntityPlayerMPFake))
        {
            return false;
        }
        if (!navEnabled || !allowAutoEat())
        {
            return false;
        }
        FoodData foodData = player.getFoodData();
        if (foodData == null || foodData.getFoodLevel() > autoEatBelow())
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
        if (!stack.isEdible())
        {
            return false;
        }
        InteractionResult result = player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND);
        if (result.consumesAction())
        {
            itemUseCooldown = 3;
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
            if (stack.isEmpty() || !stack.isEdible()) continue;
            FoodProperties props = stack.getFoodProperties(player);
            if (props == null) continue;
            double saturation = props.getNutrition() * props.getSaturationModifier() * 2.0D;
            double score = saturation + props.getNutrition();
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

    private boolean selectBestToolFor(BlockState state)
    {
        if (!allowAutoTool()) return false;
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

    private boolean tryBreakBlock(BlockPos pos, Direction side)
    {
        if (blockHitDelay > 0)
        {
            blockHitDelay--;
            return false;
        }

        BlockState state = player.level().getBlockState(pos);
        if (state.isAir())
        {
            currentBlock = null;
            return true;
        }

        selectBestToolFor(state);

        if (player.gameMode.getGameModeForPlayer().isCreative())
        {
            player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
            blockHitDelay = 5;
            return true;
        }

        if (currentBlock == null || !currentBlock.equals(pos))
        {
            if (currentBlock != null)
            {
                player.gameMode.handleBlockBreakAction(currentBlock, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
            }
            player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
            boolean notAir = !state.isAir();
            if (notAir && curBlockDamageMP == 0)
            {
                state.attack(player.level(), pos, player);
            }
            if (notAir && state.getDestroyProgress(player, player.level(), pos) >= 1)
            {
                currentBlock = null;
                return true;
            }
            currentBlock = pos;
            curBlockDamageMP = 0;
        }
        else
        {
            curBlockDamageMP += state.getDestroyProgress(player, player.level(), pos);
            if (curBlockDamageMP >= 1)
            {
                player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                currentBlock = null;
                blockHitDelay = 5;
                return true;
            }
            player.level().destroyBlockProgress(-1, pos, (int) (curBlockDamageMP * 10));
        }
        player.resetLastActionTime();
        player.swing(InteractionHand.MAIN_HAND);
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

    private boolean tryPlaceBridgeBlock(BlockPos targetFeet)
    {
        if (!allowPlaceBlocks()) return false;
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
            player.swing(InteractionHand.MAIN_HAND);
            itemUseCooldown = 3;
            return true;
        }
        return false;
    }

    private boolean isCobweb(BlockState state)
    {
        return state.is(Blocks.COBWEB);
    }

    private boolean isLava(BlockState state)
    {
        return state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.LAVA);
    }

    private boolean isFire(BlockState state)
    {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
    }

    private boolean isPowderSnow(BlockState state)
    {
        return state.is(Blocks.POWDER_SNOW);
    }

    private boolean tryBreakBlockingAhead()
    {
        BlockHitResult hit = Tracer.rayTraceBlocks(player, 1, 4.5D, false);
        if (hit == null) return false;
        BlockPos pos = hit.getBlockPos();
        BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) return false;
        return tryBreakBlock(pos, hit.getDirection());
    }

    private boolean navOpt(Boolean overrideValue, boolean ruleValue)
    {
        return overrideValue != null ? overrideValue : ruleValue;
    }

    private int navOpt(Integer overrideValue, int ruleValue)
    {
        return overrideValue != null ? overrideValue : ruleValue;
    }

    private boolean allowBreakBlocks()
    {
        return navOpt(navAllowBreakBlocks, CarpetSettings.fakePlayerNavBreakBlocks);
    }

    private boolean allowPlaceBlocks()
    {
        return navOpt(navAllowPlaceBlocks, CarpetSettings.fakePlayerNavPlaceBlocks);
    }

    private boolean allowAutoTool()
    {
        return navOpt(navAutoTool, CarpetSettings.fakePlayerNavAutoTool);
    }

    private boolean allowAutoEat()
    {
        return navOpt(navAutoEat, CarpetSettings.fakePlayerNavAutoEat);
    }

    private int autoEatBelow()
    {
        return navOpt(navAutoEatBelow, CarpetSettings.fakePlayerNavAutoEatBelow);
    }

    private boolean avoidLava()
    {
        return navOpt(navAvoidLava, CarpetSettings.fakePlayerNavAvoidLava);
    }

    private boolean avoidFire()
    {
        return navOpt(navAvoidFire, CarpetSettings.fakePlayerNavAvoidFire);
    }

    private boolean avoidCobwebs()
    {
        return navOpt(navAvoidCobwebs, CarpetSettings.fakePlayerNavAvoidCobwebs);
    }

    private boolean allowBreakCobwebs()
    {
        return navOpt(navBreakCobwebs, CarpetSettings.fakePlayerNavBreakCobwebs);
    }

    private boolean avoidPowderSnow()
    {
        return navOpt(navAvoidPowderSnow, CarpetSettings.fakePlayerNavAvoidPowderSnow);
    }

    private void tickNavigation()
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

        if (!CarpetSettings.fakePlayerNavigation)
        {
            stopNavigation();
            return;
        }

        if (!(player instanceof EntityPlayerMPFake))
        {
            stopNavigation();
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

        if (navTargetPos == null)
        {
            stopNavigation();
            return;
        }

        BlockPos feetPos = player.blockPosition();
        if (allowBreakCobwebs())
        {
            BlockState feetState = player.level().getBlockState(feetPos);
            BlockState headState = player.level().getBlockState(feetPos.above());
            if (isCobweb(feetState) || isCobweb(headState))
            {
                tryBreakBlock(isCobweb(headState) ? feetPos.above() : feetPos, Direction.UP);
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
                effectiveMode = (canElytra && CarpetSettings.fakePlayerElytraGlide) ? BotNavMode.AIR : BotNavMode.LAND;
            }
        }

        // Completion rules: air+LAND should keep running until we actually touch down.
        if (effectiveMode != BotNavMode.AIR)
        {
            if (player.position().distanceTo(navTargetPos) <= navArrivalRadius)
            {
                stopNavigation();
                stopMovement();
                return;
            }
        }
        else
        {
            if (navAirArrival == NavAirArrival.DROP)
            {
                if (player.position().distanceTo(navTargetPos) <= navArrivalRadius)
                {
                    // Stop gliding immediately; gravity takes over.
                    stopNavigation();
                    stopMovement();
                    return;
                }
            }
            else
            {
                // LAND: stop navigation when we have landed and glide controller has ended.
                if (player.onGround() && !player.isFallFlying() && !glideEnabled)
                {
                    stopNavigation();
                    stopMovement();
                    return;
                }
            }
        }

        if (effectiveMode == BotNavMode.AIR)
        {
            if (!CarpetSettings.fakePlayerElytraGlide)
            {
                stopNavigation();
                return;
            }

            if (navNeedsRepath && navRepathCooldownTicks <= 0)
            {
                navNeedsRepath = false;
                navRepathCooldownTicks = 20;

                BlockPos start = player.blockPosition();
                BlockPos goal = BlockPos.containing(navTargetPos);
                ElytraAStarPathfinder.Settings settings = ElytraAStarPathfinder.Settings.defaults();
                List<BlockPos> raw = NAV_ELYTRA_PATHFINDER.findPath((ServerLevel) player.level(), start, goal, settings);
                if (raw == null)
                {
                    stopNavigation();
                    return;
                }
                List<BlockPos> compressed = ElytraAStarPathfinder.compressWaypoints(raw, settings.waypointStride());
                List<Vec3> waypoints = new ArrayList<>(compressed.size());
                for (BlockPos p : compressed)
                {
                    waypoints.add(new Vec3(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D));
                }

                navWaypoints = waypoints;
                navWaypointIndex = 0;
                setGlideEnabled(true);
                if (navAirArrival == NavAirArrival.DROP)
                {
                    // No landing behavior: stop gliding after last waypoint.
                    setGlideArrivalAction(GlideArrivalAction.STOP);
                    setGlideGotoWaypoints(waypoints, null, navArrivalRadius);
                }
                else
                {
                    setGlideArrivalAction(GlideArrivalAction.LAND);
                    setGlideGotoWaypoints(waypoints, navTargetPos, navArrivalRadius);
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
                NavAStarPathfinder.Settings base = NavAStarPathfinder.Settings.defaults();
                NavAStarPathfinder.Settings settings = new NavAStarPathfinder.Settings(
                    base.maxExpanded(),
                    base.maxQueued(),
                    base.maxRangeXZ(),
                    base.maxRangeY(),
                    base.maxFall(),
                    base.maxStepUp(),
                    base.allowDiagonal(),
                    base.allowJumps(),
                    base.maxJumpLength(),
                    avoidLava(),
                    avoidFire(),
                    avoidPowderSnow(),
                    avoidCobwebs()
                );
            NavAStarPathfinder.Traversal traversal = (effectiveMode == BotNavMode.WATER) ? NavAStarPathfinder.Traversal.WATER : NavAStarPathfinder.Traversal.AMPHIBIOUS;
            List<BlockPos> raw = NAV_ASTAR.findPath((ServerLevel) player.level(), start, goal, traversal, settings);
            if (raw == null)
            {
                stopNavigation();
                return;
            }

            // Keep node-to-node fidelity so the follower can detect jump edges.
            navNodes = raw;

            List<Vec3> waypoints = new ArrayList<>(raw.size());
            for (BlockPos p : raw)
            {
                waypoints.add(new Vec3(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D));
            }
            navWaypoints = waypoints;
            navWaypointIndex = 0;
        }

        if (glideEnabled)
        {
            setGlideEnabled(false);
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
        if (avoidCobwebs() && !allowBreakCobwebs() && cobwebAhead)
        {
            navNeedsRepath = true;
            return;
        }
        if (avoidLava() && (isLava(nextState) || isLava(nextBelow)))
        {
            navNeedsRepath = true;
            return;
        }
        if (avoidFire() && (isFire(nextState) || isFire(nextBelow)))
        {
            navNeedsRepath = true;
            return;
        }
        if (avoidPowderSnow() && (isPowderSnow(nextState) || isPowderSnow(nextBelow)))
        {
            navNeedsRepath = true;
            return;
        }

        if (allowBreakCobwebs() && cobwebAhead && dist <= 2.0D)
        {
            tryBreakBlock(isCobweb(nextState) ? nextFeet : nextFeet.below(), Direction.UP);
            navNeedsRepath = true;
            return;
        }

        if (allowPlaceBlocks() && dist <= 1.6D)
        {
            if (tryPlaceBridgeBlock(nextFeet))
            {
                navNeedsRepath = true;
                return;
            }
        }
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
            if (allowBreakBlocks() && tryBreakBlockingAhead())
            {
                navNeedsRepath = true;
                return;
            }
            navNeedsRepath = true;
            return;
        }

        Vec2 rot = rotationsTowards(player.getEyePosition(1.0F), next);
        look(stepYaw(player.getYRot(), rot.x, 40.0F), player.getXRot());

        setSneaking(false);
        setSprinting(true);
        setForward(1.0F);
        setStrafing(0.0F);

        boolean wantUp = next.y > player.getY() + 0.2D;
        if (effectiveMode == BotNavMode.WATER)
        {
            boolean inWater = isInWaterish();
            if (inWater && wantUp)
            {
                player.setJumping(true);
                navWaterJumping = true;
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
                // A 2-block cardinal move is usually a planned gap-jump.
                needsPlannedJump = (dx + dz) >= 2 && (dx == 0 || dz == 0);
            }

            boolean shouldJump = wantUp || (needsPlannedJump && dist <= 1.35D);
            if (shouldJump && player.onGround() && navJumpCooldownTicks <= 0)
            {
                navJumpCooldownTicks = 8;
                start(ActionType.JUMP, Action.once());
            }
        }
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

    private Vec3 computeThrust(float yawDeg, float pitchDeg)
    {
        float forwardPitch = glideUsePitchForForward ? pitchDeg : 0.0F;
        Vec3 forwardVec = directionFromRotation(forwardPitch, yawDeg);
        Vec3 strafeVec = directionFromRotation(0.0F, yawDeg - 90.0F);
        Vec3 upVec = new Vec3(0.0D, 1.0D, 0.0D);

        Vec3 thrust = forwardVec.scale(glideInputForward)
                .add(strafeVec.scale(glideInputStrafe))
                .add(upVec.scale(glideInputUp));

        // In heading/goto modes, inputs act as modifiers; default forward=1 already.
        return thrust;
    }

    private static Vec3 directionFromRotation(float pitchDeg, float yawDeg)
    {
        float yawRad = yawDeg * ((float)Math.PI / 180.0F);
        float pitchRad = pitchDeg * ((float)Math.PI / 180.0F);
        float f = Mth.cos(pitchRad);
        return new Vec3(
                -Mth.sin(yawRad) * f,
                -Mth.sin(pitchRad),
                Mth.cos(yawRad) * f
        );
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

    private static float stepAngle(float current, float target, float maxStep)
    {
        float delta = target - current;
        float step = Mth.clamp(delta, -maxStep, maxStep);
        return current + step;
    }

    private enum GlideMode
    {
        MANUAL,
        HEADING,
        GOTO,
        LANDING
    }

    public enum GlideArrivalAction
    {
        STOP,
        FREEZE,
        DESCEND,
        LAND,
        CIRCLE
    }

    static HitResult getTarget(ServerPlayer player)
    {
        double blockReach = player.gameMode.isCreative() ? 5 : 4.5f;
        double entityReach = player.gameMode.isCreative() ? 5 : 3f;

        // Vanilla-like targeting with different reach for blocks vs entities:
        // - find the nearest block up to blockReach
        // - find the nearest entity up to entityReach, but do not allow selecting entities behind the block hit
        BlockHitResult blockHit = Tracer.rayTraceBlocks(player, 1, blockReach, false);
        double maxSqDist = entityReach * entityReach;
        if (blockHit != null)
        {
            maxSqDist = Math.min(maxSqDist, blockHit.getLocation().distanceToSqr(player.getEyePosition(1)));
        }
        EntityHitResult entityHit = Tracer.rayTraceEntities(player, 1, entityReach, maxSqDist);
        return entityHit == null ? blockHit : entityHit;
    }

    private void dropItemFromSlot(int slot, boolean dropAll)
    {
        Inventory inv = player.getInventory(); // getInventory;
        if (!inv.getItem(slot).isEmpty())
            player.drop(inv.removeItem(slot,
                    dropAll ? inv.getItem(slot).getCount() : 1
            ), false, true); // scatter, keep owner
    }

    public void drop(int selectedSlot, boolean dropAll)
    {
        Inventory inv = player.getInventory(); // getInventory;
        if (selectedSlot == -2) // all
        {
            for (int i = inv.getContainerSize(); i >= 0; i--)
                dropItemFromSlot(i, dropAll);
        }
        else // one slot
        {
            if (selectedSlot == -1)
                selectedSlot = inv.getSelectedSlot();
            dropItemFromSlot(selectedSlot, dropAll);
        }
    }

    public void setSlot(int slot)
    {
        player.getInventory().setSelectedSlot(slot-1);
        player.connection.send(new ClientboundSetHeldSlotPacket(slot-1));
    }

    public enum ActionType
    {
        USE(true)
        {
            @Override
            boolean execute(ServerPlayer player, Action action)
            {
                EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
                if (ap.itemUseCooldown > 0)
                {
                    ap.itemUseCooldown--;
                    return true;
                }
                if (player.isUsingItem())
                {
                    return true;
                }
                HitResult hit = getTarget(player);
                for (InteractionHand hand : InteractionHand.values())
                {
                    switch (hit.getType())
                    {
                        case BLOCK:
                        {
                            player.resetLastActionTime();
                            ServerLevel world = (ServerLevel) player.level();
                            BlockHitResult blockHit = (BlockHitResult) hit;
                            BlockPos pos = blockHit.getBlockPos();
                            Direction side = blockHit.getDirection();
                            if (pos.getY() < player.level().getMaxY() - (side == Direction.UP ? 1 : 0) && world.mayInteract(player, pos))
                            {
                                InteractionResult result = player.gameMode.useItemOn(player, world, player.getItemInHand(hand), hand, blockHit);
                                player.swing(hand);
                                if (result instanceof InteractionResult.Success success)
                                {
                                    ap.itemUseCooldown = 3;
                                    return true;
                                }
                            }
                            break;
                        }
                        case ENTITY:
                        {
                            player.resetLastActionTime();
                            EntityHitResult entityHit = (EntityHitResult) hit;
                            Entity entity = entityHit.getEntity();
                            boolean handWasEmpty = player.getItemInHand(hand).isEmpty();
                            boolean itemFrameEmpty = (entity instanceof ItemFrame) && ((ItemFrame) entity).getItem().isEmpty();
                            Vec3 relativeHitPos = entityHit.getLocation().subtract(entity.getX(), entity.getY(), entity.getZ());
                            if (entity.interactAt(player, relativeHitPos, hand).consumesAction())
                            {
                                ap.itemUseCooldown = 3;
                                return true;
                            }
                            // fix for SS itemframe always returns CONSUME even if no action is performed
                            if (player.interactOn(entity, hand).consumesAction() && !(handWasEmpty && itemFrameEmpty))
                            {
                                ap.itemUseCooldown = 3;
                                return true;
                            }
                            break;
                        }
                    }
                    ItemStack handItem = player.getItemInHand(hand);
                    if (player.gameMode.useItem(player, player.level(), handItem, hand).consumesAction())
                    {
                        ap.itemUseCooldown = 3;
                        return true;
                    }
                }
                return false;
            }

            @Override
            void inactiveTick(ServerPlayer player, Action action)
            {
                EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
                ap.itemUseCooldown = 0;
                player.releaseUsingItem();
            }
        },
        ATTACK(true) {
            @Override
            boolean execute(ServerPlayer player, Action action) {
                HitResult hit = getTarget(player);
                switch (hit.getType()) {
                    case ENTITY: {
                        EntityHitResult entityHit = (EntityHitResult) hit;
                        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();

                        if (ap.attackCritical)
                        {
                            // After a successful crit hit, wait until we touch the ground,
                            // then wait the configured interval on-ground before starting the next jump.
                            if (ap.critAwaitingGroundAfterHit)
                            {
                                if (player.onGround())
                                {
                                    ap.critAwaitingGroundAfterHit = false;
                                    ap.critPostLandingDelay = Math.max(0, action.interval);
                                }
                                return false;
                            }
                            if (ap.critPostLandingDelay > 0)
                            {
                                if (player.onGround())
                                {
                                    ap.critPostLandingDelay--;
                                }
                                return false;
                            }
                            if (player.onGround())
                            {
                                player.jumpFromGround();
                                player.resetLastActionTime();
                                return false;
                            }
                            // Critical hits require falling (not rising)
                            if (player.getDeltaMovement().y >= 0.0D)
                            {
                                return false;
                            }
                        }

                        if (!CarpetSettings.spamClickCombat)
                        {
                            // Prevent constant weak hits when spamming attacks in modern combat
                            if (player.getAttackStrengthScale(0.5F) < 0.9F)
                            {
                                return false;
                            }
                        }

                        player.attack(entityHit.getEntity());
                        player.swing(InteractionHand.MAIN_HAND);
                        player.resetAttackStrengthTicker();
                        player.resetLastActionTime();

                        if (ap.attackCritical)
                        {
                            ap.critAwaitingGroundAfterHit = true;
                        }
                        return true;
                    }
                    case BLOCK: {
                        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
                        if (ap.blockHitDelay > 0)
                        {
                            ap.blockHitDelay--;
                            return false;
                        }
                        BlockHitResult blockHit = (BlockHitResult) hit;
                        BlockPos pos = blockHit.getBlockPos();
                        Direction side = blockHit.getDirection();
                        if (player.blockActionRestricted(player.level(), pos, player.gameMode.getGameModeForPlayer())) return false;
                        if (ap.currentBlock != null && player.level().getBlockState(ap.currentBlock).isAir())
                        {
                            ap.currentBlock = null;
                            return false;
                        }
                        BlockState state = player.level().getBlockState(pos);
                        boolean blockBroken = false;
                        if (player.gameMode.getGameModeForPlayer().isCreative())
                        {
                            player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                            ap.blockHitDelay = 5;
                            blockBroken = true;
                        }
                        else  if (ap.currentBlock == null || !ap.currentBlock.equals(pos))
                        {
                            if (ap.currentBlock != null)
                            {
                                player.gameMode.handleBlockBreakAction(ap.currentBlock, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                            }
                            player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                            boolean notAir = !state.isAir();
                            if (notAir && ap.curBlockDamageMP == 0)
                            {
                                state.attack(player.level(), pos, player);
                            }
                            if (notAir && state.getDestroyProgress(player, player.level(), pos) >= 1)
                            {
                                ap.currentBlock = null;
                                //instamine??
                                blockBroken = true;
                            }
                            else
                            {
                                ap.currentBlock = pos;
                                ap.curBlockDamageMP = 0;
                            }
                        }
                        else
                        {
                            ap.curBlockDamageMP += state.getDestroyProgress(player, player.level(), pos);
                            if (ap.curBlockDamageMP >= 1)
                            {
                                player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                                ap.currentBlock = null;
                                ap.blockHitDelay = 5;
                                blockBroken = true;
                            }
                            player.level().destroyBlockProgress(-1, pos, (int) (ap.curBlockDamageMP * 10));

                        }
                        player.resetLastActionTime();
                        player.swing(InteractionHand.MAIN_HAND);
                        return blockBroken;
                    }
                }
                // MISS (air): still swing to mimic holding attack.
                // In modern combat, avoid spamming weak swings unless spam-click combat is enabled.
                if (!CarpetSettings.spamClickCombat && player.getAttackStrengthScale(0.5F) < 0.9F)
                {
                    return false;
                }
                player.swing(InteractionHand.MAIN_HAND);
                player.resetLastActionTime();
                return false;
            }

            @Override
            void inactiveTick(ServerPlayer player, Action action)
            {
                EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
                if (ap.currentBlock == null) return;
                player.level().destroyBlockProgress(-1, ap.currentBlock, -1);
                player.gameMode.handleBlockBreakAction(ap.currentBlock, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, Direction.DOWN, player.level().getMaxY(), -1);
                ap.currentBlock = null;
            }
        },
        JUMP(true)
        {
            @Override
            boolean execute(ServerPlayer player, Action action)
            {
                if (action.limit == 1)
                {
                    if (player.onGround()) player.jumpFromGround(); // onGround
                }
                else
                {
                    player.setJumping(true);
                }
                return false;
            }

            @Override
            void inactiveTick(ServerPlayer player, Action action)
            {
                player.setJumping(false);
            }
        },
        DROP_ITEM(true)
        {
            @Override
            boolean execute(ServerPlayer player, Action action)
            {
                player.resetLastActionTime();
                player.drop(false); // dropSelectedItem
                return false;
            }
        },
        DROP_STACK(true)
        {
            @Override
            boolean execute(ServerPlayer player, Action action)
            {
                player.resetLastActionTime();
                player.drop(true); // dropSelectedItem
                return false;
            }
        },
        SWAP_HANDS(true)
        {
            @Override
            boolean execute(ServerPlayer player, Action action)
            {
                player.resetLastActionTime();
                ItemStack itemStack_1 = player.getItemInHand(InteractionHand.OFF_HAND);
                player.setItemInHand(InteractionHand.OFF_HAND, player.getItemInHand(InteractionHand.MAIN_HAND));
                player.setItemInHand(InteractionHand.MAIN_HAND, itemStack_1);
                return false;
            }
        };

        public final boolean preventSpectator;

        ActionType(boolean preventSpectator)
        {
            this.preventSpectator = preventSpectator;
        }

        void start(ServerPlayer player, Action action) {}
        abstract boolean execute(ServerPlayer player, Action action);
        void inactiveTick(ServerPlayer player, Action action) {}
        void stop(ServerPlayer player, Action action)
        {
            inactiveTick(player, action);
        }
    }

    public static class Action
    {
        public boolean done = false;
        public final int limit;
        public final int interval;
        public final int offset;
        private int count;
        private int next;
        private final boolean isContinuous;
        private final boolean requiresSuccessToCount;

        private Action(int limit, int interval, int offset, boolean continuous, boolean requiresSuccessToCount)
        {
            this.limit = limit;
            this.interval = interval;
            this.offset = offset;
            next = interval + offset;
            isContinuous = continuous;
            this.requiresSuccessToCount = requiresSuccessToCount;
        }

        public static Action once()
        {
            return new Action(1, 1, 0, false, false);
        }

        public static Action onceUntilSuccess()
        {
            return new Action(1, 1, 0, false, true);
        }

        public static Action continuous()
        {
            return new Action(-1, 1, 0, true, false);
        }

        public static Action interval(int interval)
        {
            return new Action(-1, interval, 0, false, false);
        }

        public static Action intervalUntilSuccess(int interval)
        {
            return new Action(-1, interval, 0, false, true);
        }

        public static Action interval(int interval, int offset)
        {
            return new Action(-1, interval, offset, false, false);
        }

        Boolean tick(EntityPlayerActionPack actionPack, ActionType type)
        {
            next--;
            Boolean cancel = null;
            if (next <= 0)
            {
                if (interval == 1 && !isContinuous)
                {
                    // need to allow entity to tick, otherwise won't have effect (bow)
                    // actions are 20 tps, so need to clear status mid tick, allowing entities process it till next time
                    if (!type.preventSpectator || !actionPack.player.isSpectator())
                    {
                        type.inactiveTick(actionPack.player, this);
                    }
                }

                if (!type.preventSpectator || !actionPack.player.isSpectator())
                {
                    cancel = type.execute(actionPack.player, this);
                }

                boolean shouldCountThisAttempt = !requiresSuccessToCount || Boolean.TRUE.equals(cancel);
                if (requiresSuccessToCount)
                {
                    // Keep evaluating every tick until the action decides it's complete.
                    // (For critical attacks, we need per-tick updates to detect falling/landing reliably.)
                    next = 1;
                }
                if (shouldCountThisAttempt)
                {
                    count++;
                    if (count == limit)
                    {
                        type.stop(actionPack.player, null);
                        done = true;
                        return cancel;
                    }
                }

                if (!requiresSuccessToCount)
                {
                    next = interval;
                }
            }
            else
            {
                if (!type.preventSpectator || !actionPack.player.isSpectator())
                {
                    type.inactiveTick(actionPack.player, this);
                }
            }
            return cancel;
        }

        void retry(EntityPlayerActionPack actionPack, ActionType type)
        {
            //assuming action run but was unsuccesful that tick, but opportunity emerged to retry it, lets retry it.
            if (!type.preventSpectator || !actionPack.player.isSpectator())
            {
                type.execute(actionPack.player, this);
            }

            // retry() is only called in contexts where it should count as an attempt
            count++;
            if (count == limit)
            {
                type.stop(actionPack.player, null);
                done = true;
            }
        }
    }
}
