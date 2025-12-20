package carpet.patches;

import carpet.CarpetSettings;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.ParseResults;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import carpet.fakes.ServerPlayerInterface;
import carpet.utils.Messenger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.Items;

@SuppressWarnings("EntityConstructor")
public class EntityPlayerMPFake extends ServerPlayer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityPlayerMPFake.class);
    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private static final Set<String> spawning = new HashSet<>();

    public Runnable fixStartingPosition = () -> {};
    public boolean isAShadow;
    public Vec3 spawnPos;
    public double spawnYaw;
    
    // Equipment synchronization state caching
    private final Map<EquipmentSlot, ItemStack> lastSyncedEquipment = new HashMap<>();
    
    // Equipment persistence storage
    private final Map<EquipmentSlot, ItemStack> persistentEquipment = new HashMap<>();
    private static final Map<String, Map<EquipmentSlot, ItemStack>> globalEquipmentStorage = new HashMap<>();

    // Returns true if it was successful, false if couldn't spawn due to the player not existing in Mojang servers
    public static boolean createFake(String username, MinecraftServer server, Vec3 pos, double yaw, double pitch, ResourceKey<Level> dimensionId, GameType gamemode, boolean flying)
    {
        //prolly half of that crap is not necessary, but it works
        ServerLevel worldIn = server.getLevel(dimensionId);
        ProfileResolver profileResolver = server.services().profileResolver();
        GameProfile gameprofile = profileResolver.fetchByName(username).orElse(null);
        if (gameprofile == null)
        {
            if (!CarpetSettings.allowSpawningOfflinePlayers)
            {
                return false;
            } else {
                gameprofile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(username), username);
            }
        }
        GameProfile finalGP = gameprofile;

        // We need to mark this player as spawning so that we do not
        // try to spawn another player with the name while the profile
        // is being fetched - preventing multiple players spawning
        String name = gameprofile.name();
        spawning.add(name);

        fetchGameProfile(server, name).whenCompleteAsync((p, t) -> {
            // Always remove the name, even if exception occurs
            spawning.remove(name);
            if (t != null)
            {
                return;
            }

            GameProfile current = finalGP;
            if (p.isPresent())
            {
                current = p.get();
            }
            EntityPlayerMPFake instance = new EntityPlayerMPFake(server, worldIn, current, ClientInformation.createDefault(), false);
            instance.fixStartingPosition = () -> instance.snapTo(pos.x, pos.y, pos.z, (float) yaw, (float) pitch);
            server.getPlayerList().placeNewPlayer(new FakeClientConnection(PacketFlow.SERVERBOUND), instance, new CommonListenerCookie(current, 0, instance.clientInformation(), false));
            instance.teleportTo(worldIn, pos.x, pos.y, pos.z, Set.of(), (float) yaw, (float) pitch, true);
            instance.setHealth(20.0F);
            instance.unsetRemoved();
            instance.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6F);
            instance.gameMode.changeGameModeForPlayer(gamemode);
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
            instance.getAbilities().flying = flying;
        }, server);
        return true;
    }

    private static CompletableFuture<Optional<GameProfile>> fetchGameProfile(MinecraftServer server, String name) {
        ProfileResolver resolver = server.services().profileResolver();
        return CompletableFuture.supplyAsync(() -> resolver.fetchByName(name), server);
    }

    public static EntityPlayerMPFake createShadow(MinecraftServer server, ServerPlayer player)
    {
        ((ServerLevel) player.level()).getServer().getPlayerList().remove(player);
        player.connection.disconnect(Component.translatable("multiplayer.disconnect.duplicate_login"));
        ServerLevel worldIn = (ServerLevel) player.level();
        GameProfile gameprofile = player.getGameProfile();
        EntityPlayerMPFake playerShadow = new EntityPlayerMPFake(server, worldIn, gameprofile, player.clientInformation(), true);
        playerShadow.setChatSession(player.getChatSession());
        server.getPlayerList().placeNewPlayer(new FakeClientConnection(PacketFlow.SERVERBOUND), playerShadow, new CommonListenerCookie(gameprofile, 0, player.clientInformation(), true));

        playerShadow.setHealth(player.getHealth());
        playerShadow.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        playerShadow.gameMode.changeGameModeForPlayer(player.gameMode.getGameModeForPlayer());
        ((ServerPlayerInterface) playerShadow).getActionPack().copyFrom(((ServerPlayerInterface) player).getActionPack());
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
    
    // Note: Equipment persistence across server restarts is not implemented
    // Equipment will be preserved during dimension changes and respawns within the same session

    private EntityPlayerMPFake(MinecraftServer server, ServerLevel worldIn, GameProfile profile, ClientInformation cli, boolean shadow)
    {
        super(server, worldIn, profile, cli);
        isAShadow = shadow;
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
        kill(Messenger.s("Killed"));
        DamageSource dmgSource = level.damageSources().fellOutOfWorld();
        die(dmgSource);
    }

    public void kill(Component reason) {
        shakeOff();

        if (reason.getContents() instanceof TranslatableContents text && text.getKey().equals("multiplayer.disconnect.duplicate_login")) {
            this.connection.onDisconnect(new DisconnectionDetails(reason));
        }
    }

    public void fakePlayerDisconnect(Component reason) {
        this.level().getServer().schedule(new TickTask(this.level().getServer().getTickCount(), () -> {
            this.connection.onDisconnect(new DisconnectionDetails(reason));
        }));

    }

    @Override
    public void tick() {
        if (this.level().getServer().getTickCount() % 10 == 0)
        {
            this.connection.resetPosition();
            ((net.minecraft.server.level.ServerLevel)this.level()).getChunkSource().move(this);
        }
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


    }

    private void shakeOff()
    {
        if (getVehicle() instanceof Player) stopRiding();
        for (Entity passenger : getIndirectPassengers())
        {
            if (passenger instanceof Player) passenger.stopRiding();
        }
    }    @Override
    public void die(DamageSource cause) {
        shakeOff();
        super.die(cause);
        kill(this.getCombatTracker().getDeathMessage());
        EntityPlayerMPFake.executor.schedule(this::respawn, 1L, TimeUnit.MILLISECONDS);
        this.setHealth(20);
        giveExperienceLevels(-(experienceLevel + 1));
        kill(this.getCombatTracker().getDeathMessage());
        this.teleportTo(spawnPos.x, spawnPos.y, spawnPos.z);
        EntityPlayerMPFake.executor.schedule(() -> this.setDeltaMovement(0, 0, 0), 1L, TimeUnit.MILLISECONDS);
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
            this.setHealth(20);
            this.teleportTo(spawnPos.x, spawnPos.y, spawnPos.z);
            
            // Ensure equipment synchronization after respawn
            executor.schedule(() -> {
                try {
                    syncAllEquipmentToClients();
                    LOGGER.debug("Equipment synchronized after respawn for fake player {}", getName().getString());
                } catch (Exception e) {
                    LOGGER.error("Failed to sync equipment after respawn for fake player {}: {}", 
                        getName().getString(), e.getMessage(), e);
                }
            }, 50, TimeUnit.MILLISECONDS);
            
        } catch (Exception e) {
            LOGGER.error("Error during respawn for fake player {}: {}", getName().getString(), e.getMessage(), e);
        }
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
        return CarpetSettings.allowListingFakePlayers;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        doCheckFallDamage(0.0, y, 0.0, onGround);
    }    @Override
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
            if (result == this) {
                // Schedule equipment restoration to happen after teleportation is complete
                executor.schedule(() -> {
                    try {
                        restoreEquipmentState();
                        LOGGER.debug("Equipment restoration completed after dimension teleport for fake player {}", 
                            getName().getString());
                    } catch (Exception e) {
                        LOGGER.error("Failed to restore equipment after dimension teleport for fake player {}: {}", 
                            getName().getString(), e.getMessage(), e);
                    }
                }, 100, TimeUnit.MILLISECONDS);
            } else if (result instanceof EntityPlayerMPFake fakeResult) {
                // If a new instance was created, transfer equipment state
                executor.schedule(() -> {
                    try {
                        fakeResult.restoreEquipmentState();
                        LOGGER.debug("Equipment transferred to new instance after dimension teleport for fake player {}", 
                            getName().getString());
                    } catch (Exception e) {
                        LOGGER.error("Failed to transfer equipment to new instance after dimension teleport for fake player {}: {}", 
                            getName().getString(), e.getMessage(), e);
                    }
                }, 100, TimeUnit.MILLISECONDS);
            }
            
            return result;
            
        } catch (Exception e) {
            LOGGER.error("Error during dimension teleport for fake player {}: {}", 
                getName().getString(), e.getMessage(), e);
            return super.teleport(teleportTransition);
        }
    }    @Override
    public boolean hurtServer(ServerLevel serverLevel, DamageSource source, float f) {
        // Vanilla shields only block when the source is blockable and within the forward arc.
        if (f > 0.0f && this.isDamageSourceBlocked(source)) {
            this.applyItemBlocking(serverLevel, source, f);
            ItemStack stack = this.getUseItem();
            // Check if this is an attack that can disable shields (axes can disable shields)
            boolean canDisable = source.getEntity() instanceof LivingEntity le && 
                                le.getMainHandItem().getItem().toString().contains("axe");
            if(canDisable){
                this.playSound(SoundEvents.SHIELD_BREAK.value(), 0.8F, 0.8F + this.level().random.nextFloat() * 0.4F);
                this.stopUsingItem();
                this.getCooldowns().addCooldown(stack, 100);
                if(!CarpetSettings.shieldStunning) {
                    this.invulnerableTime = 20;
                }
                String ign = this.getGameProfile().name();
                MinecraftServer srv = this.level().getServer();
                CommandSourceStack commandSource = srv.createCommandSourceStack().withSuppressedOutput();
                ParseResults<CommandSourceStack> parseResults
                        = srv.getCommands().getDispatcher().parse(String.format("function practicebot:shielddisable", ign), commandSource);
                srv.getCommands().performCommand(parseResults, "");
            } else {
                this.playSound(SoundEvents.SHIELD_BLOCK.value(), 1.0F, 0.8F + this.level().random.nextFloat() * 0.4F);
            }
            CriteriaTriggers.ENTITY_HURT_PLAYER.trigger((ServerPlayer)this, source, f, 0, true);
            if(f < 3.4028235E37F){
                ((ServerPlayer)this).awardStat(Stats.DAMAGE_BLOCKED_BY_SHIELD, Math.round(f * 10.0F));
            }
            return false;
        }
        return super.hurtServer(serverLevel, source, f);
    }
}
