package carpet.patches;

import net.minecraft.ChatFormatting;
import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.ProfileResolver;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import carpet.helpers.EntityPlayerActionPack;
import carpet.pvp.BotBrain;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.BotSettings;
import carpet.utils.DelayedTasks;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

@SuppressWarnings("EntityConstructor")
public class EntityPlayerMPFake extends ServerPlayer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityPlayerMPFake.class);
    private static final Set<String> spawning = new HashSet<>();
    private static final int DEFAULT_SHIELD_DISABLE_TICKS = 100;
    private static final double TICKS_PER_SECOND = 20.0D;
    private static final Method DISABLE_BLOCKING = disableBlockingMethod(4);
    private static final Method DISABLE_BLOCKING_WITH_ATTACKER = disableBlockingMethod(5);

    private final EntityPlayerActionPack actionPack;

    public Runnable fixStartingPosition = () -> {};
    public boolean isAShadow;
    public Vec3 spawnPos;
    public double spawnYaw;

    // PvP combat-AI state (lazily initialised so it also covers shadow players).
    private BotPvpConfig pvpConfig;
    private BotBrain botBrain;
    /** Knockback a hit handed us, waiting for the next tick to apply. See {@link #setPendingKnockback}. */
    private Vec3 pendingKnockback;
    /** What this player's velocity was before the hit being processed, to tell knockback from a repeat. */
    private Vec3 velocityBeforeDamage;
    /** True from a hit on this player until the motion packet that carries its knockback, or its next tick. */
    private boolean knockbackDue;
    /** UUID of the most recent attacker, used by the combat-AI revenge logic. */
    public UUID lastAttackerUUID;
    /**
     * The game tick this fake player last died on, or {@link Long#MIN_VALUE} while it never has. A fake
     * player has no death screen and no dead flag, and it is put back a tick later, so this is what lets
     * whoever was fighting it see afterwards that the fight ended.
     */
    private long diedTick = Long.MIN_VALUE;
    /** Game-time tick at which {@link #lastAttackerUUID} last dealt damage. */
    public long lastAttackerTick;

    /** Per-bot PvP configuration, seeded from the global {@code /carpet} bot rules. */
    public BotPvpConfig getPvpConfig()
    {
        if (pvpConfig == null) pvpConfig = new BotPvpConfig();
        return pvpConfig;
    }

    /**
     * A hit on a fake player leaves it with no knockback of its own: vanilla sends the victim the
     * motion it was given and puts its velocity back, expecting a client to apply it. The fake
     * packet listener catches that motion packet and hands it here, and the next tick turns it into
     * real movement.
     */
    public void setPendingKnockback(Vec3 velocity)
    {
        pendingKnockback = velocity;
    }

    /** Remembers the velocity a hit is about to change, so the motion packet it sends can be read. */
    public void noteDamage()
    {
        velocityBeforeDamage = getDeltaMovement();
        knockbackDue = true;
    }

    /**
     * Whether the given velocity is one a hit on this player produced, rather than the one it started from.
     * Only the attacker's hit sends the victim its knockback and then puts the victim's velocity back, which
     * is the one case there is anything to keep for the next tick. Every other motion packet a player is sent
     * about itself repeats a velocity the server has already given it: a mace sends its own wielder one from
     * inside the smash, before the Wind Burst that follows has thrown it, and applying that a tick late would
     * take the burst away again.
     */
    public boolean isKnockback(Vec3 velocity)
    {
        boolean due = knockbackDue;
        knockbackDue = false;
        return due && (velocityBeforeDamage == null || !velocity.equals(velocityBeforeDamage));
    }

    /** The per-bot combat-AI driver, ticked each game tick from the action pack. */
    public BotBrain getBotBrain()
    {
        if (botBrain == null) botBrain = new BotBrain(this);
        return botBrain;
    }

    /** Resets this bot's PvP config back to the current global {@code /carpet} rule defaults. */
    public void resetPvpConfig()
    {
        pvpConfig = new BotPvpConfig();
    }
    
    // Equipment synchronization state caching
    private final Map<EquipmentSlot, ItemStack> lastSyncedEquipment = new HashMap<>();
    
    // Equipment persistence storage
    private final Map<EquipmentSlot, ItemStack> persistentEquipment = new HashMap<>();
    private static final Map<String, Map<EquipmentSlot, ItemStack>> globalEquipmentStorage = new HashMap<>();
    private double disableBlockingForSeconds = DEFAULT_SHIELD_DISABLE_TICKS / TICKS_PER_SECOND;
    private boolean disableBlockingForSecondsOverridden = false;

    public void setDisableBlockingForSeconds(double seconds)
    {
        if (!Double.isFinite(seconds))
        {
            return;
        }
        disableBlockingForSecondsOverridden = true;
        disableBlockingForSeconds = Math.max(0.0D, seconds);
    }

    public boolean hasDisableBlockingForSecondsOverride()
    {
        return disableBlockingForSecondsOverridden;
    }

    public int getDisableBlockingTicks()
    {
        long disableTicks = Math.round(disableBlockingForSeconds * TICKS_PER_SECOND);
        return (int) Mth.clamp(disableTicks, 0L, Integer.MAX_VALUE);
    }

    // Returns true if the spawn was started, false if the name cannot be spawned at all. On an online mode
    // server the profile is still coming, so the placement happens later, on the server thread.
    /** The form other mods call: problems are reported to the server console. */
    public static boolean createFake(String username, MinecraftServer server, Vec3 pos, double yaw, double pitch, ResourceKey<Level> dimensionId, GameType gamemode, boolean flying)
    {
        return createFake(username, server, server.createCommandSourceStack(), pos, yaw, pitch, dimensionId, gamemode, flying);
    }

    public static boolean createFake(String username, MinecraftServer server, CommandSourceStack source, Vec3 pos, double yaw, double pitch, ResourceKey<Level> dimensionId, GameType gamemode, boolean flying)
    {
        ServerLevel worldIn = server.getLevel(dimensionId);
        if (!server.usesAuthentication())
        {
            // Offline mode: the profile is built locally, so Mojang is never asked and the name stays as typed.
            if (!BotSettings.allowSpawningOfflinePlayers) return false;
            GameProfile profile = offlineProfile(username);
            return canPlace(profile, server, source) && placeFake(profile, server, worldIn, pos, yaw, pitch, dimensionId, gamemode, flying);
        }

        // Mark the name as spawning so that we do not try to spawn another player with the name while
        // the profile is being fetched - preventing multiple players spawning
        spawning.add(username);
        fetchGameProfile(server, username).whenCompleteAsync((p, t) -> {
            // Always remove the name, even if exception occurs
            spawning.remove(username);
            GameProfile profile = p == null ? null : p.orElse(null);
            if (profile == null)
            {
                if (!BotSettings.allowSpawningOfflinePlayers)
                {
                    source.sendSuccess(() -> Component.literal("Player " + username + " doesn't exist and cannot spawn in online mode. "
                            + "Turn the server offline or the allowSpawningOfflinePlayers on to spawn non-existing players")
                            .withStyle(ChatFormatting.RED), false);
                    return;
                }
                profile = offlineProfile(username);
            }
            if (!canPlace(profile, server, source)) return;
            try
            {
                placeFake(profile, server, worldIn, pos, yaw, pitch, dimensionId, gamemode, flying);
            }
            catch (Throwable e)
            {
                // A future swallows what its callback throws: without this the command reports a spawn
                // and nobody is ever told that the bot did not arrive.
                LOGGER.error("Could not spawn fake player {}", username, e);
                source.sendSuccess(() -> Component.literal("Could not spawn " + username + ": " + e
                        + ". The server log has the details").withStyle(ChatFormatting.RED), false);
            }
        }, server);
        return true;
    }

    private static GameProfile offlineProfile(String username)
    {
        return new GameProfile(UUIDUtil.createOfflinePlayerUUID(username), username);
    }

    /** The spawn checks that need the resolved profile: bans and the whitelist. */
    private static boolean canPlace(GameProfile profile, MinecraftServer server, CommandSourceStack source)
    {
        PlayerList players = server.getPlayerList();
        NameAndId nameAndId = new NameAndId(profile.id(), profile.name());
        if (players.getBans().isBanned(nameAndId))
        {
            source.sendSuccess(() -> Component.literal("Player " + nameAndId.name() + " is banned on this server")
                    .withStyle(ChatFormatting.RED), false);
            return false;
        }
        if (players.isUsingWhitelist() && players.isWhiteListed(nameAndId) && !Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
        {
            source.sendSuccess(() -> Component.literal("Whitelisted players can only be spawned by operators")
                    .withStyle(ChatFormatting.RED), false);
            return false;
        }
        return true;
    }

    /** Places the fake player in the world. Must run on the server thread. */
    private static boolean placeFake(GameProfile profile, MinecraftServer server, ServerLevel worldIn, Vec3 pos, double yaw, double pitch, ResourceKey<Level> dimensionId, GameType gamemode, boolean flying)
    {
        EntityPlayerMPFake instance = new EntityPlayerMPFake(server, worldIn, profile, ClientInformation.createDefault(), false);
        instance.fixStartingPosition = () -> instance.snapTo(pos.x, pos.y, pos.z, (float) yaw, (float) pitch);
        join(server, instance, profile, false);
        instance.teleportTo(worldIn, pos.x, pos.y, pos.z, Set.of(), (float) yaw, (float) pitch, true);
        instance.setHealth(20.0F);
        instance.unsetRemoved();
        instance.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6F);
        instance.gameMode.changeGameModeForPlayer(gamemode);
        instance.getAbilities().flying = flying;
        instance.spawnPos = pos;
        instance.spawnYaw = yaw;
        server.getPlayerList().broadcastAll(new ClientboundRotateHeadPacket(instance, (byte) (instance.yHeadRot * 256 / 360)), dimensionId);//instance.dimension);
        server.getPlayerList().broadcastAll(ClientboundEntityPositionSyncPacket.of(instance), dimensionId);//instance.dimension);
        //instance.world.getChunkManager(). updatePosition(instance);
        instance.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f); // show all model layers (incl. capes)

        // Restore equipment state if available (for server restart scenarios)
        instance.restoreEquipmentState();

        // Ensure equipment is synchronized to all clients
        instance.syncAllEquipmentToClients();
        return true;
    }

    /**
     * Puts the fake player into the player list and swaps the vanilla packet listener the join
     * created for the fake one, which drops the packets a client would have handled. The listener's
     * constructor is what assigns {@code player.connection}, so building it afterwards is enough.
     */
    private static void join(MinecraftServer server, EntityPlayerMPFake fake, GameProfile profile, boolean transferred)
    {
        FakeClientConnection connection = new FakeClientConnection(PacketFlow.SERVERBOUND);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, transferred);
        server.getPlayerList().placeNewPlayer(connection, fake, cookie);
        new NetHandlerPlayServerFake(server, connection, fake, cookie);
    }

    private static CompletableFuture<Optional<GameProfile>> fetchGameProfile(MinecraftServer server, String name) {
        ProfileResolver resolver = server.services().profileResolver();
        // The lookup blocks on the network, so it goes to the vanilla background pool instead of the server thread.
        return CompletableFuture.supplyAsync(() -> resolver.fetchByName(name), Util.nonCriticalIoPool());
    }

    public static EntityPlayerMPFake createShadow(MinecraftServer server, ServerPlayer player)
    {
        ((ServerLevel) player.level()).getServer().getPlayerList().remove(player);
        player.connection.disconnect(Component.translatable("multiplayer.disconnect.duplicate_login"));
        ServerLevel worldIn = (ServerLevel) player.level();
        GameProfile gameprofile = player.getGameProfile();
        EntityPlayerMPFake playerShadow = new EntityPlayerMPFake(server, worldIn, gameprofile, player.clientInformation(), true);
        playerShadow.setChatSession(player.getChatSession());
        join(server, playerShadow, gameprofile, true);

        playerShadow.setHealth(player.getHealth());
        playerShadow.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        playerShadow.gameMode.changeGameModeForPlayer(player.gameMode.getGameModeForPlayer());
        // The player's own action pack comes from Carpet's mixin, which only exists on Fabric, so the
        // caller hands it over once the shadow is in place.
        // this might create problems if a player logs back in...
        playerShadow.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6F);
        playerShadow.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, player.getEntityData().get(DATA_PLAYER_MODE_CUSTOMISATION));


        server.getPlayerList().broadcastAll(new ClientboundRotateHeadPacket(playerShadow, (byte) (player.yHeadRot * 256 / 360)), playerShadow.level().dimension());
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, playerShadow));
        //player.world.getChunkManager().updatePosition(playerShadow);
        playerShadow.getAbilities().flying = player.getAbilities().flying;
        
        // Save equipment state from the original player
        playerShadow.saveEquipmentState();
        
        // Ensure equipment is synchronized to all clients
        playerShadow.syncAllEquipmentToClients();
        return playerShadow;
    }

    public static EntityPlayerMPFake respawnFake(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation cli)
    {
        return new EntityPlayerMPFake(server, level, profile, cli, false);
    }

    public static boolean isSpawningPlayer(String username)
    {
        return spawning.contains(username);
    }

    /** Forgets the names whose profile lookup never came back, so they can be spawned again. */
    public static void forgetSpawningPlayers()
    {
        spawning.clear();
    }
    
    // Note: Equipment persistence across server restarts is not implemented
    // Equipment will be preserved during dimension changes and respawns within the same session

    private EntityPlayerMPFake(MinecraftServer server, ServerLevel worldIn, GameProfile profile, ClientInformation cli, boolean shadow)
    {
        super(server, worldIn, profile, cli);
        isAShadow = shadow;
        actionPack = new EntityPlayerActionPack(this);
    }

    /**
     * The fake player's own action pack. A real player gets one from Carpet's mixin into
     * {@link ServerPlayer}; a fake player owns it outright, so the same code works where there is no
     * mixin to add it. It is ticked from {@link #tick()}.
     */
    public EntityPlayerActionPack getActionPack()
    {
        return actionPack;
    }

    @Override
    public void forceSetRotation(float yaw, boolean relativeYaw, float pitch, boolean relativePitch)
    {
        float nextYaw = relativeYaw ? (this.getYRot() + yaw) : yaw;
        float nextPitch = relativePitch ? (this.getXRot() + pitch) : pitch;

        nextYaw = nextYaw % 360.0F;
        nextPitch = Mth.clamp(nextPitch, -90.0F, 90.0F);

        this.setYRot(nextYaw);
        this.setXRot(nextPitch);

        // keep rendering state consistent
        this.setYHeadRot(nextYaw);
        this.setYBodyRot(nextYaw);
        this.yHeadRotO = this.yHeadRot;
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();

        if (this.level() instanceof ServerLevel serverLevel)
        {
            byte yawByte = (byte) Mth.floor(this.getYRot() * 256.0F / 360.0F);
            byte pitchByte = (byte) Mth.floor(this.getXRot() * 256.0F / 360.0F);
            byte headYawByte = (byte) Mth.floor(this.getYHeadRot() * 256.0F / 360.0F);

            serverLevel.getChunkSource().sendToTrackingPlayers(this, new ClientboundMoveEntityPacket.Rot(this.getId(), yawByte, pitchByte, this.onGround()));
            serverLevel.getChunkSource().sendToTrackingPlayers(this, new ClientboundRotateHeadPacket(this, headYawByte));
        }
    }

    @Override
    public void onEquipItem(final EquipmentSlot slot, final ItemStack previous, final ItemStack stack)
    {
        super.onEquipItem(slot, previous, stack);
        
        // Log equipment changes for debugging
        String previousItemName = previous.isEmpty() ? "empty" : previous.getDisplayName().getString();
        String newItemName = stack.isEmpty() ? "empty" : stack.getDisplayName().getString();
        LOGGER.debug("Equipment changed for fake player {}: {} slot {} -> {}", 
            getName().getString(), slot.getName(), previousItemName, newItemName);
        
        // Force synchronization to all clients after equipment changes
        try {
            syncEquipmentToClients(slot, stack);
        } catch (Exception e) {
            LOGGER.error("Failed to sync equipment change for fake player {} in slot {}: {}", 
                getName().getString(), slot.getName(), e.getMessage(), e);
        }
    }

    /**
     * Synchronizes equipment changes to all nearby clients
     * Includes caching to prevent redundant sync operations
     */
    private void syncEquipmentToClients(EquipmentSlot slot, ItemStack stack) {
        try {
            // Check if equipment state has actually changed to prevent redundant updates
            ItemStack lastSynced = lastSyncedEquipment.get(slot);
            if (ItemStack.matches(lastSynced, stack)) {
                LOGGER.debug("Skipping redundant equipment sync for fake player {} in slot {} - no change detected", 
                    getName().getString(), slot.getName());
                return; // No change, skip sync
            }
            
            // Update cache
            lastSyncedEquipment.put(slot, stack.copy());
            
            // Count nearby players for logging
            int nearbyPlayerCount = this.level().getServer().getPlayerList().getPlayers().size();
            
            // Use the existing broadcast pattern to synchronize equipment changes
            // This forces a refresh of the fake player's appearance to all nearby players
            this.level().getServer().getPlayerList().broadcastAll(ClientboundEntityPositionSyncPacket.of(this), this.level().dimension());
            
            LOGGER.debug("Synced equipment change for fake player {} in slot {} to {} players", 
                getName().getString(), slot.getName(), nearbyPlayerCount);
                
        } catch (Exception e) {
            LOGGER.error("Failed to sync equipment for fake player {} in slot {}: {}", 
                getName().getString(), slot.getName(), e.getMessage(), e);
        }
    }

    /**
     * Forces a full equipment synchronization to all nearby clients
     * Useful for ensuring equipment state consistency
     */
    public void syncAllEquipmentToClients() {
        try {
            LOGGER.debug("Starting full equipment sync for fake player {}", getName().getString());
            
            int syncedSlots = 0;
            // Update cache for all equipment slots
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack currentItem = getItemBySlot(slot);
                lastSyncedEquipment.put(slot, currentItem.copy());
                if (!currentItem.isEmpty()) {
                    syncedSlots++;
                }
            }
            
            // Count nearby players for logging
            int nearbyPlayerCount = this.level().getServer().getPlayerList().getPlayers().size();
            
            // Force synchronization to all clients
            this.level().getServer().getPlayerList().broadcastAll(ClientboundEntityPositionSyncPacket.of(this), this.level().dimension());
            
            LOGGER.debug("Completed full equipment sync for fake player {} - {} equipped slots synced to {} players", 
                getName().getString(), syncedSlots, nearbyPlayerCount);
                
        } catch (Exception e) {
            LOGGER.error("Failed to sync all equipment for fake player {}: {}", getName().getString(), e.getMessage(), e);
        }
    }
    
    /**
     * Public method to trigger equipment synchronization from external code
     * Used by Scarpet inventory functions
     */
    public void syncEquipmentToClients() {
        try {
            LOGGER.debug("External equipment sync requested for fake player {}", getName().getString());
            syncAllEquipmentToClients();
            
            // Force full synchronization using the existing broadcast pattern
            this.level().getServer().getPlayerList().broadcastAll(ClientboundEntityPositionSyncPacket.of(this), this.level().dimension());
            
            LOGGER.debug("External equipment sync completed for fake player {}", getName().getString());
        } catch (Exception e) {
            LOGGER.error("Failed external equipment sync for fake player {}: {}", getName().getString(), e.getMessage(), e);
        }
    }
    
    /**
     * Saves current equipment state for persistence across dimension changes and respawns
     */
    public void saveEquipmentState() {
        try {
            String playerName = getName().getString();
            Map<EquipmentSlot, ItemStack> currentEquipment = new HashMap<>();
            
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack item = getItemBySlot(slot);
                if (!item.isEmpty()) {
                    currentEquipment.put(slot, item.copy());
                }
            }
            
            // Store in both instance and global storage
            persistentEquipment.clear();
            persistentEquipment.putAll(currentEquipment);
            globalEquipmentStorage.put(playerName, new HashMap<>(currentEquipment));
            
            LOGGER.debug("Saved equipment state for fake player {} - {} equipped items", 
                playerName, currentEquipment.size());
                
        } catch (Exception e) {
            LOGGER.error("Failed to save equipment state for fake player {}: {}", 
                getName().getString(), e.getMessage(), e);
        }
    }
    
    /**
     * Restores equipment state from persistent storage
     */
    public void restoreEquipmentState() {
        try {
            String playerName = getName().getString();
            Map<EquipmentSlot, ItemStack> savedEquipment = globalEquipmentStorage.get(playerName);
            
            if (savedEquipment == null || savedEquipment.isEmpty()) {
                LOGGER.debug("No saved equipment state found for fake player {}", playerName);
                return;
            }
            
            int restoredItems = 0;
            for (Map.Entry<EquipmentSlot, ItemStack> entry : savedEquipment.entrySet()) {
                EquipmentSlot slot = entry.getKey();
                ItemStack item = entry.getValue().copy();
                
                // Only restore if slot is currently empty to avoid overwriting new equipment
                if (getItemBySlot(slot).isEmpty()) {
                    setItemSlot(slot, item);
                    restoredItems++;
                    LOGGER.debug("Restored {} to slot {} for fake player {}", 
                        item.getDisplayName().getString(), slot.getName(), playerName);
                }
            }
            
            // Update persistent equipment cache
            persistentEquipment.clear();
            persistentEquipment.putAll(savedEquipment);
            
            // Sync equipment to clients after restoration
            if (restoredItems > 0) {
                syncAllEquipmentToClients();
                LOGGER.debug("Restored {} equipment items for fake player {}", restoredItems, playerName);
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to restore equipment state for fake player {}: {}", 
                getName().getString(), e.getMessage(), e);
        }
    }
    
    /**
     * Clears saved equipment state for this player
     */
    public void clearSavedEquipmentState() {
        try {
            String playerName = getName().getString();
            persistentEquipment.clear();
            globalEquipmentStorage.remove(playerName);
            LOGGER.debug("Cleared saved equipment state for fake player {}", playerName);
        } catch (Exception e) {
            LOGGER.error("Failed to clear equipment state for fake player {}: {}", 
                getName().getString(), e.getMessage(), e);
        }
    }
    
    // Note: NBT serialization/deserialization for server restart persistence is not implemented
    // Equipment persistence is handled through in-memory storage during the session

    @Override
    public void kill(ServerLevel level) {
        kill(Component.literal("Killed"));
    }

    public void kill(Component reason) {
        shakeOff();

        // A fake player has no client that could have been shown a death screen, so being killed takes it
        // off the server the same way a disconnect does. Dying in the world is a different thing and goes
        // through die(), which respawns the bot.
        this.fakePlayerDisconnect(reason);
    }

    /**
     * Removes every fake player when the server stops. Vanilla's shutdown asks each connection to
     * disconnect and then waits until no chunk tickets are left; a fake connection ignores that
     * request, so a fake player left in a level keeps its tickets and the server never stops.
     */
    public static void disconnectAll(MinecraftServer server) {
        Component reason = Component.translatable("multiplayer.disconnect.server_shutdown");
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (player instanceof EntityPlayerMPFake fake) {
                fake.shakeOff();
                fake.connection.onDisconnect(new DisconnectionDetails(reason));
            }
        }
    }

    public void fakePlayerDisconnect(Component reason) {
        MinecraftServer server = this.level().getServer();
        server.schedule(new TickTask(server.getTickCount(), () -> {
            // The server removes fake players itself when it stops; do not disconnect twice.
            if (server.getPlayerList().getPlayer(this.getUUID()) == this) {
                this.connection.onDisconnect(new DisconnectionDetails(reason));
            }
        }));
    }

    /**
     * A real player's position is whatever its client last reported, so the server leaves the counting of
     * its fall distance to the client's movement packets. A fake player has no client and nothing else
     * counts it, so the server is the authority for it and the game builds the counter itself. Paper
     * asks this question before counting, and without the answer the counter stays at zero: a bot dropped
     * off a ledge read no fall distance at all and took no fall damage.
     */
    @Override
    public boolean isClientAuthoritative()
    {
        return false;
    }

    @Override
    public void tick() {
        if (pendingKnockback != null)
        {
            setDeltaMovement(pendingKnockback);
            pendingKnockback = null;
        }
        knockbackDue = false;
        actionPack.onUpdate();
        if (this.level().getServer().getTickCount() % 10 == 0)
        {
            this.connection.resetPosition();
            ((net.minecraft.server.level.ServerLevel)this.level()).getChunkSource().move(this);
        }
        // A real player's known movement is the distance its client last reported it had moved, which is where
        // the game reads a player's speed from: a spear reads the closing speed of two fighters out of it. A
        // fake player has no client, so it reports the distance the server moved it by this tick. Not its
        // velocity: that already has the tick's friction taken off and reads a sprint as a little over half.
        Vec3 before = position();
        try
        {
            super.tick();
            this.doTick();
        }
        catch (NullPointerException ignored)
        {
            // happens with that paper port thingy - not sure what that would fix, but hey
            // the game not gonna crash violently.
        }
        setKnownMovement(position().subtract(before));


    }

    /** Vanilla keeps the shield's cooldown method, Paper's copy of it takes the attacker as well. */
    private static Method disableBlockingMethod(int parameterCount)
    {
        for (Method method : BlocksAttacks.class.getMethods())
        {
            if (method.getName().equals("disable") && method.getParameterCount() == parameterCount) return method;
        }
        return null;
    }

    private void shakeOff()
    {
        if (getVehicle() instanceof Player) stopRiding();
        for (Entity passenger : getIndirectPassengers())
        {
            if (passenger instanceof Player) passenger.stopRiding();
        }
    }

    @Override
    public void die(DamageSource cause) {
        shakeOff();
        // Avoid super.die() to prevent the internal "dead" flag from sticking and blocking future damage.
        //~ if >=26.3 'this.invulnerableTime = 0' -> 'this.setInvulnerableTime(0)'
        this.setInvulnerableTime(0);
        this.hurtTime = 0;
        this.deathTime = 0;
        this.setRemainingFireTicks(0);
        this.setDeltaMovement(0, 0, 0);
        this.setHealth(1.0F);
        this.setPose(Pose.STANDING);
        this.diedTick = this.level().getGameTime();

        this.level().getScoreboard().forAllObjectives(ObjectiveCriteria.DEATH_COUNT, this, ScoreAccess::increment);

        if (BotSettings.fakePlayerDropInventoryOnDeath) {
            this.dropFakePlayerLoot(this.level(), cause);
        }

        // Keep fake players in-world and immediately schedule a respawn.
        DelayedTasks.scheduleNextTick(this.level().getServer(), this::respawn);
    }

    /**
     * What a fake player drops when it dies and the rule asks for it. Not an override: Paper declares
     * its own {@code dropAllDeathLoot} that returns an event, so a method of that name here would be an
     * override of something that does not return what this one does.
     */
    private void dropFakePlayerLoot(ServerLevel serverLevel, DamageSource damageSource) {
        boolean keepInventory = serverLevel.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY);

        if (!keepInventory) {
            this.clearSavedEquipmentState();
            this.destroyVanishingCursedItems();
            this.getInventory().dropAll();
        }
    }

    protected void destroyVanishingCursedItems() {
        Inventory inventory = this.getInventory();
        for(int i = 0; i < inventory.getContainerSize(); ++i) {
            ItemStack itemStack = inventory.getItem(i);
            if (!itemStack.isEmpty() && EnchantmentHelper.has(itemStack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                inventory.removeItemNoUpdate(i);
            }
        }
    }
    
    /**
     * Handles equipment restoration after respawn based on game rules and settings
     */
    private void handleRespawnEquipment() {
        try {
            // Check game rules for equipment handling on death
            boolean keepInventory = ((net.minecraft.server.level.ServerLevel) this.level()).getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY);
            
            if (keepInventory) {
                // Restore all equipment if keepInventory is enabled
                restoreEquipmentState();
                LOGGER.debug("Restored all equipment for fake player {} after respawn (keepInventory=true)", 
                    getName().getString());
            } else {
                // For fake players, we might want to keep equipment for testing purposes
                // This can be controlled by a carpet setting in the future
                boolean keepFakePlayerEquipment = true; // Default to keeping equipment for testing
                
                if (keepFakePlayerEquipment) {
                    restoreEquipmentState();
                    LOGGER.debug("Restored equipment for fake player {} after respawn (fake player equipment persistence enabled)", 
                        getName().getString());
                } else {
                    // Clear equipment state if not keeping it
                    clearSavedEquipmentState();
                    LOGGER.debug("Cleared equipment for fake player {} after respawn (fake player equipment persistence disabled)", 
                        getName().getString());
                }
            }
            
        } catch (Exception e) {
            LOGGER.error("Error handling respawn equipment for fake player {}: {}", 
                getName().getString(), e.getMessage(), e);
        }
    }
    
    /**
     * Respawn method for fake players
     */
    public void respawn() {
        try {
            LOGGER.debug("Respawning fake player {}", getName().getString());
            resetDeathStateForRespawn();
            this.teleportTo(spawnPos.x, spawnPos.y, spawnPos.z);

            // Ensure equipment synchronization after respawn
            DelayedTasks.schedule(this.level().getServer(), 1, () -> {
                try {
                    syncAllEquipmentToClients();
                    LOGGER.debug("Equipment synchronized after respawn for fake player {}", getName().getString());
                } catch (Exception e) {
                    LOGGER.error("Failed to sync equipment after respawn for fake player {}: {}",
                        getName().getString(), e.getMessage(), e);
                }
            });

        } catch (Exception e) {
            LOGGER.error("Error during respawn for fake player {}: {}", getName().getString(), e.getMessage(), e);
        }
    }

    /** The game tick this fake player last died on, or {@link Long#MIN_VALUE} while it never has. */
    public long diedTick()
    {
        return diedTick;
    }

    /**
     * Resets death-related state so fake players can take damage after respawn.
     */
    private void resetDeathStateForRespawn() {
        // Ensure the entity is not flagged as removed from the world.
        this.unsetRemoved();
        //~ if >=26.3 'setInvulnerable' -> 'setPermanentlyInvulnerable'
        this.setPermanentlyInvulnerable(false);
        // Clear invulnerability and death timers that can block damage after death.
        //~ if >=26.3 'this.invulnerableTime = 0' -> 'this.setInvulnerableTime(0)'
        this.setInvulnerableTime(0);
        this.hurtTime = 0;
        this.deathTime = 0;
        // Reset core survival state.
        this.setHealth(20.0F);
        this.setAbsorptionAmount(0.0F);
        this.foodData = new FoodData();
        this.setAirSupply(this.getMaxAirSupply());
        this.setRemainingFireTicks(0);
        this.fallDistance = 0.0F;
    }

//    public void respawn()
//    {
//        this.setHealth(20);
//        this.foodData = new FoodData();
//        this.teleportTo(spawnPos.x, spawnPos.y, spawnPos.z);
//        this.connection.send(new ClientboundRespawnPacket(this.createCommonSpawnInfo(serverLevel()), (byte)3));
//    }

    public void stop()
    {

    }

    @Override
    public String getIpAddress()
    {
        return "127.0.0.1";
    }

    @Override
    public boolean allowsListing() {
        return BotSettings.allowListingFakePlayers;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        if (!BotSettings.fakePlayerFallDamage) {
            return;
        }
        super.checkFallDamage(y, onGround, state, pos);
    }

    @Override
    public ServerPlayer teleport(TeleportTransition teleportTransition) {
        try {
            LOGGER.debug("Starting dimension teleport for fake player {} from {} to {}", 
                getName().getString(), 
                this.level().dimension().identifier(),
                teleportTransition.newLevel().dimension().identifier());
            
            // Save equipment state before teleportation
            saveEquipmentState();
            
            ServerPlayer result = super.teleport(teleportTransition);
            
            if (wonGame) {
                ServerboundClientCommandPacket p = new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN);
                connection.handleClientCommand(p);
            }

            // Handle dimension change completion
            if (connection.player.isChangingDimension()) {
                connection.player.hasChangedDimension();
            }
            
            // Restore equipment state after teleportation if this is still the same player instance
            MinecraftServer server = this.level().getServer();
            if (result == this) {
                // Schedule equipment restoration to happen after teleportation is complete
                DelayedTasks.schedule(server, 2, () -> {
                    try {
                        restoreEquipmentState();
                        LOGGER.debug("Equipment restoration completed after dimension teleport for fake player {}", 
                            getName().getString());
                    } catch (Exception e) {
                        LOGGER.error("Failed to restore equipment after dimension teleport for fake player {}: {}", 
                            getName().getString(), e.getMessage(), e);
                    }
                });
            } else if (result instanceof EntityPlayerMPFake fakeResult) {
                // If a new instance was created, transfer equipment state
                DelayedTasks.schedule(server, 2, () -> {
                    try {
                        fakeResult.restoreEquipmentState();
                        LOGGER.debug("Equipment transferred to new instance after dimension teleport for fake player {}", 
                            getName().getString());
                    } catch (Exception e) {
                        LOGGER.error("Failed to transfer equipment to new instance after dimension teleport for fake player {}: {}", 
                            getName().getString(), e.getMessage(), e);
                    }
                });
            }
            
            return result;
            
        } catch (Exception e) {
            LOGGER.error("Error during dimension teleport for fake player {}: {}", 
                getName().getString(), e.getMessage(), e);
            return super.teleport(teleportTransition);
        }
    }

    @Override
    public boolean hurtServer(ServerLevel serverLevel, DamageSource source, float f) {
        // Record the attacker so the combat-AI revenge logic can retaliate.
        if (f > 0.0f && source.getEntity() instanceof LivingEntity attacker && attacker != this) {
            noteDamage();
            this.lastAttackerUUID = attacker.getUUID();
            this.lastAttackerTick = serverLevel.getGameTime();
        }
        // Everything else is the game's: a blocking item is applied by LivingEntity.hurtServer through
        // applyItemBlocking, which takes what the item blocked off the damage and carries on with the
        // rest, the hurt time, the knockback and the sounds that go with it, exactly as it does for a real
        // player. The rules this mod adds on purpose are elsewhere and apply to fake and real players
        // alike: swordBlockHitting in Player_swordBlockStateMixin, and shieldStunning, which clears the
        // invulnerability a disabled shield leaves behind on the next tick (Player_shieldStunMixin).
        return super.hurtServer(serverLevel, source, f);
    }

    public float applyItemBlocking(ServerLevel serverLevel, DamageSource damageSource, float f) {
        return super.applyItemBlocking(serverLevel, damageSource, f);
    }

    // A blocked hit knocks no fake player back (see blockedByItem), so the blocking item is disabled
    // here instead: with the vanilla duration of the weapon that hit, or with the bot's own duration.
//? if <26.1 {
/*    @Override
    protected void blockUsingItem(ServerLevel serverLevel, LivingEntity livingEntity)
    {
*///?} else {
    @Override
    protected void blockUsingItem(ServerLevel serverLevel, LivingEntity livingEntity, DamageSource damageSource, float f/*? if >=26.3 {*/, boolean fullyBlocked/*?}*/)
    {
//?}
        if (!this.hasDisableBlockingForSecondsOverride())
        {
//? if <26.1 {
/*            super.blockUsingItem(serverLevel, livingEntity);
*///?} else {
            super.blockUsingItem(serverLevel, livingEntity, damageSource, f/*? if >=26.3 {*/, fullyBlocked/*?}*/);
//?}
            return;
        }
        ItemStack blocking = this.getItemBlockingWith();
        BlocksAttacks blocksAttacks = blocking == null ? null : blocking.get(DataComponents.BLOCKS_ATTACKS);
        if (blocksAttacks == null || livingEntity.getSecondsToDisableBlocking() <= 0.0F) return;
        if (this.disableBlockingForSeconds <= 0.0F)
        {
            // Asked for no cooldown, but the hit still breaks the block.
            this.stopUsingItem();
            return;
        }
        disableBlocking(serverLevel, livingEntity, blocksAttacks, blocking);
    }

    /**
     * Turns the fake player's shield off for as long as its own rule says. Paper's copy of this
     * method takes the attacker as well, so the call goes through whichever overload the server has.
     */
    private void disableBlocking(ServerLevel serverLevel, LivingEntity attacker, BlocksAttacks blocksAttacks, ItemStack blocking)
    {
        float seconds = (float) this.disableBlockingForSeconds;
        try
        {
            if (DISABLE_BLOCKING_WITH_ATTACKER != null)
            {
                DISABLE_BLOCKING_WITH_ATTACKER.invoke(blocksAttacks, serverLevel, this, seconds, blocking, attacker);
            }
            else
            {
                DISABLE_BLOCKING.invoke(blocksAttacks, serverLevel, this, seconds, blocking);
            }
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("Could not put the shield on cooldown", e);
        }
    }

    // A blocked hit used to knock the fake player forward when it held a raised shield, which is not
    // what a client sees. Only the knockback is dropped: blockUsingItem still disables blocking.
    @Override
//? if <26.1 {
/*    protected void blockedByItem(LivingEntity livingEntity) {
    }
*///?} else {
    protected void blockedByItem(LivingEntity livingEntity, DamageSource damageSource, float f/*? if >=26.3 {*/, boolean fullyBlocked/*?}*/) {
    }
//?}
}
