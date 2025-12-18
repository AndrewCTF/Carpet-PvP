package carpet.commands;

import carpet.helpers.EntityPlayerActionPack;
import carpet.helpers.EntityPlayerActionPack.Action;
import carpet.helpers.EntityPlayerActionPack.ActionType;
import carpet.helpers.pathfinding.ElytraAStarPathfinder;
import carpet.helpers.pathfinding.BotNavMode;
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
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
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
                        .then(makeAttackCommand())
                    .then(makeGlideCommand())
                    .then(makeNavCommand())
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

    private static LiteralArgumentBuilder<CommandSourceStack> makeGlideCommand()
    {
        return literal("glide")
                .then(literal("start").executes(PlayerCommand::glideStart))
                .then(literal("stop").executes(PlayerCommand::glideStop))
                .then(literal("freeze")
                        .executes(PlayerCommand::glideFreezeToggle)
                        .then(argument("value", BoolArgumentType.bool())
                                .executes(PlayerCommand::glideFreezeSet)))
            .then(literal("arrival")
                .then(literal("stop").executes(PlayerCommand::glideArrivalStop))
                .then(literal("freeze").executes(PlayerCommand::glideArrivalFreeze))
                .then(literal("descend").executes(PlayerCommand::glideArrivalDescend))
                .then(literal("land").executes(PlayerCommand::glideArrivalLand))
                .then(literal("circle").executes(PlayerCommand::glideArrivalCircle)))
            .then(literal("launch")
                .then(literal("assist")
                    .then(argument("value", BoolArgumentType.bool())
                        .executes(PlayerCommand::glideLaunchAssist)))
                .then(literal("pitch")
                    .then(argument("deg", DoubleArgumentType.doubleArg(-45.0D, 45.0D))
                        .executes(PlayerCommand::glideLaunchPitch)))
                .then(literal("speed")
                    .then(argument("blocksPerTick", DoubleArgumentType.doubleArg(0.0D))
                        .executes(PlayerCommand::glideLaunchSpeed)))
                .then(literal("forwardTicks")
                    .then(argument("ticks", IntegerArgumentType.integer(0, 20))
                        .executes(PlayerCommand::glideLaunchForwardTicks))))
                .then(literal("freezeAtTarget")
                        .then(argument("value", BoolArgumentType.bool())
                                .executes(PlayerCommand::glideFreezeAtTargetSet)))
                .then(literal("speed")
                        .then(argument("blocksPerTick", DoubleArgumentType.doubleArg(0.0D))
                                .executes(PlayerCommand::glideSpeed)))
                .then(literal("rates")
                        .then(argument("yawDegPerTick", DoubleArgumentType.doubleArg(0.0D))
                                .then(argument("pitchDegPerTick", DoubleArgumentType.doubleArg(0.0D))
                                        .executes(PlayerCommand::glideRates))))
                .then(literal("usePitch")
                        .then(argument("value", BoolArgumentType.bool())
                                .executes(PlayerCommand::glideUsePitch)))
                .then(literal("input")
                        .then(argument("forward", DoubleArgumentType.doubleArg(-1.0D, 1.0D))
                                .then(argument("strafe", DoubleArgumentType.doubleArg(-1.0D, 1.0D))
                                        .then(argument("up", DoubleArgumentType.doubleArg(-1.0D, 1.0D))
                                                .executes(PlayerCommand::glideInput)))))
                .then(literal("heading")
                        .then(argument("yaw", DoubleArgumentType.doubleArg(-360.0D, 360.0D))
                                .then(argument("pitch", DoubleArgumentType.doubleArg(-90.0D, 90.0D))
                                        .executes(PlayerCommand::glideHeading))))
                .then(literal("goto")
                    .then(literal("smart")
                        .then(argument("pos", Vec3Argument.vec3())
                            .executes(PlayerCommand::glideGotoSmartDefault)
                            .then(argument("arrivalRadius", DoubleArgumentType.doubleArg(0.0D))
                                .executes(PlayerCommand::glideGotoSmartWithRadius))))
                    .then(argument("pos", Vec3Argument.vec3())
                        .executes(PlayerCommand::glideGotoDefault)
                        .then(argument("arrivalRadius", DoubleArgumentType.doubleArg(0.0D))
                            .executes(PlayerCommand::glideGotoWithRadius))))
                .then(literal("status").executes(PlayerCommand::glideStatus));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeNavCommand()
    {
        return literal("nav")
                .then(literal("stop").executes(PlayerCommand::navStop))
                .then(literal("status").executes(PlayerCommand::navStatus))
                .then(literal("goto")
                        .then(argument("pos", Vec3Argument.vec3())
                                .executes(c -> navGoto(c, BotNavMode.AUTO))
                                .then(argument("arrivalRadius", DoubleArgumentType.doubleArg(0.0D))
                                        .executes(c -> navGoto(c, BotNavMode.AUTO)))
                        )
                        .then(literal("land").then(argument("pos", Vec3Argument.vec3())
                                .executes(c -> navGoto(c, BotNavMode.LAND))
                                .then(argument("arrivalRadius", DoubleArgumentType.doubleArg(0.0D))
                                        .executes(c -> navGoto(c, BotNavMode.LAND)))))
                        .then(literal("water").then(argument("pos", Vec3Argument.vec3())
                                .executes(c -> navGoto(c, BotNavMode.WATER))
                                .then(argument("arrivalRadius", DoubleArgumentType.doubleArg(0.0D))
                                        .executes(c -> navGoto(c, BotNavMode.WATER)))))
                .then(literal("air")
                    // Default: land on the floor under the target XZ.
                    .then(argument("pos", Vec3Argument.vec3())
                        .executes(c -> navGotoAir(c, true))
                        .then(argument("arrivalRadius", DoubleArgumentType.doubleArg(0.0D))
                            .executes(c -> navGotoAir(c, true))))
                    .then(literal("land")
                        .then(argument("pos", Vec3Argument.vec3())
                            .executes(c -> navGotoAir(c, true))
                            .then(argument("arrivalRadius", DoubleArgumentType.doubleArg(0.0D))
                                .executes(c -> navGotoAir(c, true)))))
                    .then(literal("drop")
                        .then(argument("pos", Vec3Argument.vec3())
                            .executes(c -> navGotoAir(c, false))
                            .then(argument("arrivalRadius", DoubleArgumentType.doubleArg(0.0D))
                                .executes(c -> navGotoAir(c, false))))))
                );
    }

    private static boolean cantNavManipulate(CommandContext<CommandSourceStack> context)
    {
        if (cantManipulate(context)) return true;
        if (!CarpetSettings.fakePlayerNavigation)
        {
            Messenger.m(context.getSource(), "r Navigation is disabled. Enable the carpet rule 'fakePlayerNavigation' first.");
            return true;
        }
        ServerPlayer player = getPlayer(context);
        if (!(player instanceof EntityPlayerMPFake))
        {
            Messenger.m(context.getSource(), "r Navigation is only supported for fake players.");
            return true;
        }
        return false;
    }

    private static int navStop(CommandContext<CommandSourceStack> context)
    {
        if (cantNavManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.stopNavigation();
        Messenger.m(context.getSource(), "g Navigation stopped for ", player.getName());
        return 1;
    }

    private static int navStatus(CommandContext<CommandSourceStack> context)
    {
        if (cantNavManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        if (!ap.isNavEnabled())
        {
            Messenger.m(context.getSource(), "y Navigation: disabled for ", player.getName());
            return 1;
        }
        Vec3 target = ap.getNavTargetPos();
        Messenger.m(context.getSource(),
                "g Navigation: enabled ",
                "w mode=", "y ", ap.getNavMode().name().toLowerCase(),
                "w  target=", (target == null ? "r <none>" : String.format("y %.1f %.1f %.1f", target.x, target.y, target.z)),
                "w  radius=", String.format("y %.2f", ap.getNavArrivalRadius()),
                "g  for ", player.getName());
        return 1;
    }

    private static int navGoto(CommandContext<CommandSourceStack> context, BotNavMode mode)
    {
        if (cantNavManipulate(context)) return 0;

        Vec3 pos = Vec3Argument.getVec3(context, "pos");
        double arrivalRadius = 1.0D;
        try
        {
            arrivalRadius = DoubleArgumentType.getDouble(context, "arrivalRadius");
        }
        catch (IllegalArgumentException ignored)
        {
        }

        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setNavGoto(pos, mode, arrivalRadius);

        Messenger.m(context.getSource(), "g Navigation started for ", player.getName(), "w  mode=", "y ", mode.name().toLowerCase());
        return 1;
    }

    private static int navGotoAir(CommandContext<CommandSourceStack> context, boolean landOnFloor)
    {
        if (cantNavManipulate(context)) return 0;

        Vec3 pos = Vec3Argument.getVec3(context, "pos");
        double arrivalRadius = 1.0D;
        try
        {
            arrivalRadius = DoubleArgumentType.getDouble(context, "arrivalRadius");
        }
        catch (IllegalArgumentException ignored)
        {
        }

        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setNavGotoAir(pos, arrivalRadius, landOnFloor);

        Messenger.m(context.getSource(), "g Air navigation started for ", player.getName(), "w  arrival=", "y ", landOnFloor ? "land" : "drop");
        return 1;
    }

    private static boolean cantBotManipulate(CommandContext<CommandSourceStack> context)
    {
        if (cantManipulate(context)) return true;
        if (!CarpetSettings.fakePlayerElytraGlide)
        {
            Messenger.m(context.getSource(), "r Elytra gliding controls are disabled. Enable the carpet rule 'fakePlayerElytraGlide' first.");
            return true;
        }
        ServerPlayer player = getPlayer(context);
        if (!(player instanceof EntityPlayerMPFake))
        {
            Messenger.m(context.getSource(), "r Glide controls are only supported for fake players.");
            return true;
        }
        return false;
    }

    private static int glideStart(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideEnabled(true);
        Messenger.m(context.getSource(), "g Elytra glide enabled for ", player.getName());
        return 1;
    }

    private static int glideStop(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideEnabled(false);
        Messenger.m(context.getSource(), "g Elytra glide disabled for ", player.getName());
        return 1;
    }

    private static int glideFreezeToggle(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        boolean next = !ap.isGlideFrozen();
        ap.setGlideFrozen(next);
        Messenger.m(context.getSource(), next ? "y Glide frozen for " : "g Glide unfrozen for ", player.getName());
        return 1;
    }

    private static int glideFreezeSet(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        boolean value = BoolArgumentType.getBool(context, "value");
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideFrozen(value);
        Messenger.m(context.getSource(), value ? "y Glide frozen for " : "g Glide unfrozen for ", player.getName());
        return 1;
    }

    private static int glideFreezeAtTargetSet(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        boolean value = BoolArgumentType.getBool(context, "value");
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideFreezeAtTarget(value);
        Messenger.m(context.getSource(), "g freezeAtTarget set to ", value ? "true" : "false", "g  for ", player.getName());
        return 1;
    }

    private static int glideArrivalStop(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideArrivalAction(EntityPlayerActionPack.GlideArrivalAction.STOP);
        Messenger.m(context.getSource(), "g arrival action set to stop for ", player.getName());
        return 1;
    }

    private static int glideArrivalFreeze(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideArrivalAction(EntityPlayerActionPack.GlideArrivalAction.FREEZE);
        Messenger.m(context.getSource(), "g arrival action set to freeze for ", player.getName());
        return 1;
    }

    private static int glideArrivalDescend(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideArrivalAction(EntityPlayerActionPack.GlideArrivalAction.DESCEND);
        Messenger.m(context.getSource(), "g arrival action set to descend for ", player.getName());
        return 1;
    }

    private static int glideArrivalLand(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideArrivalAction(EntityPlayerActionPack.GlideArrivalAction.LAND);
        Messenger.m(context.getSource(), "g arrival action set to land for ", player.getName());
        return 1;
    }

    private static int glideArrivalCircle(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideArrivalAction(EntityPlayerActionPack.GlideArrivalAction.CIRCLE);
        Messenger.m(context.getSource(), "g arrival action set to circle for ", player.getName());
        return 1;
    }

    private static int glideLaunchAssist(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        boolean value = BoolArgumentType.getBool(context, "value");
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideLaunchAssistEnabled(value);
        Messenger.m(context.getSource(), "g launch assist set to ", value ? "true" : "false", "g  for ", player.getName());
        return 1;
    }

    private static int glideLaunchPitch(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        float deg = (float) DoubleArgumentType.getDouble(context, "deg");
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideLaunchPitch(deg);
        Messenger.m(context.getSource(), "g launch pitch set to ", String.format("%.1f", deg), "g° for ", player.getName());
        return 1;
    }

    private static int glideLaunchSpeed(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        double speed = DoubleArgumentType.getDouble(context, "blocksPerTick");
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideLaunchSpeed(speed);
        Messenger.m(context.getSource(), "g launch speed set to ", String.format("%.3f", speed), "g  for ", player.getName());
        return 1;
    }

    private static int glideLaunchForwardTicks(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        int ticks = IntegerArgumentType.getInteger(context, "ticks");
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideLaunchForwardTicks(ticks);
        Messenger.m(context.getSource(), "g launch forwardTicks set to ", String.valueOf(ticks), "g  for ", player.getName());
        return 1;
    }

    private static int glideSpeed(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        double speed = DoubleArgumentType.getDouble(context, "blocksPerTick");
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideSpeed(speed);
        Messenger.m(context.getSource(), "g speed set to ", String.format("%.3f", ap.getGlideSpeed()), "g  for ", player.getName());
        return 1;
    }

    private static int glideRates(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        float yawRate = (float) DoubleArgumentType.getDouble(context, "yawDegPerTick");
        float pitchRate = (float) DoubleArgumentType.getDouble(context, "pitchDegPerTick");
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideRates(yawRate, pitchRate);
        Messenger.m(context.getSource(), "g rates set for ", player.getName());
        return 1;
    }

    private static int glideUsePitch(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        boolean value = BoolArgumentType.getBool(context, "value");
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideUsePitchForForward(value);
        Messenger.m(context.getSource(), "g usePitch set to ", value ? "true" : "false", "g  for ", player.getName());
        return 1;
    }

    private static int glideInput(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        float forward = (float) DoubleArgumentType.getDouble(context, "forward");
        float strafe = (float) DoubleArgumentType.getDouble(context, "strafe");
        float up = (float) DoubleArgumentType.getDouble(context, "up");
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideEnabled(true);
        ap.setGlideInput(forward, strafe, up);
        Messenger.m(context.getSource(), "g glide input set for ", player.getName());
        return 1;
    }

    private static int glideHeading(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        float yaw = (float) DoubleArgumentType.getDouble(context, "yaw");
        float pitch = (float) DoubleArgumentType.getDouble(context, "pitch");
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideEnabled(true);
        ap.setGlideHeading(yaw, pitch);
        Messenger.m(context.getSource(), "g glide heading set for ", player.getName());
        return 1;
    }

    private static int glideGotoDefault(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        Vec3 pos = Vec3Argument.getVec3(context, "pos");
        double radius = 1.0D;
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideEnabled(true);
        ap.setGlideGoto(pos, radius);
        Messenger.m(context.getSource(), "g glide goto set for ", player.getName());
        return 1;
    }

    private static int glideGotoWithRadius(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        Vec3 pos = Vec3Argument.getVec3(context, "pos");
        double radius = DoubleArgumentType.getDouble(context, "arrivalRadius");
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideEnabled(true);
        ap.setGlideGoto(pos, radius);
        Messenger.m(context.getSource(), "g glide goto set for ", player.getName());
        return 1;
    }

    private static int glideGotoSmartDefault(CommandContext<CommandSourceStack> context)
    {
        return glideGotoSmart(context, 1.0D);
    }

    private static int glideGotoSmartWithRadius(CommandContext<CommandSourceStack> context)
    {
        double radius = DoubleArgumentType.getDouble(context, "arrivalRadius");
        return glideGotoSmart(context, radius);
    }

    private static int glideGotoSmart(CommandContext<CommandSourceStack> context, double radius)
    {
        if (cantBotManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        if (!(player.level() instanceof ServerLevel level)) return 0;

        Vec3 goal = Vec3Argument.getVec3(context, "pos");
        BlockPos startPos = BlockPos.containing(player.position());
        BlockPos goalPos = BlockPos.containing(goal);

        ElytraAStarPathfinder.Settings settings = ElytraAStarPathfinder.Settings.defaults();
        ElytraAStarPathfinder pf = new ElytraAStarPathfinder();
        List<BlockPos> raw = pf.findPath(level, startPos, goalPos, settings);
        if (raw == null || raw.isEmpty())
        {
            Messenger.m(context.getSource(), "r No smart path found (range/terrain/chunks). Try a higher goal Y or move closer.");
            return 0;
        }

        List<BlockPos> compressed = ElytraAStarPathfinder.compressWaypoints(raw, settings.waypointStride());
        List<Vec3> waypoints = new java.util.ArrayList<>(compressed.size());
        for (BlockPos p : compressed)
        {
            // Aim at block centers for smoother flight.
            waypoints.add(new Vec3(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D));
        }

        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        ap.setGlideEnabled(true);
        ap.setGlideArrivalAction(EntityPlayerActionPack.GlideArrivalAction.LAND);
        ap.setGlideGotoWaypoints(waypoints, goal, radius);
        Messenger.m(context.getSource(), "g smart glide path set with ", String.valueOf(waypoints.size()), "g  waypoints for ", player.getName());
        return 1;
    }

    private static int glideStatus(CommandContext<CommandSourceStack> context)
    {
        if (cantBotManipulate(context)) return 0;
        ServerPlayer player = getPlayer(context);
        EntityPlayerActionPack ap = ((ServerPlayerInterface) player).getActionPack();
        Messenger.m(context.getSource(),
                "g glide: enabled=", ap.isGlideEnabled() ? "true" : "false",
                "g , frozen=", ap.isGlideFrozen() ? "true" : "false",
            "g , speed=", String.format("%.3f", ap.getGlideSpeed()),
            "g , arrival=", ap.getGlideArrivalAction().name().toLowerCase()
        );
        return 1;
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

    private static LiteralArgumentBuilder<CommandSourceStack> makeAttackCommand()
    {
        LiteralArgumentBuilder<CommandSourceStack> base = literal("attack")
                .executes(manipulation(ap -> {
                    ap.setAttackCritical(false);
                    ap.start(ActionType.ATTACK, Action.once());
                }))
                .then(literal("once").executes(manipulation(ap -> {
                    ap.setAttackCritical(false);
                    ap.start(ActionType.ATTACK, Action.once());
                })))
                .then(literal("continuous").executes(manipulation(ap -> {
                    ap.setAttackCritical(false);
                    ap.start(ActionType.ATTACK, Action.continuous());
                })))
                .then(literal("interval").then(argument("ticks", IntegerArgumentType.integer(1))
                        .executes(c -> manipulate(c, ap -> {
                            ap.setAttackCritical(false);
                            ap.start(ActionType.ATTACK, Action.interval(IntegerArgumentType.getInteger(c, "ticks")));
                        }))));

        LiteralArgumentBuilder<CommandSourceStack> crit = literal("crit")
                .executes(manipulation(ap -> {
                    ap.setAttackCritical(true);
                    ap.start(ActionType.ATTACK, Action.onceUntilSuccess());
                }))
                .then(literal("once").executes(manipulation(ap -> {
                    ap.setAttackCritical(true);
                    ap.start(ActionType.ATTACK, Action.onceUntilSuccess());
                })))
                .then(literal("continuous").executes(manipulation(ap -> {
                    ap.setAttackCritical(true);
                    ap.start(ActionType.ATTACK, Action.continuous());
                })))
                .then(literal("interval").then(argument("ticks", IntegerArgumentType.integer(1))
                        .executes(c -> manipulate(c, ap -> {
                            ap.setAttackCritical(true);
                            ap.start(ActionType.ATTACK, Action.intervalUntilSuccess(IntegerArgumentType.getInteger(c, "ticks")));
                        }))));

        return base.then(crit);
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
        Set<String> players = new LinkedHashSet<>(List.of("Bot", "s"));
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
                    .get(net.minecraft.resources.Identifier.parse(entry.getValue()))
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
