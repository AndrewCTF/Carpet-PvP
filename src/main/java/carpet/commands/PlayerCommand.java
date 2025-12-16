package carpet.commands;

import carpet.helpers.EntityPlayerActionPack;
import carpet.helpers.EntityPlayerActionPack.Action;
import carpet.helpers.EntityPlayerActionPack.ActionType;
import carpet.CarpetSettings;
import carpet.fakes.ServerPlayerInterface;
import carpet.patches.EntityPlayerMPFake;
import carpet.utils.CommandHelper;
import carpet.utils.Messenger;
import carpet.utils.EquipmentSlotMapping;
import carpet.utils.ArmorSetDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.GameModeArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

public class PlayerCommand
{
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerCommand.class);
    
    // TODO: allow any order like execute
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext)
    {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("player")
                .requires((player) -> CommandHelper.canUseCommand(player, CarpetSettings.commandPlayer))
                .then(argument("player", StringArgumentType.word())
                        .suggests((c, b) -> suggest(getPlayerSuggestions(c.getSource()), b))
                        .then(literal("stop").executes(manipulation(EntityPlayerActionPack::stopAll)))
                        .then(makeActionCommand("use", ActionType.USE))
                        .then(makeActionCommand("jump", ActionType.JUMP))
                        .then(makeActionCommand("attack", ActionType.ATTACK))
                        .then(makeActionCommand("drop", ActionType.DROP_ITEM))
                        .then(makeDropCommand("drop", false))
                        .then(makeActionCommand("dropStack", ActionType.DROP_STACK))
                        .then(makeDropCommand("dropStack", true))
                        .then(makeActionCommand("swapHands", ActionType.SWAP_HANDS))
                        .then(literal("hotbar")
                                .then(argument("slot", IntegerArgumentType.integer(1, 9))
                                        .executes(c -> manipulate(c, ap -> ap.setSlot(IntegerArgumentType.getInteger(c, "slot"))))))
                        .then(literal("kill").executes(PlayerCommand::kill))
                        .then(literal("disconnect").executes(PlayerCommand::disconnect))
                        .then(literal("shadow"). executes(PlayerCommand::shadow))
                        .then(literal("mount").executes(manipulation(ap -> ap.mount(true)))
                                .then(literal("anything").executes(manipulation(ap -> ap.mount(false)))))
                        .then(literal("dismount").executes(manipulation(EntityPlayerActionPack::dismount)))
                        .then(makeEquipmentCommands(commandBuildContext))
                        .then(literal("unequip")
                                .then(argument("slot", StringArgumentType.word())
                                        .suggests((c, b) -> suggest(List.of("head", "helmet", "chest", "chestplate", "legs", "leggings", "feet", "boots", "mainhand", "weapon", "offhand", "shield"), b))
                                        .executes(PlayerCommand::unequipItem)))
                        .then(literal("equipment").executes(PlayerCommand::showEquipment))
                        .then(literal("sneak").executes(manipulation(ap -> ap.setSneaking(true))))
                        .then(literal("unsneak").executes(manipulation(ap -> ap.setSneaking(false))))
                        .then(literal("sprint").executes(manipulation(ap -> ap.setSprinting(true))))
                        .then(literal("unsprint").executes(manipulation(ap -> ap.setSprinting(false))))
                        .then(literal("look")
                                .then(literal("north").executes(manipulation(ap -> ap.look(Direction.NORTH))))
                                .then(literal("south").executes(manipulation(ap -> ap.look(Direction.SOUTH))))
                                .then(literal("east").executes(manipulation(ap -> ap.look(Direction.EAST))))
                                .then(literal("west").executes(manipulation(ap -> ap.look(Direction.WEST))))
                                .then(literal("up").executes(manipulation(ap -> ap.look(Direction.UP))))
                                .then(literal("down").executes(manipulation(ap -> ap.look(Direction.DOWN))))
                                .then(literal("at").then(argument("position", Vec3Argument.vec3())
                                        .executes(c -> manipulate(c, ap -> ap.lookAt(Vec3Argument.getVec3(c, "position"))))))
                                .then(argument("direction", RotationArgument.rotation())
                                        .executes(c -> manipulate(c, ap -> ap.look(RotationArgument.getRotation(c, "direction").getRotation(c.getSource())))))
                        ).then(literal("turn")
                                .then(literal("left").executes(manipulation(ap -> ap.turn(-90, 0))))
                                .then(literal("right").executes(manipulation(ap -> ap.turn(90, 0))))
                                .then(literal("back").executes(manipulation(ap -> ap.turn(180, 0))))
                                .then(argument("rotation", RotationArgument.rotation())
                                        .executes(c -> manipulate(c, ap -> ap.turn(RotationArgument.getRotation(c, "rotation").getRotation(c.getSource())))))
                        ).then(literal("move").executes(manipulation(EntityPlayerActionPack::stopMovement))
                                .then(literal("forward").executes(manipulation(ap -> ap.setForward(1))))
                                .then(literal("backward").executes(manipulation(ap -> ap.setForward(-1))))
                                .then(literal("left").executes(manipulation(ap -> ap.setStrafing(1))))
                                .then(literal("right").executes(manipulation(ap -> ap.setStrafing(-1))))
                        ).then(literal("spawn").executes(PlayerCommand::spawn)
                                .then(literal("in").requires((player) -> player.hasPermission(2))
                                        .then(argument("gamemode", GameModeArgument.gameMode())
                                        .executes(PlayerCommand::spawn)))
                                .then(literal("at").then(argument("position", Vec3Argument.vec3()).executes(PlayerCommand::spawn)
                                        .then(literal("facing").then(argument("direction", RotationArgument.rotation()).executes(PlayerCommand::spawn)
                                                .then(literal("in").then(argument("dimension", DimensionArgument.dimension()).executes(PlayerCommand::spawn)
                                                        .then(literal("in").requires((player) -> player.hasPermission(2))
                                                                .then(argument("gamemode", GameModeArgument.gameMode())
                                                                .executes(PlayerCommand::spawn)
                                                        )))
                                        )))
                                ))
                        )
                );
        dispatcher.register(command);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeActionCommand(String actionName, ActionType type)
    {
        return literal(actionName)
                .executes(manipulation(ap -> ap.start(type, Action.once())))
                .then(literal("once").executes(manipulation(ap -> ap.start(type, Action.once()))))
                .then(literal("continuous").executes(manipulation(ap -> ap.start(type, Action.continuous()))))
                .then(literal("interval").then(argument("ticks", IntegerArgumentType.integer(1))
                        .executes(c -> manipulate(c, ap -> ap.start(type, Action.interval(IntegerArgumentType.getInteger(c, "ticks")))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeDropCommand(String actionName, boolean dropAll)
    {
        return literal(actionName)
                .then(literal("all").executes(manipulation(ap -> ap.drop(-2, dropAll))))
                .then(literal("mainhand").executes(manipulation(ap -> ap.drop(-1, dropAll))))
                .then(literal("offhand").executes(manipulation(ap -> ap.drop(40, dropAll))))
                .then(argument("slot", IntegerArgumentType.integer(0, 40)).
                        executes(c -> manipulate(c, ap -> ap.drop(IntegerArgumentType.getInteger(c, "slot"), dropAll))));
    }

    private static Collection<String> getPlayerSuggestions(CommandSourceStack source)
    {
        //using s instead of @s because making it parse selectors properly was a pain in the ass
        Set<String> players = new LinkedHashSet<>(List.of("Steve", "Alex", "TheobaldTheBot", "s"));
        players.addAll(source.getOnlinePlayerNames());
        return players;
    }

    private static ServerPlayer getPlayer(CommandContext<CommandSourceStack> context)
    {
        String playerName = StringArgumentType.getString(context, "player");
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        //we can just use '/execute as' when we want proper target selectors
        if (playerName.equals("s") && source.isPlayer()) return source.getPlayer();

        return server.getPlayerList().getPlayerByName(playerName);
    }

    private static NameAndId nameAndId(GameProfile profile)
    {
        return new NameAndId(profile.id(), profile.name());
    }

    private static boolean cantManipulate(CommandContext<CommandSourceStack> context)
    {
        Player player = getPlayer(context);
        CommandSourceStack source = context.getSource();
        if (player == null)
        {
            Messenger.m(source, "r Can only manipulate existing players");
            return true;
        }
        Player sender = source.getPlayer();
        if (sender == null)
        {
            return false;
        }

        if (!source.getServer().getPlayerList().isOp(nameAndId(sender.getGameProfile())))
        {
            if (sender != player && !(player instanceof EntityPlayerMPFake))
            {
                Messenger.m(source, "r Non OP players can't control other real players");
                return true;
            }
        }
        return false;
    }

    private static boolean cantReMove(CommandContext<CommandSourceStack> context)
    {
        if (cantManipulate(context)) return true;
        Player player = getPlayer(context);
        if (player instanceof EntityPlayerMPFake) return false;
        Messenger.m(context.getSource(), "r Only fake players can be moved or killed");
        return true;
    }

    private static boolean cantSpawn(CommandContext<CommandSourceStack> context)
    {
        String playerName = StringArgumentType.getString(context, "player");
        MinecraftServer server = context.getSource().getServer();
        PlayerList manager = server.getPlayerList();

        if (EntityPlayerMPFake.isSpawningPlayer(playerName))
        {
            Messenger.m(context.getSource(), "r Player ", "rb " + playerName, "r  is currently logging on");
            return true;
        }
        if (manager.getPlayerByName(playerName) != null)
        {
            Messenger.m(context.getSource(), "r Player ", "rb " + playerName, "r  is already logged on");
            return true;
        }
        GameProfile profile = server.services().profileResolver().fetchByName(playerName).orElse(null);
        if (profile == null)
        {
            if (!CarpetSettings.allowSpawningOfflinePlayers)
            {
                Messenger.m(context.getSource(), "r Player "+playerName+" is either banned by Mojang, or auth servers are down. " +
                        "Banned players can only be summoned in Singleplayer and in servers in off-line mode.");
                return true;
            } else {
                profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(playerName), playerName);
            }
        }
        if (manager.getBans().isBanned(nameAndId(profile)))
        {
            Messenger.m(context.getSource(), "r Player ", "rb " + playerName, "r  is banned on this server");
            return true;
        }
        if (manager.isUsingWhitelist() && manager.isWhiteListed(nameAndId(profile)) && !context.getSource().hasPermission(2))
        {
            Messenger.m(context.getSource(), "r Whitelisted players can only be spawned by operators");
            return true;
        }
        return false;
    }

    private static int kill(CommandContext<CommandSourceStack> context) {
        if (cantReMove(context)) return 0;
        ServerPlayer player = getPlayer(context);
        player.kill((net.minecraft.server.level.ServerLevel) player.level());
        return 1;
    }

    private static int disconnect(CommandContext<CommandSourceStack> context) {
        Player player = getPlayer(context);
        if (player instanceof EntityPlayerMPFake)
        {
            ((EntityPlayerMPFake) player).fakePlayerDisconnect(Messenger.s(""));
            return 1;
        }
        Messenger.m(context.getSource(), "r Cannot disconnect real players");
        return 0;
    }

    @FunctionalInterface
    interface SupplierWithCSE<T>
    {
        T get() throws CommandSyntaxException;
    }

    private static <T> T getArgOrDefault(SupplierWithCSE<T> getter, T defaultValue) throws CommandSyntaxException
    {
        try
        {
            return getter.get();
        }
        catch (IllegalArgumentException e)
        {
            return defaultValue;
        }
    }

    private static int spawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        if (cantSpawn(context)) return 0;

        CommandSourceStack source = context.getSource();
        Vec3 pos = getArgOrDefault(
                () -> Vec3Argument.getVec3(context, "position"),
                source.getPosition()
        );
        Vec2 facing = getArgOrDefault(
                () -> RotationArgument.getRotation(context, "direction").getRotation(source),
                source.getRotation()
        );
        ResourceKey<Level> dimType = getArgOrDefault(
                () -> DimensionArgument.getDimension(context, "dimension").dimension(),
                source.getLevel().dimension()
        );
        GameType mode = GameType.CREATIVE;
        boolean flying = false;
        if (source.getEntity() instanceof ServerPlayer sender)
        {
            mode = sender.gameMode.getGameModeForPlayer();
            flying = sender.getAbilities().flying;
        }
        try {
            mode = GameModeArgument.getGameMode(context, "gamemode");
        } catch (IllegalArgumentException notPresent) {}

        if (mode == GameType.SPECTATOR)
        {
            // Force override flying to true for spectator players, or they will fell out of the world.
            flying = true;
        } else if (mode.isSurvival())
        {
            // Force override flying to false for survival-like players, or they will fly too
            flying = false;
        }
        String playerName = StringArgumentType.getString(context, "player");
        if (playerName.length() > maxNameLength(source.getServer()))
        {
            Messenger.m(source, "rb Player name: " + playerName + " is too long");
            return 0;
        }

        if (!Level.isInSpawnableBounds(BlockPos.containing(pos)))
        {
            Messenger.m(source, "rb Player " + playerName + " cannot be placed outside of the world");
            return 0;
        }
        boolean success = EntityPlayerMPFake.createFake(playerName, source.getServer(), pos, facing.y, facing.x, dimType, mode, flying);
        if (!success) {
            Messenger.m(source, "rb Player " + playerName + " doesn't exist and cannot spawn in online mode. " +
                    "Turn the server offline or the allowSpawningOfflinePlayers on to spawn non-existing players");
            return 0;
        };
        return 1;
    }

    private static int maxNameLength(MinecraftServer server)
    {
        return server.getPort() >= 0 ? SharedConstants.MAX_PLAYER_NAME_LENGTH : 40;
    }

    private static int manipulate(CommandContext<CommandSourceStack> context, Consumer<EntityPlayerActionPack> action)
    {
        if (cantManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        action.accept(((ServerPlayerInterface) player).getActionPack());
        return 1;
    }

    private static Command<CommandSourceStack> manipulation(Consumer<EntityPlayerActionPack> action)
    {
        return c -> manipulate(c, action);
    }

    private static int shadow(CommandContext<CommandSourceStack> context)
    {
        if (cantManipulate(context)) return 0;

        ServerPlayer player = getPlayer(context);
        if (player instanceof EntityPlayerMPFake)
        {
            Messenger.m(context.getSource(), "r Cannot shadow fake players");
            return 0;
        }
        if (((ServerLevel) player.level()).getServer().isSingleplayerOwner(nameAndId(player.getGameProfile()))) {
            Messenger.m(context.getSource(), "r Cannot shadow single-player server owner");
            return 0;
        }
 
        EntityPlayerMPFake.createShadow(((ServerLevel) player.level()).getServer(), player);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeEquipmentCommands(CommandBuildContext commandBuildContext)
    {
        return literal("equip")
                // /player <name> equip <armor_type> - equip full armor set
                .then(argument("armor_type", StringArgumentType.word())
                        .suggests((c, b) -> suggest(ArmorSetDefinition.ARMOR_SETS.keySet(), b))
                        .executes(PlayerCommand::equipArmorSet))
                // /player <name> equip <slot> <item> - equip specific item in slot
                .then(argument("slot", StringArgumentType.word())
                        .suggests((c, b) -> suggest(List.of("head", "helmet", "chest", "chestplate", "legs", "leggings", "feet", "boots", "mainhand", "weapon", "offhand", "shield"), b))
                        .then(argument("item", ItemArgument.item(commandBuildContext))
                                .executes(PlayerCommand::equipItem)));
    }

    private static int equipArmorSet(CommandContext<CommandSourceStack> context)
    {
        if (cantManipulate(context)) return 0;

        ServerPlayer player = getPlayer(context);
        String armorType = StringArgumentType.getString(context, "armor_type");
        CommandSourceStack source = context.getSource();

        // Enhanced parameter validation
        if (armorType == null || armorType.trim().isEmpty()) {
            Messenger.m(source, "r Armor type cannot be empty");
            Messenger.m(source, "r Usage: /player <name> equip <armor_type>");
            Messenger.m(source, "r Available armor types: " + String.join(", ", ArmorSetDefinition.ARMOR_SETS.keySet()));
            LOGGER.warn("Empty armor type provided for player {} by {}", player.getName().getString(), source.getTextName());
            return 0;
        }

        ArmorSetDefinition armorSet = ArmorSetDefinition.getArmorSet(armorType);
        if (armorSet == null)
        {
            Messenger.m(source, "r Unknown armor type: '" + armorType + "'");
            Messenger.m(source, "r Usage: /player <name> equip <armor_type>");
            Messenger.m(source, "r Available armor types: " + String.join(", ", ArmorSetDefinition.ARMOR_SETS.keySet()));
            Messenger.m(source, "r Example: /player Steve equip diamond");
            LOGGER.warn("Invalid armor type '{}' requested for player {} by {}", armorType, player.getName().getString(), source.getTextName());
            return 0;
        }

        LOGGER.debug("Equipping {} armor set on player {}", armorType, player.getName().getString());
        
        int equipped = 0;
        int failed = 0;
        for (Map.Entry<EquipmentSlot, String> entry : armorSet.getPieces().entrySet())
        {
            try
            {
                net.minecraft.world.item.Item item = source.getServer().registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ITEM)
                    .get(net.minecraft.resources.ResourceLocation.parse(entry.getValue()))
                    .map(net.minecraft.core.Holder::value)
                    .orElse(null);
                
                if (item != null)
                {
                    ItemStack itemStack = new ItemStack(item);
                    player.setItemSlot(entry.getKey(), itemStack);
                    equipped++;
                    LOGGER.debug("Successfully equipped {} in {} slot for player {}", entry.getValue(), entry.getKey().getName(), player.getName().getString());
                }
                else
                {
                    Messenger.m(source, "r Item not found in registry: " + entry.getValue());
                    LOGGER.error("Item '{}' not found in registry for armor set '{}' on player {}", entry.getValue(), armorType, player.getName().getString());
                    failed++;
                }
            }
            catch (Exception e)
            {
                Messenger.m(source, "r Failed to equip " + entry.getValue() + " in " + entry.getKey().getName() + " slot");
                Messenger.m(source, "r Error: " + e.getMessage());
                LOGGER.error("Failed to equip {} in {} slot for player {}: {}", entry.getValue(), entry.getKey().getName(), player.getName().getString(), e.getMessage(), e);
                failed++;
            }
        }

        if (equipped > 0)
        {
            Messenger.m(source, "g Successfully equipped " + equipped + " pieces of " + armorType + " armor on " + player.getName().getString());
            if (failed > 0) {
                Messenger.m(source, "y Warning: " + failed + " pieces failed to equip");
            }
            LOGGER.info("Equipped {} pieces of {} armor on player {} (requested by {})", equipped, armorType, player.getName().getString(), source.getTextName());
        }
        else if (failed > 0)
        {
            Messenger.m(source, "r Failed to equip any armor pieces. Check server logs for details.");
            LOGGER.error("Failed to equip any pieces of {} armor on player {}", armorType, player.getName().getString());
        }
        
        return equipped > 0 ? 1 : 0;
    }

    private static int equipItem(CommandContext<CommandSourceStack> context)
    {
        if (cantManipulate(context)) return 0;

        ServerPlayer player = getPlayer(context);
        String slotName = StringArgumentType.getString(context, "slot");
        CommandSourceStack source = context.getSource();

        // Enhanced parameter validation
        if (slotName == null || slotName.trim().isEmpty()) {
            Messenger.m(source, "r Equipment slot cannot be empty");
            Messenger.m(source, "r Usage: /player <name> equip <slot> <item>");
            Messenger.m(source, "r Valid slots: head, helmet, chest, chestplate, legs, leggings, feet, boots, mainhand, weapon, offhand, shield");
            Messenger.m(source, "r Example: /player Steve equip head diamond_helmet");
            LOGGER.warn("Empty slot name provided for player {} by {}", player.getName().getString(), source.getTextName());
            return 0;
        }

        EquipmentSlot slot = EquipmentSlotMapping.fromString(slotName);
        if (slot == null)
        {
            Messenger.m(source, "r Invalid equipment slot: '" + slotName + "'");
            Messenger.m(source, "r Usage: /player <name> equip <slot> <item>");
            Messenger.m(source, "r Valid slots: head, helmet, chest, chestplate, legs, leggings, feet, boots, mainhand, weapon, offhand, shield");
            Messenger.m(source, "r Example: /player Steve equip head diamond_helmet");
            LOGGER.warn("Invalid slot name '{}' provided for player {} by {}", slotName, player.getName().getString(), source.getTextName());
            return 0;
        }

        try
        {
            ItemInput itemInput = ItemArgument.getItem(context, "item");
            ItemStack itemStack = itemInput.createItemStack(1, false);
            
            // Validate that the item was created successfully
            if (itemStack.isEmpty()) {
                Messenger.m(source, "r Failed to create item stack - item may not exist");
                Messenger.m(source, "r Usage: /player <name> equip <slot> <item>");
                Messenger.m(source, "r Example: /player Steve equip head diamond_helmet");
                LOGGER.warn("Empty item stack created for player {} in slot {} by {}", player.getName().getString(), slotName, source.getTextName());
                return 0;
            }
            
            // Store previous item for logging
            ItemStack previousItem = player.getItemBySlot(slot);
            String previousItemName = previousItem.isEmpty() ? "empty" : previousItem.getDisplayName().getString();
            
            player.setItemSlot(slot, itemStack);
            
            Messenger.m(source, "g Successfully equipped " + itemStack.getDisplayName().getString() + " in " + slot.getName() + " slot for " + player.getName().getString());
            if (!previousItem.isEmpty()) {
                Messenger.m(source, "w Replaced previous item: " + previousItemName);
            }
            
            LOGGER.info("Equipped {} in {} slot for player {} (replaced: {}) (requested by {})", 
                itemStack.getDisplayName().getString(), slot.getName(), player.getName().getString(), previousItemName, source.getTextName());
            return 1;
        }
        catch (Exception e)
        {
            Messenger.m(source, "r Failed to equip item in " + slot.getName() + " slot");
            Messenger.m(source, "r Error: " + e.getMessage());
            Messenger.m(source, "r Usage: /player <name> equip <slot> <item>");
            Messenger.m(source, "r Example: /player Steve equip head diamond_helmet");
            LOGGER.error("Failed to equip item in {} slot for player {} (requested by {}): {}", slot.getName(), player.getName().getString(), source.getTextName(), e.getMessage(), e);
            return 0;
        }
    }

    private static int unequipItem(CommandContext<CommandSourceStack> context)
    {
        if (cantManipulate(context)) return 0;

        ServerPlayer player = getPlayer(context);
        String slotName = StringArgumentType.getString(context, "slot");
        CommandSourceStack source = context.getSource();

        // Enhanced parameter validation
        if (slotName == null || slotName.trim().isEmpty()) {
            Messenger.m(source, "r Equipment slot cannot be empty");
            Messenger.m(source, "r Usage: /player <name> unequip <slot>");
            Messenger.m(source, "r Valid slots: head, helmet, chest, chestplate, legs, leggings, feet, boots, mainhand, weapon, offhand, shield");
            Messenger.m(source, "r Example: /player Steve unequip head");
            LOGGER.warn("Empty slot name provided for unequip on player {} by {}", player.getName().getString(), source.getTextName());
            return 0;
        }

        EquipmentSlot slot = EquipmentSlotMapping.fromString(slotName);
        if (slot == null)
        {
            Messenger.m(source, "r Invalid equipment slot: '" + slotName + "'");
            Messenger.m(source, "r Usage: /player <name> unequip <slot>");
            Messenger.m(source, "r Valid slots: head, helmet, chest, chestplate, legs, leggings, feet, boots, mainhand, weapon, offhand, shield");
            Messenger.m(source, "r Example: /player Steve unequip head");
            LOGGER.warn("Invalid slot name '{}' provided for unequip on player {} by {}", slotName, player.getName().getString(), source.getTextName());
            return 0;
        }

        ItemStack currentItem = player.getItemBySlot(slot);
        if (currentItem.isEmpty())
        {
            Messenger.m(source, "r No item equipped in " + slot.getName() + " slot for " + player.getName().getString());
            Messenger.m(source, "r Use '/player " + player.getName().getString() + " equipment' to see current equipment");
            LOGGER.debug("Attempted to unequip from empty {} slot on player {} by {}", slot.getName(), player.getName().getString(), source.getTextName());
            return 0;
        }

        try {
            String removedItemName = currentItem.getDisplayName().getString();
            player.setItemSlot(slot, ItemStack.EMPTY);
            
            Messenger.m(source, "g Successfully removed " + removedItemName + " from " + slot.getName() + " slot for " + player.getName().getString());
            LOGGER.info("Unequipped {} from {} slot for player {} (requested by {})", removedItemName, slot.getName(), player.getName().getString(), source.getTextName());
            return 1;
        } catch (Exception e) {
            Messenger.m(source, "r Failed to unequip item from " + slot.getName() + " slot");
            Messenger.m(source, "r Error: " + e.getMessage());
            LOGGER.error("Failed to unequip item from {} slot for player {} (requested by {}): {}", slot.getName(), player.getName().getString(), source.getTextName(), e.getMessage(), e);
            return 0;
        }
    }

    private static int showEquipment(CommandContext<CommandSourceStack> context)
    {
        if (cantManipulate(context)) return 0;

        ServerPlayer player = getPlayer(context);
        CommandSourceStack source = context.getSource();

        try {
            Messenger.m(source, "g Equipment for " + player.getName().getString() + ":");
            
            int equippedCount = 0;
            for (EquipmentSlot slot : EquipmentSlot.values())
            {
                ItemStack item = player.getItemBySlot(slot);
                if (item.isEmpty()) {
                    Messenger.m(source, "w " + slot.getName() + ": Empty");
                } else {
                    String itemName = item.getDisplayName().getString();
                    String durabilityInfo = "";
                    if (item.isDamageableItem()) {
                        int durability = item.getMaxDamage() - item.getDamageValue();
                        int maxDurability = item.getMaxDamage();
                        durabilityInfo = String.format(" (Durability: %d/%d)", durability, maxDurability);
                    }
                    Messenger.m(source, "w " + slot.getName() + ": " + itemName + durabilityInfo);
                    equippedCount++;
                }
            }
            
            if (equippedCount == 0) {
                Messenger.m(source, "y No equipment currently equipped");
                Messenger.m(source, "w Use '/player " + player.getName().getString() + " equip <armor_type>' to equip a full armor set");
                Messenger.m(source, "w Use '/player " + player.getName().getString() + " equip <slot> <item>' to equip individual items");
            } else {
                Messenger.m(source, "g Total equipped items: " + equippedCount);
            }
            
            LOGGER.debug("Displayed equipment for player {} (requested by {})", player.getName().getString(), source.getTextName());
            return 1;
        } catch (Exception e) {
            Messenger.m(source, "r Failed to display equipment information");
            Messenger.m(source, "r Error: " + e.getMessage());
            LOGGER.error("Failed to display equipment for player {} (requested by {}): {}", player.getName().getString(), source.getTextName(), e.getMessage(), e);
            return 0;
        }
    }
}
