package carpet.pvp.ranged;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotBudget;
import carpet.pvp.BotStats;
import carpet.pvp.Perception;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.CrystalSearch;
import carpet.pvp.sim.ProjectileSim;
import carpet.pvp.sim.TntCartPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Laying a rail and a tnt minecart next to a target and then setting it off with a flaming arrow.
 *
 * <p>Where to lay it comes from {@link TntCartPlan}, which asks every block the bot can reach what the blast
 * there would do to the target. The bot's own share of that blast is deliberately not part of the choice,
 * because a cart is laid beside the target and lit from a distance. What decides whether the bot lights anything
 * is {@link TntCartPlan#survivable}, the same never-kill-yourself rule the crystal style keeps, asked again from
 * where the bot is standing: a cart goes down within a few blocks of the bot, so for the first few seconds of
 * it the bot is standing in the middle of its own blast and has to walk out of it before it can aim at the cart.
 * That is what {@link #outOfBlast} answers, and it is the difference between a bot that lights its cart and one
 * that lays it down and then stands next to it forever.</p>
 *
 * <p>Laying the rail and the cart are ordinary uses of the item in the hand, which is what a player does with the
 * right mouse button, so both go through the game mode and through {@link CartHand}: the bot asks for the slot,
 * waits the tick a hotbar change takes, then clicks the face at its own click rate. Nothing here lights the cart
 * either: a flaming arrow does, and that arrow is the bot's own bow shot.</p>
 */
public final class TntCart
{
    /** Half the volume around the bot the plan looks at, in blocks. */
    public static final int SEARCH_X = 4;
    public static final int SEARCH_Y = 2;
    public static final int SEARCH_Z = 4;
    /** Ticks between two looks for a place to lay the cart. */
    public static final int PLAN_TICKS = 10;
    /** Ticks a picked-out cell is given for its rail and then its cart before the bot looks for another one. */
    public static final int LAY_TICKS = 60;
    /**
     * Ticks the bot keeps looking for a cart it has laid before it gives the trap up. A cart is an entity, and a
     * lookup can miss it for a tick for reasons of its own, which is no reason to walk away from a live minecart
     * and hand the fight back to the bow.
     */
    private static final int LOOKUP_GRACE = 20;

    /** What the bot is still holding of the cell it picked out. */
    private enum Stage
    {
        /** The rail, which goes in the cell under the cart. */
        RAIL,
        /** The cart, which goes on the rail that was just put down. */
        CART
    }

    private final EntityPlayerMPFake bot;
    private final BotStats stats;
    private final CartHand hand;

    /** The cell the cart is armed in, and the cart itself, which is what a shot has to be aimed at. */
    private BlockPos rail;
    private MinecartTNT cart;
    /** The cell the plan picked out, waiting for its rail and then its cart. */
    private BlockPos cell;
    private Stage stage = Stage.RAIL;
    private int untilPlan;
    private int missed;
    /** True while the bot has a flaming arrow in the air at the cart it is standing next to. */
    private boolean shotAt;
    /** True on the tick after that cart stopped standing. */
    private boolean lit;

    public TntCart(EntityPlayerMPFake bot, BotBody body)
    {
        this.bot = bot;
        this.stats = body.stats();
        this.hand = new CartHand(body);
    }

    /**
     * The cart that is out, or null while there is none.
     *
     * <p>Where the cart is comes from the cell it was laid in rather than from a lookup, because that is where a
     * player knows it is: a cart is an entity in a chunk and {@code getEntitiesOfClass} walks the level's entity
     * sections, so a bot that has to find its own trap by a box spends the technique on whether the box comes
     * back with anything. The rail the cart was placed on is the witness that it is still there: a cart cannot
     * stand without the rail under it.</p>
     */
    public MinecartTNT rail()
    {
        if (cart != null && cart.isRemoved() && shotAt)
        {
            // The bot shot at this cart and the cart is no longer standing: the shot is what set it off.
            lit = true;
            shotAt = false;
        }
        if (cart == null || cart.isRemoved())
        {
            cart = null;
            if (rail != null && ++missed > LOOKUP_GRACE && !railed())
            {
                // The rail the cart went down on is gone, so the cart is gone with it: either the bot's shot
                // set it off or something else did, and either way there is no trap left to run from. Until then
                // a tick without the cart in hand is only a tick.
                rail = null;
                missed = 0;
            }
            else if (cart != null)
            {
                missed = 0;
            }
        }
        return cart;
    }

    /** True while the cell the bot laid its rail in still has a rail in it. */
    public boolean railed()
    {
        return rail != null && bot.level().getBlockState(rail).getBlock() instanceof BaseRailBlock;
    }

    /** The place the cart stands: the cell its rail went into, where {@code MinecartItem} puts the cart. */
    private Vec3 centreOfCell()
    {
        return new Vec3(rail.getX() + 0.5D, rail.getY() + 0.06D, rail.getZ() + 0.5D);
    }

    /**
     * True on the one tick the bot notices a cart it had shot at is no longer standing, which is a tnt minecart
     * it set off itself. Reading it clears it.
     */
    public boolean lit()
    {
        boolean was = lit;
        lit = false;
        return was;
    }

    /** Notes that a flaming arrow has just been let go at the cart that is out. */
    public void shot()
    {
        shotAt = true;
    }

    /** True while the bot still has a rail or a cart to put down in a cell the plan picked out. */
    public boolean laying()
    {
        return cell != null;
    }

    /** One tick of bookkeeping between attempts, and the tick a cell that never became a cart is given up on. */
    public void tick()
    {
        if (untilPlan <= 0)
        {
            return;
        }
        untilPlan--;
        if (untilPlan == 0 && cell != null)
        {
            // The rail went down but the cart never did, or neither did: the cell is given up rather than stood
            // over forever, and the plan looks for another one.
            cell = null;
            stage = Stage.RAIL;
        }
    }

    /** Forgets the cart, which is what a bot does when it is no longer interested in one. */
    public void forget()
    {
        rail = null;
        cart = null;
        cell = null;
        stage = Stage.RAIL;
        untilPlan = 0;
        missed = 0;
        shotAt = false;
        lit = false;
    }

    /**
     * Looks for a cell to put a cart in and remembers it until it has been laid, at most every
     * {@link #PLAN_TICKS} ticks. A cart has to go down within a reach of the bot, so this answers where, and
     * {@link #place} is what puts the two items down there. The search casts exposure rays through the real
     * level for every cell it looks at, so it draws on the shared budget and is skipped when the bot's share
     * of it is already spent.
     */
    public boolean plan(Perception.Snapshot me, Perception.Snapshot seen, int difficulty, Loadout loadout)
    {
        if (cell != null || rail != null || loadout.cart < 0 || loadout.rails < 0 || untilPlan > 0
                || !(bot.level() instanceof ServerLevel level))
        {
            return false;
        }
        BotBudget budget = BotBudget.instance();
        if (budget.join() < TntCartPlan.COST_PER_SEARCH)
        {
            stats.starvedTicks++;
            return false;
        }
        untilPlan = PLAN_TICKS;
        LevelExplosionView view = new LevelExplosionView(level);
        TntCartPlan.Placement found = new TntCartPlan(view).choose(perceived(me, 0.0F),
                perceived(seen, seen.healthTotal()), SEARCH_X, SEARCH_Y, SEARCH_Z, difficulty);
        budget.spend(TntCartPlan.COST_PER_SEARCH);
        stats.plannerCalls++;
        stats.simulatedTicks += TntCartPlan.COST_PER_SEARCH;
        if (found == null || !view.supportsRail(found.x, found.y - 1, found.z))
        {
            return false;
        }
        cell = new BlockPos(found.x, found.y, found.z);
        stage = Stage.RAIL;
        untilPlan = LAY_TICKS;
        return true;
    }

    /**
     * Where the view goes while a cell is being worked on, which is the block the next click is aimed at: the
     * rail into the cell under the cart, and then the cart onto the rail itself. Null when there is no cell,
     * which is the tick the plan is running and the view belongs on the target.
     */
    public BotBody.Aim aim()
    {
        return cell == null ? null : hand.aimAt(stage == Stage.RAIL ? cell.below() : cell);
    }

    /**
     * Works on putting the rail and the cart into the cell the plan picked out, one click at a time, and returns
     * true on the tick a cart went down.
     *
     * <p>Both are uses of the item in the hand on a face of a block, which is what a player does, so both go
     * through {@link CartHand}: the slot is asked for, the bot waits the tick a hotbar change takes, and the
     * click follows once the view is on the block and the click limiter lets it.</p>
     */
    public boolean place(Loadout loadout)
    {
        if (cell == null || rail != null || loadout.cart < 0 || loadout.rails < 0)
        {
            return false;
        }
        if (stage == Stage.RAIL)
        {
            if (!hand.hold(loadout.rails, Items.RAIL))
            {
                return false;
            }
            if (!railDown())
            {
                // Until the rail is in the cell the bot keeps the rail in its hand and keeps clicking, at its
                // own click rate, the way a player does until the rail goes down. A click the game refuses is
                // not a rail, so it is tried again.
                if (CartHand.placed(hand.click(cell.below(), Direction.UP)))
                {
                    stats.blocksPlaced++;
                }
                else
                {
                    return false;
                }
            }
            stage = Stage.CART;
            return false;
        }
        if (!hand.hold(loadout.cart, Items.TNT_MINECART))
        {
            return false;
        }
        if (!CartHand.placed(hand.click(cell, Direction.UP)))
        {
            return false;
        }
        rail = cell;
        cart = null;
        cell = null;
        stage = Stage.RAIL;
        return true;
    }

    /** True while the picked-out cell already has its rail in it. */
    private boolean railDown()
    {
        return cell != null && bot.level().getBlockState(cell).getBlock() instanceof BaseRailBlock;
    }

    /**
     * True when there is a cart out, the bot is out of the blast of it and it carries a bow with Flame on it,
     * which is the only thing a player can set a tnt minecart off with.
     */
    public boolean readyToLight(Loadout loadout, int difficulty)
    {
        return armed() && loadout.flameBow >= 0 && loadout.arrows > 0 && !outOfBlast(difficulty);
    }

    /** True while the bot has a cart out: one it laid, and whose rail is still under it. */
    public boolean armed()
    {
        return rail != null && railed();
    }

    /**
     * True while the cart that is out can still hurt the bot where it is standing, which is the state a cart
     * is in for the first moments after it goes down. It is the same {@link TntCartPlan#survivable} question the
     * crystal style asks about a crystal, asked about this cart from where the bot is standing now. It costs
     * the shared budget what a blast estimate with an exposure ray behind it is worth.
     */
    public boolean outOfBlast(int difficulty)
    {
        if (!armed() || !(bot.level() instanceof ServerLevel level))
        {
            return false;
        }
        Vec3 at = centreOfCell();
        BotBudget budget = BotBudget.instance();
        if (budget.join() < TntCartPlan.COST_PER_BLAST)
        {
            stats.starvedTicks++;
            return false;
        }
        TntCartPlan plan = new TntCartPlan(new LevelExplosionView(level));
        CrystalSearch.Side self = new CrystalSearch.Side();
        self.x = bot.getX();
        self.y = bot.getY();
        self.z = bot.getZ();
        self.health = bot.getHealth() + bot.getAbsorptionAmount();
        self.armor = bot.getArmorValue();
        self.toughness = (float) bot.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        self.epf = protectionAgainstBlasts(bot);
        boolean survivable = plan.survivable(self, at.x, at.y, at.z, difficulty);
        budget.spend(TntCartPlan.COST_PER_BLAST);
        stats.simulatedTicks += TntCartPlan.COST_PER_BLAST;
        return !survivable;
    }

    /**
     * True when the bot has laid a cart it has no way to light: a cart it cannot set off is a target standing
     * next to a live minecart, which is worse than no cart at all, so the bot forgets it and goes back to
     * whatever else it fights with.
     */
    public boolean spent(Loadout loadout)
    {
        return rail != null && (loadout.flameBow < 0 || loadout.arrows <= 0);
    }

    /**
     * The cart as the aim solver wants it: a box the size of a minecart, sitting on the rail it was laid on and
     * not moving, because a rail on the flat has no slope to roll down.
     */
    public ProjectileSim.Target cart()
    {
        Vec3 at = centreOfCell();
        ProjectileSim.Target cart = new ProjectileSim.Target();
        cart.x = at.x;
        cart.y = at.y;
        cart.z = at.z;
        cart.halfWidth = 0.48;
        cart.height = 0.94;
        return cart;
    }

    /** A perceived fighter as the plan wants it. */
    private static CrystalSearch.Side perceived(Perception.Snapshot snapshot, float health)
    {
        CrystalSearch.Side side = new CrystalSearch.Side();
        side.x = snapshot.x;
        side.y = snapshot.y;
        side.z = snapshot.z;
        side.vx = snapshot.vx;
        side.vy = snapshot.vy;
        side.vz = snapshot.vz;
        side.armor = snapshot.armor;
        side.toughness = snapshot.armorToughness;
        side.epf = snapshot.epf;
        side.health = health;
        return side;
    }

    /**
     * The enchantment protection factor of the armour a fighter is wearing against an explosion, which is what
     * the blast has to get through before it reaches its health.
     */
    public static float protectionAgainstBlasts(LivingEntity bot)
    {
        RegistryAccess registries = bot.registryAccess();
        Holder<Enchantment> protection = lookup(registries, "minecraft:protection");
        Holder<Enchantment> blast = lookup(registries, "minecraft:blast_protection");
        int levels = 0;
        int blastLevels = 0;
        for (EquipmentSlot slot : EquipmentSlot.values())
        {
            if (!slot.isArmor())
            {
                continue;
            }
            ItemStack piece = bot.getItemBySlot(slot);
            if (protection != null)
            {
                levels += EnchantmentHelper.getItemEnchantmentLevel(protection, piece);
            }
            if (blast != null)
            {
                blastLevels += EnchantmentHelper.getItemEnchantmentLevel(blast, piece);
            }
        }
        return CombatMath.epf(levels, blastLevels, true);
    }

    private static Holder<Enchantment> lookup(RegistryAccess registries, String id)
    {
        return registries.lookupOrThrow(Registries.ENCHANTMENT).get(Identifier.parse(id)).orElse(null);
    }
}
