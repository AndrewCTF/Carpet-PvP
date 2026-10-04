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
import net.minecraft.world.phys.Vec3;

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
     * True while the bot still has the button down but the game has stopped counting the charge, which is
     * what happens once the weapon's own window has run out. A player lets go of the button for a tick and
     * starts the charge again, because nothing more can land out of the one that ended.
     */
    public boolean spent()
    {
        return body.holdingItem() && !bot.isUsingItem();
    }

    /**
     * How far the bot's eyes are from the nearest point of the target's box, which is the distance the reach
     * window is measured against, both here and in the game.
     */
    public double reachOf(LivingEntity target)
    {
        AABB box = target.getBoundingBox();
        double dx = Math.max(Math.max(box.minX - bot.getX(), bot.getX() - box.maxX), 0.0);
        double dy = Math.max(Math.max(box.minY - bot.getEyeY(), bot.getEyeY() - box.maxY), 0.0);
        double dz = Math.max(Math.max(box.minZ - bot.getZ(), bot.getZ() - box.maxZ), 0.0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** True when the target is inside the reach window of the spear, which the bot reads off the live target. */
    public boolean inReach(LivingEntity target)
    {
        return SpearMath.inReach(reachOf(target));
    }

    /**
     * Whether the two are closing on each other fast enough for a thrust to do any damage, which is the gate the
     * game puts on the damage itself and nothing else. The charge age is deliberately left out: the bot charges
     * while it runs in, so by the time it arrives the wind-up is always behind it, and what decides whether the
     * run is worth anything is the speed in front of the spear.
     */
    public boolean fast(LivingEntity target, ItemStack spear)
    {
        KineticWeapon.Condition condition = damageCondition(spear);
        return condition != null && closingSpeed(target) >= condition.minRelativeSpeed();
    }

    /**
     * How fast the bot and the target close on each other along the direction the bot is looking, in blocks a
     * second, which is what the game measures: the attacker's motion along the view, less the target's, never
     * below nothing.
     *
     * <p>The game reads that motion off {@code getKnownSpeed}, which is a velocity of a block <em>a tick</em>,
     * and {@code KineticWeapon.getMotion} is what turns it into a block a second before the condition sees it.
     * The gate on the damage is written in that unit as well: a relative speed of 4.6 is a closing speed of 4.6
     * blocks a second, which is what a sprint is worth. Leaving it per tick would make every gate twenty times
     * too high and the thrust would never be worth committing to.</p>
     */
    public double closingSpeed(LivingEntity target)
    {
        Vec3 mine = bot.getKnownSpeed();
        Vec3 theirs = target.getKnownSpeed();
        return SpearMath.TICKS_A_SECOND * SpearMath.closingSpeed(
                SpearMath.along(mine.x, mine.y, mine.z, bot.getYRot(), bot.getXRot()),
                SpearMath.along(theirs.x, theirs.y, theirs.z, bot.getYRot(), bot.getXRot()));
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
