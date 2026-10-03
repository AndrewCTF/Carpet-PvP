package carpet.pvp.crystal;

import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.CombatUtils;
import carpet.pvp.look.LookController;
import carpet.pvp.sim.Box;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The hands of a crystal bot: the actions {@link BotBody} has no verb for.
 *
 * <p>Everything a player does with a crystal is a click on a face of a block, so that is what this class
 * sends. The click goes as a use-item-on packet, the packet a client sends, which puts the game's own
 * reach, build limit and placement checks in the way; a swing at a placed crystal is the action pack's
 * melee attack behind the body's reach and aim gate. Nothing happens before the view is on the thing
 * being clicked: {@link #aim} turns the view through the body's look controller at the bot's skill, and
 * the caller only clicks once {@link #onCell} says a ray from the eyes along the view the bot now has
 * meets the block. Clicks come from the body's own limiter, so putting a crystal down and hitting it
 * draw on one mouse.</p>
 */
public final class CrystalHand
{
    /** How far inside a block cell the view has to be before the bot counts itself as aimed at it. */
    private static final double AIM_INSET = 0.05;
    /** Half the diagonal of a one block face, the angular size the look controller is given for a cell. */
    private static final double FACE_HALF_DIAGONAL = 0.4;
    /** How far from the centre of a block a face's centre sits, in blocks. */
    private static final double FACE_MIDDLE = 0.5;
    /** How far short of that face the bot aims, so that its ray ends inside the block it is aiming at. */
    private static final double FACE_NUDGE = 0.05;
    /** How far the bot will walk from where it is before it drops a placement it cannot reach. */
    private static final double GIVE_UP_RANGE = 2.0;

    private final BotBody body;

    public CrystalHand(BotBody body)
    {
        this.body = body;
    }

    public BotBody body()
    {
        return body;
    }

    public EntityPlayerMPFake bot()
    {
        return body.bot();
    }

    public EntityPlayerActionPack pack()
    {
        return body.pack();
    }

    private LookController look()
    {
        return body.look();
    }

    private ServerLevel level()
    {
        return (ServerLevel) bot().level();
    }

    // ===== the loadout =====

    /** The hotbar slot an item sits in, or -1 when the bot carries none. */
    public int slot(Item item)
    {
        return BotBody.findSlot(bot(), item);
    }

    /**
     * Asks for a hotbar slot, which as for a real client takes effect on the next tick. Reports whether the
     * bot is holding that item already, so a caller waits a tick rather than clicking at the wrong thing.
     */
    public boolean hold(int slot0)
    {
        if (slot0 < 0)
        {
            return false;
        }
        if (slot0 != body.currentSlot() && slot0 != body.pendingSlot())
        {
            body.requestSlot(slot0);
        }
        return body.currentSlot() == slot0;
    }

    /** True while the bot holds the given item in the main hand. */
    public boolean holding(Item item)
    {
        return bot().getMainHandItem().is(item);
    }

    /** True while the bot holds the given item in the offhand. */
    public boolean offhand(Item item)
    {
        return CombatUtils.offhandHolds(bot(), item);
    }

    /** How many of an item the bot carries, counted over the inventory and the offhand. */
    public int carried(Item item)
    {
        Inventory inventory = bot().getInventory();
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
        {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item))
            {
                count += stack.getCount();
            }
        }
        if (bot().getItemBySlot(EquipmentSlot.OFFHAND).is(item))
        {
            count++;
        }
        return count;
    }

    /** True when the bot carries more than one totem, the offhand one included. */
    public boolean twoTotems()
    {
        return carried(Items.TOTEM_OF_UNDYING) >= 2;
    }

    /** The explosion protection the bot's own armour gives, as EnchantmentHelper reads it off the wearer. */
    public float ownExplosionProtection()
    {
        return EnchantmentHelper.getDamageProtection(level(), bot(), bot().damageSources().explosion(bot(), bot()));
    }

    // ===== aiming =====

    /**
     * Turns the view onto a point at the bot's skill and on its mouse grid, and applies it. The angular
     * size of a cell is what the look controller treats the aim point as, the way a player aims at a face
     * rather than at a mathematical point.
     */
    public void aim(double x, double y, double z)
    {
        double dx = x - bot().getX();
        double dy = y - bot().getEyeY();
        double dz = z - bot().getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(flat, 1.0E-6)));
        float radius = (float) Math.toDegrees(Math.atan2(FACE_HALF_DIAGONAL, Math.max(flat, 0.1)));
        look().aimAt(yaw, pitch, radius);
        float beforeYaw = look().yaw();
        float beforePitch = look().pitch();
        look().tick();
        float afterYaw = look().yaw();
        float afterPitch = look().pitch();
        body.stats().recordRotationStep(afterYaw - beforeYaw, afterPitch - beforePitch, body.profile().grid());
        pack().look(afterYaw, afterPitch);
    }

    /** Aims at the centre of a block cell. */
    public void aim(BlockPos cell)
    {
        aim(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5);
    }

    /**
     * Aims at a face of a block, at the point a client reports when its crosshair sits in the middle of
     * that face, pulled a hair into the block so that the ray really enters it: a ray that only reaches
     * the face from outside never meets the block the bot is aiming at.
     */
    public void aimFace(BlockPos against, Direction face)
    {
        aim(against.getX() + 0.5 + face.getStepX() * (FACE_MIDDLE - FACE_NUDGE),
                against.getY() + 0.5 + face.getStepY() * (FACE_MIDDLE - FACE_NUDGE),
                against.getZ() + 0.5 + face.getStepZ() * (FACE_MIDDLE - FACE_NUDGE));
    }

    /**
     * True when a ray from the bot's eyes along the view it currently has meets the cell. This is the gate
     * on every click: no crosshair on the block, no click.
     */
    public boolean onCell(BlockPos cell)
    {
        return on(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + 1.0, cell.getY() + 1.0,
                cell.getZ() + 1.0);
    }

    /** True when a ray from the bot's eyes along the view it currently has meets the box. */
    public boolean on(Box box)
    {
        return on(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private boolean on(double minX, double minY, double minZ, double maxX, double maxY, double maxZ)
    {
        double yaw = Math.toRadians(bot().getYRot());
        double pitch = Math.toRadians(bot().getXRot());
        return BotBody.rayHitsBox(bot().getX(), bot().getEyeY(), bot().getZ(),
                -Mth.sin(yaw) * Mth.cos(pitch), -Mth.sin(pitch), Mth.cos(yaw) * Mth.cos(pitch),
                minX + AIM_INSET, minY + AIM_INSET, minZ + AIM_INSET,
                maxX - AIM_INSET, maxY - AIM_INSET, maxZ - AIM_INSET);
    }

    // ===== clicking =====

    /**
     * Clicks a face of a block with the item in the main hand, as a client does. The caller has already
     * checked that the view is on the block; what the block does with the click is its own business.
     *
     * <p>The click goes through {@code ServerPlayerGameMode.useItemOn}, the same dispatch the vanilla action
     * pack uses, so the item's own rules and the build limit still apply. The reach test a client packet
     * would do is made here instead, with the same predicate and the same margin, so a click the game would
     * throw away is never made.</p>
     */
    public void clickFace(BlockPos against, Direction face)
    {
        if (!bot().isWithinBlockInteractionRange(against, 1.0))
        {
            return;
        }
        Vec3 on = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5,
                face.getStepZ() * 0.5);
        bot().gameMode.useItemOn(bot(), level(), bot().getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(on, face, against, false));
        //? if >=26.3 {
        bot().swingAndResetAttackStrength(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        //?} else {
        /*bot().swing(InteractionHand.MAIN_HAND);
        *///?}
    }

    /**
     * Swings at an entity, but only when the game would let the bot land the hit: inside its attack range,
     * and with a ray from the eyes along the view it has hitting the entity's box. Reports whether the hit
     * went through; a swing at nothing still costs the click, as it does for a player.
     */
    public boolean strike(Entity target)
    {
        if (target == null || target.isRemoved() || !target.isAlive())
        {
            return false;
        }
        if (!body.canHit(target))
        {
            body.stats().misses++;
            pack().swing();
            return false;
        }
        pack().attackEntity(target);
        return true;
    }

    /** Uses the item in the main hand, which is how a pearl leaves the hand and the game sets its cooldown. */
    public boolean useHeld()
    {
        ItemStack stack = bot().getMainHandItem();
        if (stack.isEmpty())
        {
            return false;
        }
        return bot().gameMode.useItem(bot(), level(), stack, InteractionHand.MAIN_HAND).consumesAction();
    }

    // ===== the world =====

    /**
     * The box a placed end crystal can be hit in: EntityTypes.END_CRYSTAL is a two by two by two box
     * centred on the entity, which is what the swing has to meet.
     */
    public Box crystalBox(EndCrystal crystal)
    {
        return new Box(crystal.getX() - 1.0, crystal.getY() - 1.0, crystal.getZ() - 1.0,
                crystal.getX() + 1.0, crystal.getY() + 1.0, crystal.getZ() + 1.0);
    }

    /** The end crystal standing on the base cell at (x, y, z), or null when there is none. */
    public EndCrystal crystalAt(int x, int y, int z)
    {
        List<EndCrystal> found = level().getEntitiesOfClass(EndCrystal.class,
                new AABB(x, y, z, x + 1.0, y + 2.0, z + 1.0));
        return found.isEmpty() ? null : found.get(0);
    }

    /** How many charges are in a respawn anchor, as RespawnAnchorBlock.CHARGE reads it. */
    public int anchorCharge(BlockPos pos)
    {
        BlockState state = level().getBlockState(pos);
        return state.is(Blocks.RESPAWN_ANCHOR) ? state.getValue(RespawnAnchorBlock.CHARGE) : 0;
    }

    /** Whether the cell holds the given block kind, whatever its state. */
    public boolean isBlock(BlockPos pos, Block block)
    {
        return level().getBlockState(pos).is(block);
    }

    /** Whether the cell has nothing solid in it, which is what it means to be able to step into it. */
    public boolean isFree(BlockPos pos)
    {
        BlockState state = level().getBlockState(pos);
        return state.isAir() || state.getCollisionShape(level(), pos).isEmpty();
    }

    /** Whether the bot is still close enough to a cell it picked to go on with it. */
    public boolean near(BlockPos cell)
    {
        return bot().isWithinBlockInteractionRange(cell, GIVE_UP_RANGE);
    }
}
