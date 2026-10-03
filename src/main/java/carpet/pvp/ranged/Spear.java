package carpet.pvp.ranged;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.sim.SpearMath;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.phys.AABB;

/**
 * The thrust of a charged spear.
 *
 * <p>A spear is not aimed with a click: holding the use key charges it, and on every tick of that charge the
 * game sweeps whatever the view reaches along the spear's own reach and hurts it by the attacker's plain base
 * damage plus the speed at which the two bodies are closing on each other. The reach, the wind-up before the
 * first thrust can land, the charge after which it stops landing at all and the speed it takes are all in the
 * weapon's own {@link KineticWeapon} component, so they are read off the stack rather than guessed at here.</p>
 */
public final class Spear
{
    private final EntityPlayerMPFake bot;
    private final BotBody body;

    public Spear(EntityPlayerMPFake bot, BotBody body)
    {
        this.bot = bot;
        this.body = body;
    }

    /** Ticks the charge has been held for, which is the wind-up the game counts. */
    public int held()
    {
        return bot.isUsingItem() ? bot.getTicksUsingItem() : 0;
    }

    /** Starts or stops the charge, which is holding or letting go of the use button. */
    public void charge(boolean charging)
    {
        if (charging)
        {
            body.holdItem();
        }
        else
        {
            body.releaseItem();
        }
    }

    /**
     * Whether a thrust at the live target would do anything, which is the model a bot asks before it commits to
     * the run. The gate is the one the game puts on the damage itself: a charge that is long enough past the
     * wind-up and a closing speed over the material's threshold.
     */
    public boolean worthIt(LivingEntity target, ItemStack spear)
    {
        KineticWeapon.Condition condition = damageCondition(spear);
        if (condition == null)
        {
            return false;
        }
        int charge = held() - windUp(spear);
        if (charge < 0)
        {
            return false;
        }
        double speed = closingSpeed(target);
        return SpearMath.damages(charge, condition.maxDurationTicks(), speed, condition.minRelativeSpeed());
    }

    /** The health a landed thrust would take off the live target, which is what the run is being judged on. */
    public float damageOf(LivingEntity target, ItemStack spear)
    {
        KineticWeapon weapon = spear.get(DataComponents.KINETIC_WEAPON);
        if (weapon == null)
        {
            return 0.0F;
        }
        // The game builds this on the attacker's plain base damage, which carries none of the item's modifiers.
        double base = bot.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
        return SpearMath.thrustDamage(base, closingSpeed(target), weapon.damageMultiplier());
    }

    /** True when the target is inside the reach window of the spear, which the bot reads off the live target. */
    public boolean inReach(LivingEntity target)
    {
        AABB box = target.getBoundingBox();
        return SpearMath.inReach(bot.getX(), bot.getEyeY(), bot.getZ(), box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ);
    }

    /**
     * How fast the bot and the target close on each other along the direction the bot is looking, which is what
     * the game measures: the attacker's motion along the view, less the target's, never below nothing.
     */
    public double closingSpeed(LivingEntity target)
    {
        double mine = SpearMath.along(bot.getDeltaMovement().x, bot.getDeltaMovement().y, bot.getDeltaMovement().z,
                bot.getYRot(), bot.getXRot());
        double theirs = SpearMath.along(target.getDeltaMovement().x, target.getDeltaMovement().y,
                target.getDeltaMovement().z, bot.getYRot(), bot.getXRot());
        return SpearMath.closingSpeed(mine, theirs);
    }

    /**
     * How long one charge is good for: the thrust is only behind its gate while the charge is younger than
     * this, so a bot that has held on for longer has to let go and start again, as a player has to.
     */
    public static int chargeWindow(ItemStack spear)
    {
        KineticWeapon.Condition condition = damageCondition(spear);
        return condition == null ? 0 : condition.maxDurationTicks() + windUp(spear);
    }

    /** Ticks the weapon holds the spear back before the first thrust can land. */
    public static int windUp(ItemStack spear)
    {
        KineticWeapon weapon = spear.get(DataComponents.KINETIC_WEAPON);
        return weapon == null ? 0 : weapon.delayTicks();
    }

    /** The gate the damage itself is behind, or null for a stack that is not a spear. */
    private static KineticWeapon.Condition damageCondition(ItemStack spear)
    {
        KineticWeapon weapon = spear.get(DataComponents.KINETIC_WEAPON);
        return weapon == null ? null : weapon.damageConditions().orElse(null);
    }
}
