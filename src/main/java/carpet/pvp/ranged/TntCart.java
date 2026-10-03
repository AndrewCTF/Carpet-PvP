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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Laying a rail and a tnt minecart next to a target and then setting it off with a flaming arrow.
 *
 * <p>Where to lay it comes from {@link TntCartPlan}, which asks every block the bot can reach what the blast
 * there would do to the target. The bot's own share of that blast is deliberately not part of the choice,
 * because a cart is laid beside the target and lit from a distance. What decides whether the bot lights anything
 * is {@link TntCartPlan#survivable}, the same never-kill-yourself rule the crystal style keeps, asked again from
 * where the bot is standing when it is about to shoot.</p>
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

    private final EntityPlayerMPFake bot;
    private final BotBody body;
    private final Bow bow;

    private BlockPos rail;
    private int untilPlan;

    public TntCart(EntityPlayerMPFake bot, BotBody body, Bow bow)
    {
        this.bot = bot;
        this.body = body;
        this.bow = bow;
    }

    /** The block the cart is on, or null while there is no cart out. */
    public BlockPos rail()
    {
        return rail;
    }

    /** One tick of bookkeeping between attempts. */
    public void tick()
    {
        if (untilPlan > 0)
        {
            untilPlan--;
        }
    }

    /** Forgets the cart, which is what a bot does when it is no longer interested in one. */
    public void forget()
    {
        rail = null;
        untilPlan = 0;
    }

    /**
     * Looks for a place to lay a cart and puts one there when it finds one, as long as the bot carries a cart
     * and a rail. Returns true on the tick a cart went down.
     */
    public boolean place(Perception.Snapshot me, Perception.Snapshot seen, int difficulty, Loadout loadout)
    {
        if (rail != null || loadout.cart < 0 || loadout.rails < 0 || untilPlan > 0)
        {
            return false;
        }
        untilPlan = PLAN_TICKS;
        if (!(bot.level() instanceof ServerLevel level))
        {
            return false;
        }
        LevelExplosionView view = new LevelExplosionView(level);
        TntCartPlan.Placement found = new TntCartPlan(view).choose(perceived(me, 0.0F), perceived(seen, seen.healthTotal()),
                SEARCH_X, SEARCH_Y, SEARCH_Z, difficulty);
        if (found == null || !view.supportsRail(found.x, found.y - 1, found.z))
        {
            return false;
        }
        if (!use(loadout.stack(loadout.rails), new BlockPos(found.x, found.y - 1, found.z), Direction.UP)
                || !railWentDown(level, found))
        {
            return false;
        }
        if (!use(loadout.stack(loadout.cart), new BlockPos(found.x, found.y, found.z), Direction.UP))
        {
            return false;
        }
        rail = new BlockPos(found.x, found.y, found.z);
        return true;
    }

    /**
     * True when there is a cart out, the bot is out of the blast of it and it carries a bow with Flame on it,
     * which is the only thing a player can set a tnt minecart off with.
     */
    public boolean readyToLight(Loadout loadout, int difficulty)
    {
        if (rail == null || loadout.flameBow < 0 || loadout.arrows <= 0)
        {
            return false;
        }
        if (!(bot.level() instanceof ServerLevel level))
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
        return plan.survivable(self, rail.getX() + 0.5, rail.getY() + 0.5, rail.getZ() + 0.5, difficulty);
    }

    /**
     * The cart as the aim solver wants it: a box the size of a minecart, sitting on the rail it was laid on and
     * not moving, because a rail on the flat has no slope to roll down.
     */
    public ProjectileSim.Target cart()
    {
        ProjectileSim.Target cart = new ProjectileSim.Target();
        cart.x = rail.getX() + 0.5;
        cart.y = rail.getY() + 0.0625;
        cart.z = rail.getZ() + 0.5;
        cart.halfWidth = 0.48;
        cart.height = 0.94;
        return cart;
    }

    /** One use of the held item on a face, and whether the game let it happen. */
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

    /** True when the rail is standing where the plan wanted it. */
    private static boolean railWentDown(ServerLevel level, TntCartPlan.Placement found)
    {
        return level.getBlockState(new BlockPos(found.x, found.y, found.z)).getBlock() instanceof BaseRailBlock;
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
     * The enchantment protection factor of the armour the bot is wearing against an explosion, which is what the
     * blast has to get through before it reaches its health.
     */
    public static float protectionAgainstBlasts(EntityPlayerMPFake bot)
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
