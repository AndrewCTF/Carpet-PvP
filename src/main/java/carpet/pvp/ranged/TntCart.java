package carpet.pvp.ranged;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.Perception;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.ProjectileSim;
import carpet.pvp.sim.CrystalSearch;
import carpet.pvp.sim.TntCartPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
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
 * <p>Laying the rail and the cart are ordinary uses of the item on a block, which is what a player does with the
 * right mouse button, so both go through the game mode rather than putting blocks into the world by hand.
 * Nothing here lights the cart either: a flaming arrow does, and that arrow is the bot's own bow shot.</p>
 */
public final class TntCart
{
    /** Half the volume around the bot the plan looks at, in blocks. */
    public static final int SEARCH_X = 4;
    public static final int SEARCH_Y = 2;
    public static final int SEARCH_Z = 4;
    /** Ticks between two looks for a place to lay the cart. */
    public static final int PLAN_TICKS = 10;
    /** Ticks a picked-out cell is given for its rail and its cart before the bot looks for another one. */
    public static final int LAY_TICKS = 40;
    /** How far around the armed cell a cart is looked for, which is as far as one can be knocked. */
    private static final double SEARCH = 2.0D;

    private final EntityPlayerMPFake bot;
    private final BotBody body;
    private final Bow bow;

    /** The cell the cart is armed in, and the cart itself, which is what a shot has to be aimed at. */
    private BlockPos rail;
    private MinecartTNT cart;
    /** The cell the plan picked out, waiting for its rail and then its cart. */
    private BlockPos cell;
    private int untilPlan;

    public TntCart(EntityPlayerMPFake bot, BotBody body, Bow bow)
    {
        this.bot = bot;
        this.body = body;
        this.bow = bow;
    }

    /**
     * The cart that is out, looked up again every tick: a cart is an entity, so it can be knocked along its
     * rail, and aiming at the cell it was armed in would be aiming at where it used to be. Null while there is
     * no cart, which includes the tick one goes off on, because a blast takes the cart with it.
     */
    public MinecartTNT rail()
    {
        if (cart == null || cart.isRemoved())
        {
            cart = null;
            if (rail != null)
            {
                for (MinecartTNT found : bot.level().getEntitiesOfClass(MinecartTNT.class,
                        new AABB(rail).inflate(SEARCH)))
                {
                    cart = found;
                    break;
                }
            }
        }
        if (cart == null)
        {
            // The cart is gone: either the bot's shot set it off or something else did, and either way there
            // is no trap left to run from.
            rail = null;
        }
        return cart;
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
        }
    }

    /** Forgets the cart, which is what a bot does when it is no longer interested in one. */
    public void forget()
    {
        rail = null;
        cart = null;
        cell = null;
        untilPlan = 0;
    }

    /**
     * Looks for a cell to put a cart in and remembers it until it has been laid, at most every
     * {@link #PLAN_TICKS} ticks. A cart has to go down within a reach of the bot, so this answers where, and
     * {@link #layRail} and {@link #layCart} are what put the two items down there.
     */
    public boolean plan(Perception.Snapshot me, Perception.Snapshot seen, int difficulty, Loadout loadout)
    {
        if (cell != null || rail != null || loadout.cart < 0 || loadout.rails < 0 || untilPlan > 0
                || !(bot.level() instanceof ServerLevel level))
        {
            return false;
        }
        untilPlan = PLAN_TICKS;
        LevelExplosionView view = new LevelExplosionView(level);
        TntCartPlan.Placement found = new TntCartPlan(view).choose(perceived(me, 0.0F), perceived(seen, seen.healthTotal()),
                SEARCH_X, SEARCH_Y, SEARCH_Z, difficulty);
        if (found == null || !view.supportsRail(found.x, found.y - 1, found.z))
        {
            return false;
        }
        cell = new BlockPos(found.x, found.y, found.z);
        untilPlan = LAY_TICKS;
        return true;
    }

    /**
     * Lays the rail and the cart on the cell the plan picked out, and returns true on the tick a cart went down.
     *
     * <p>Both are uses of the item on a block, the way a player puts them down, so they go through the game mode
     * with the stack to place rather than with whatever the hand happens to hold: the bot cannot be trusted to
     * have selected the right slot, and the game reads the stack it is given for what to place.</p>
     */
    public boolean place(Loadout loadout)
    {
        if (cell == null || rail != null || loadout.cart < 0 || loadout.rails < 0)
        {
            return false;
        }
        if (!use(loadout.stack(loadout.rails), cell.below(), Direction.UP)
                || !railDown()
                || !use(loadout.stack(loadout.cart), cell, Direction.UP))
        {
            return false;
        }
        rail = cell;
        cart = null;
        cell = null;
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
        return rail() != null && loadout.flameBow >= 0 && loadout.arrows > 0 && !outOfBlast(difficulty);
    }

    /**
     * True while the cart that is out can still hurt the bot where it is standing, which is the state a cart
     * is in for the first moments after it goes down. It is the same {@link TntCartPlan#survivable} question the
     * crystal style asks about a crystal, asked about this cart from where the bot is standing now.
     */
    public boolean outOfBlast(int difficulty)
    {
        MinecartTNT minecart = rail();
        if (minecart == null || !(bot.level() instanceof ServerLevel level))
        {
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
        return !plan.survivable(self, minecart.getX(), minecart.getY(), minecart.getZ(), difficulty);
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
        MinecartTNT minecart = rail();
        ProjectileSim.Target cart = new ProjectileSim.Target();
        cart.x = minecart.getX();
        cart.y = minecart.getY();
        cart.z = minecart.getZ();
        cart.halfWidth = 0.48;
        cart.height = 0.94;
        return cart;
    }

    /**
     * One use of an item on a face, and whether the game let it happen.
     *
     * <p>The stack is handed to the game rather than taken out of the hand, because that is what decides what
     * goes down: {@code UseOnContext} is built from the hand, and what a player ends up holding afterwards is
     * corrected from what the use reports. A bot that has not selected the rail and the cart first therefore
     * still gets the rail and the cart down, which is what {@link #place} relies on.</p>
     */
    private boolean use(ItemStack stack, BlockPos against, Direction face)
    {
        if (stack.isEmpty())
        {
            return false;
        }
        bot.resetLastActionTime();
        Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5D, face.getStepY() * 0.5D,
                face.getStepZ() * 0.5D);
        BlockHitResult hitResult = new BlockHitResult(hit, face, against, false);
        InteractionResult outcome = bot.gameMode.useItemOn(bot, bot.level(), stack, InteractionHand.MAIN_HAND, hitResult);
        return outcome.consumesAction();
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
