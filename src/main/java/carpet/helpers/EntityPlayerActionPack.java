package carpet.helpers;

import carpet.CarpetSettings;
import carpet.fakes.ServerPlayerInterface;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.PvpInitializer;
import carpet.script.utils.Tracer;
import carpet.pvp.nav.BotNavMode;
import carpet.pvp.nav.NavController;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
//? if >=26.3
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

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

    // PvP combat-AI: chance (0-100) for a chase-attack swing to deliberately miss.
    // Driven each tick by carpet.pvp.BotBrain; read at the chase-attack site below.
    public int botMissChancePercent = 0;

    // Timed movement: counts down ticks and stops movement when depleted.
    private int moveTicksRemaining = -1; // -1 = indefinite

    private boolean critAwaitingGroundAfterHit;
    private int critPostLandingDelay;
    private int critTargetEntityId = -1; // Entity ID of the target we're crit-attacking

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

    private final NavController nav;

    public EntityPlayerActionPack(ServerPlayer playerIn)
    {
        player = playerIn;
        nav = new NavController(this);
        stopAll();
    }

    public ServerPlayer getPlayer()
    {
        return player;
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
            critTargetEntityId = -1;
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

    /**
     * Set how many ticks movement lasts. -1 = indefinite (default).
     * After the timer expires, movement is automatically stopped.
     */
    public EntityPlayerActionPack setMoveDuration(int ticks)
    {
        moveTicksRemaining = ticks;
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
        // Vec2 from RotationArgument: x=pitch, y=yaw
        return look(rotation.y, rotation.x);
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
        // Vec2 from RotationArgument: x=pitch, y=yaw
        return turn(rotation.y, rotation.x);
    }

    public EntityPlayerActionPack stopMovement()
    {
        setSneaking(false);
        setSprinting(false);
        forward = 0.0F;
        strafing = 0.0F;
        moveTicksRemaining = -1;
        return this;
    }


    public EntityPlayerActionPack stopAll()
    {
        for (ActionType type : actions.keySet()) type.stop(player, actions.get(type));
        actions.clear();
        critAwaitingGroundAfterHit = false;
        critPostLandingDelay = 0;
        critTargetEntityId = -1;
        setGlideEnabled(false);
        stopNavigation();
        return stopMovement();
    }

    public EntityPlayerActionPack stopNavigation()
    {
        nav.stop();
        return this;
    }

    public boolean isNavEnabled()
    {
        return nav.isEnabled();
    }

    public boolean isNavSearching()
    {
        return nav.isSearchRunning();
    }

    public boolean isNavFollowingFlowField()
    {
        return nav.isFollowingFlowField();
    }

    public BotNavMode getNavMode()
    {
        return nav.getMode();
    }

    public Vec3 getNavTargetPos()
    {
        return nav.getTargetPos();
    }

    public double getNavArrivalRadius()
    {
        return nav.getArrivalRadius();
    }

    public void resetNavOptions()
    {
        nav.resetOptions();
    }

    public boolean setNavOption(String option, boolean value)
    {
        return nav.setOption(option, value);
    }

    public boolean setNavOption(String option, int value)
    {
        return nav.setOption(option, value);
    }

    public EntityPlayerActionPack setNavGoto(Vec3 targetPos, BotNavMode mode, double arrivalRadius)
    {
        nav.gotoTarget(targetPos, mode, arrivalRadius);
        return this;
    }

    public EntityPlayerActionPack setNavGotoAir(Vec3 targetPos, double arrivalRadius, boolean landOnFloor)
    {
        nav.gotoAir(targetPos, arrivalRadius, landOnFloor);
        return this;
    }

    public EntityPlayerActionPack setNavFollow(UUID targetUUID, double radius)
    {
        nav.follow(targetUUID, radius);
        return this;
    }

    public EntityPlayerActionPack setNavChase(UUID targetUUID, boolean crit, double attackRange, int attackInterval)
    {
        nav.chase(targetUUID, crit, attackRange, attackInterval);
        return this;
    }

    /**
     * Chases a target like {@link #setNavChase} but never attacks it: the caller decides when a click is
     * worth throwing, and so nothing can hit from behind its back.
     */
    public EntityPlayerActionPack setNavApproach(UUID targetUUID, double stopRange)
    {
        nav.chase(targetUUID, false, stopRange, 0, false);
        return this;
    }

    public EntityPlayerActionPack setNavMine(List<Block> targets, int radius, int maxCount)
    {
        nav.mine(targets, radius, maxCount);
        return this;
    }

    public EntityPlayerActionPack setNavCome(Vec3 senderPos, double arrivalRadius)
    {
        nav.come(senderPos, arrivalRadius);
        return this;
    }

    public EntityPlayerActionPack setNavPatrol(List<Vec3> waypoints, boolean loop)
    {
        nav.patrol(waypoints, loop);
        return this;
    }

    // Getters for status display.
    public UUID getNavFollowTarget() { return nav.getFollowTarget(); }
    public List<Block> getNavMineTargets() { return nav.getMineTargets(); }
    public int getNavMinedCount() { return nav.getMinedCount(); }
    public int getNavMineMaxCount() { return nav.getMineMaxCount(); }
    public List<Vec3> getNavPatrolWaypoints() { return nav.getPatrolWaypoints(); }
    public int getNavPatrolIndex() { return nav.getPatrolIndex(); }
    public boolean isNavPatrolLoop() { return nav.isPatrolLoop(); }
    public UUID getNavChaseTarget() { return nav.getChaseTarget(); }
    public boolean isNavChaseCrit() { return nav.isChaseCrit(); }
    public double getNavChaseAttackRange() { return nav.getChaseAttackRange(); }
    public int getNavChaseAttackInterval() { return nav.getChaseAttackInterval(); }

    /**
     * Reads every carpet rule navigation needs. The single place settings enter this subsystem.
     */
    private NavController.Settings navSettings()
    {
        return new NavController.Settings(
                CarpetSettings.fakePlayerNavigation,
                CarpetSettings.fakePlayerElytraGlide,
                CarpetSettings.fakePlayerNavBreakBlocks,
                CarpetSettings.fakePlayerNavPlaceBlocks,
                CarpetSettings.fakePlayerNavAutoTool,
                CarpetSettings.fakePlayerNavAutoEat,
                CarpetSettings.fakePlayerNavAutoEatBelow,
                CarpetSettings.fakePlayerNavAvoidLava,
                CarpetSettings.fakePlayerNavAvoidFire,
                CarpetSettings.fakePlayerNavAvoidCobwebs,
                CarpetSettings.fakePlayerNavBreakCobwebs,
                CarpetSettings.fakePlayerNavAvoidPowderSnow,
                CarpetSettings.fakePlayerNavAllowParkour,
                CarpetSettings.fakePlayerNavAllowPillar,
                CarpetSettings.fakePlayerNavAllowBreakThrough,
                CarpetSettings.fakePlayerNavAllowDescendMine,
                CarpetSettings.fakePlayerNavAllowSprint,
                CarpetSettings.fakePlayerNavMobAvoidance,
                CarpetSettings.fakePlayerNavMobAvoidanceRadius,
                CarpetSettings.fakePlayerNavMaxFallHeight,
                CarpetSettings.fakePlayerNavAvoidSoulSand,
                CarpetSettings.fakePlayerNavAllowOpenDoors,
                CarpetSettings.fakePlayerNavAllowOpenFenceGates,
                CarpetSettings.fakePlayerNavAllowSwimming,
                CarpetSettings.fakePlayerNavSearchBudget,
                CarpetSettings.fakePlayerNavSearchBudgetTotal
        );
    }

    public void setItemUseCooldown(int ticks)
    {
        itemUseCooldown = ticks;
    }

    // Block-breaking progress, shared with the ATTACK action and with navigation.
    public int getBlockHitDelay() { return blockHitDelay; }
    public void setBlockHitDelay(int delay) { blockHitDelay = delay; }
    public BlockPos getCurrentBlock() { return currentBlock; }
    public void setCurrentBlock(BlockPos pos) { currentBlock = pos; }
    public float getCurBlockDamageMP() { return curBlockDamageMP; }
    public void setCurBlockDamageMP(float damage) { curBlockDamageMP = damage; }

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
        // PvP combat-AI: let the bot brain pick targets / drive combat before normal action processing.
        if (player instanceof EntityPlayerMPFake fakeForBrain)
        {
            fakeForBrain.getBotBrain().tick();
        }

        NavController.Settings navSettings = navSettings();

        if (nav.maybeAutoEat(navSettings))
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

        nav.tick(navSettings);

        tickGlide();

        // Timed movement countdown
        if (moveTicksRemaining > 0)
        {
            moveTicksRemaining--;
            if (moveTicksRemaining <= 0)
            {
                moveTicksRemaining = -1;
                stopMovement();
            }
        }

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
            ), /*? if >=26.3 {*/true, Prediction.SERVER_ONLY/*?} else {*//*false, true*//*?}*/); // scatter, keep owner
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

    /**
     * The vanilla melee hit of {@link ActionType#ATTACK} against a target the caller has already
     * validated (reach, aim, charge), for callers that decide on their own when a click is a hit.
     */
    public boolean attackEntity(Entity target)
    {
        player.attack(target);
        //~ if >=26.3 'swing(InteractionHand.MAIN_HAND)' -> 'swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)'
        player.swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        player.resetAttackStrengthTicker();
        player.resetLastActionTime();
        return true;
    }

    /** Swings the main hand without hitting anything, as a client does when a click hits air. */
    public void swing()
    {
        //~ if >=26.3 'swing(InteractionHand.MAIN_HAND)' -> 'swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)'
        player.swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        player.resetLastActionTime();
    }

    public enum ActionType
    {
        USE(true)
        {
            @Override
            boolean execute(ServerPlayer player, Action action)
            {
                EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
                if (player.isUsingItem())
                {
                    // A sword held up blocks for as long as the item is in use, so the block window is
                    // kept open rather than running out while the bot is still holding it up.
                    PvpInitializer.startSwordBlock(player, player.getUsedItemHand());
                    return true;
                }
                if (ap.itemUseCooldown > 0)
                {
                    ap.itemUseCooldown--;
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
                                //~ if >=26.3 'swing(hand)' -> 'swingAndResetAttackStrength(hand, SwingAnimation.DEFAULT, false)'
                                player.swingAndResetAttackStrength(hand, SwingAnimation.DEFAULT, false);
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
                            //? if >=26.3 {
                            // client always consumes action with food on a horse, unless it's a skeleton horse.
                            boolean isFeedingHorse = entity instanceof AbstractHorse horse
                                    && horse.isAlive()
                                    && horse.isFood(player.getItemInHand(hand))
                                    && !(horse instanceof SkeletonHorse);
                            //?}
                            Vec3 relativeHitPos = entityHit.getLocation().subtract(entity.getX(), entity.getY(), entity.getZ());
//? if <26.1 {
/*                            if (entity.interactAt(player, relativeHitPos, hand).consumesAction())
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
*///?}
                            //? if >=26.1 {
                            if (entity.interact(player, hand, relativeHitPos).consumesAction()/*? if >=26.3 {*/ || isFeedingHorse/*?}*/)
                            {
                                ap.itemUseCooldown = 3;
                                return true;
                            }
                            //?}
                        }
                    }
                    ItemStack handItem = player.getItemInHand(hand);
                    if (player.gameMode.useItem(player, player.level(), handItem, hand).consumesAction())
                    {
                        PvpInitializer.startSwordBlock(player, hand);
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
                EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();

                // --- Chase mode: direct entity attack for 100% accuracy ---
                NavController nav = ap.nav;
                if (nav.isChasing())
                {
                    // Not in range — do nothing (no swing, no attack) except jump if
                    // close enough.
                    if (!nav.isChaseInRange())
                    {
                        if (ap.attackCritical && nav.isChaseInJumpRange() && player.onGround())
                        {
                            player.setSprinting(true);
                            player.jumpFromGround();
                            player.resetLastActionTime();
                        }

                        return false;
                    }
                    if (nav.getChaseTargetEntityId() == -1)
                    {
                        return false;
                    }

                    Entity chaseTarget = player.level().getEntity(nav.getChaseTargetEntityId());
                    if (chaseTarget == null || !chaseTarget.isAlive())
                    {
                        return false;
                    }

                    // Interval = ticks between successful hits.
                    if (nav.getChaseAttackCooldown() > 0)
                    {
                        nav.setChaseAttackCooldown(nav.getChaseAttackCooldown() - 1);
                    }

                    // When interval is set, it fully controls hit cadence.
                    // Only enforce weapon-cooldown readiness for interval=0 continuous mode.
                    boolean enforceWeaponCooldown = !CarpetSettings.spamClickCombat && nav.getChaseAttackInterval() <= 0;

                    if (ap.attackCritical)
                    {
                        // Timed crit jump-reset: wait out cooldown on ground,
                        // then start one jump shortly before the next hit window.
                        if (player.isSprinting()) player.setSprinting(false);

                        if (player.onGround())
                        {
                            if (nav.getChaseAttackCooldown() > NavController.CHASE_CRIT_JUMP_LEAD_TICKS)
                            {
                                return false;
                            }
                            player.jumpFromGround();
                            player.resetLastActionTime();
                            return false;
                        }

                        // Keep looking at target while airborne.
                        ap.lookAt(chaseTarget.position().add(0, chaseTarget.getBbHeight() * 0.5, 0));

                        // Wait until cooldown expires before allowing the hit.
                        if (nav.getChaseAttackCooldown() > 0) return false;

                        // Only attack on the way down with fallDistance > 0.
                        if (player.getDeltaMovement().y >= 0.0D) return false;
                        if (player.fallDistance <= 0.0F) return false;
                    }
                    else if (nav.getChaseAttackCooldown() > 0)
                    {
                        return false;
                    }

                    // Attack strength check for modern combat.
                    if (enforceWeaponCooldown && player.getAttackStrengthScale(0.5F) < 0.9F)
                    {
                        return false;
                    }

                    // PvP combat-AI realism: deliberately miss a fraction of swings.
                    if (ap.botMissChancePercent > 0
                            && player.getRandom().nextInt(100) < ap.botMissChancePercent)
                    {
                        nav.setChaseAttackCooldown(nav.getChaseAttackInterval());
                        return false;
                    }

                    // Direct attack — bypass ray trace for 100% accuracy.
                    player.attack(chaseTarget);
                    //~ if >=26.3 'swing(InteractionHand.MAIN_HAND)' -> 'swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)'
                    player.swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                    player.resetAttackStrengthTicker();
                    player.resetLastActionTime();
                    nav.setChaseAttackCooldown(nav.getChaseAttackInterval());
                    return true;
                }

                // --- Standard (non-chase) attack logic ---
                if (ap.attackCritical)
                {
                    // After a successful crit hit, wait until we touch the ground,
                    // then wait the configured interval on-ground before starting the next jump.
                    if (ap.critAwaitingGroundAfterHit)
                    {
                        if (player.onGround())
                        {
                            ap.critAwaitingGroundAfterHit = false;
                            ap.critPostLandingDelay = Math.max(3, action.interval);
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

                    // While in the air, look at the stored target entity so the ray trace will hit it.
                    if (ap.critTargetEntityId != -1 && !player.onGround())
                    {
                        Entity tracked = player.level().getEntity(ap.critTargetEntityId);
                        if (tracked != null && tracked.isAlive())
                        {
                            ap.lookAt(tracked.position().add(0, tracked.getBbHeight() * 0.5, 0));
                        }
                        else
                        {
                            ap.critTargetEntityId = -1;
                        }
                    }

                    if (player.onGround())
                    {
                        // Before jumping, find and store the target entity to track in the air.
                        HitResult preHit = getTarget(player);
                        if (preHit.getType() == HitResult.Type.ENTITY)
                        {
                            ap.critTargetEntityId = ((EntityHitResult) preHit).getEntity().getId();
                        }
                        // Disable sprinting — canCriticalAttack() requires !isSprinting().
                        if (player.isSprinting())
                        {
                            player.setSprinting(false);
                        }
                        player.jumpFromGround();
                        player.resetLastActionTime();
                        return false;
                    }
                    // Critical hits require falling (not rising)
                    if (player.getDeltaMovement().y >= 0.0D)
                    {
                        return false;
                    }
                    // Wait until fallDistance is positive so the mixin's canCriticalAttack check passes.
                    if (player.fallDistance <= 0.0F)
                    {
                        return false;
                    }
                    // Disable sprinting right before the attack for the mixin check.
                    if (player.isSprinting())
                    {
                        player.setSprinting(false);
                    }
                }

                HitResult hit = getTarget(player);
                switch (hit.getType()) {
                    case ENTITY: {
                        EntityHitResult entityHit = (EntityHitResult) hit;

                        // PvP combat-AI realism: deliberately miss a fraction of swings
                        // (also covers the ray-trace attack path, not just the chase path).
                        if (ap.botMissChancePercent > 0
                                && player.getRandom().nextInt(100) < ap.botMissChancePercent)
                        {
                            return false;
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
                        //~ if >=26.3 'swing(InteractionHand.MAIN_HAND)' -> 'swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)'
                        player.swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                        player.resetAttackStrengthTicker();
                        player.resetLastActionTime();

                        if (ap.attackCritical)
                        {
                            ap.critAwaitingGroundAfterHit = true;
                            ap.critTargetEntityId = -1;
                        }
                        return true;
                    }
                    case BLOCK: {
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
                        //~ if >=26.3 'swing(InteractionHand.MAIN_HAND)' -> 'swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)'
                        player.swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                        return blockBroken;
                    }
                }
                // MISS (air): still swing to mimic holding attack.
                // In crit mode, suppress swings on miss to avoid spam while airborne.
                if (ap.attackCritical)
                {
                    return false;
                }
                // In modern combat, avoid spamming weak swings unless spam-click combat is enabled.
                if (!CarpetSettings.spamClickCombat && player.getAttackStrengthScale(0.5F) < 0.9F)
                {
                    return false;
                }
                //~ if >=26.3 'swing(InteractionHand.MAIN_HAND)' -> 'swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)'
                player.swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
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
        },
        SWING(true)
        {
            @Override
            boolean execute(ServerPlayer player, Action action)
            {
                // Plays the arm swing animation without performing any interaction.
                //~ if >=26.3 'swing(InteractionHand.MAIN_HAND)' -> 'swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)'
                player.swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                player.resetLastActionTime();
                return false;
            }
        },
        SWING_OFF_HAND(true)
        {
            @Override
            boolean execute(ServerPlayer player, Action action)
            {
                // The same arm animation with the other hand, which is what using the off hand looks like.
                //~ if >=26.3 'swing(InteractionHand.OFF_HAND)' -> 'swingAndResetAttackStrength(InteractionHand.OFF_HAND, SwingAnimation.DEFAULT, false)'
                player.swingAndResetAttackStrength(InteractionHand.OFF_HAND, SwingAnimation.DEFAULT, false);
                player.resetLastActionTime();
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
