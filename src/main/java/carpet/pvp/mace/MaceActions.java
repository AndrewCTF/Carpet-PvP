package carpet.pvp.mace;

import carpet.pvp.BotBody;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/**
 * The actions a mace fighter needs that {@link BotBody} does not carry: using the item in its hand, opening
 * and folding the wings, and the glide the action pack flies while they are worn. All of them go through the
 * body and through the vanilla paths a player would drive them with, so the aim, the click rate and the hotbar
 * delay stay the ones every other bot lives with. The item's own use cooldown is what keeps the bot from
 * throwing two wind charges in one tick.
 */
public final class MaceActions
{
    private final BotBody body;

    public MaceActions(BotBody body)
    {
        this.body = body;
    }

    /**
     * Uses the item in the main hand once, as a click on the use key does. Returns whether the item was
     * actually used: an item on cooldown, or an empty hand, is a click that does nothing. This is also how a
     * chest piece is put on and taken off, since using an equippable from the hand exchanges it with the worn
     * one, which is how a player swaps the elytra for the chestplate it hides.
     */
    public boolean useMainHand()
    {
        ItemStack stack = body.bot().getMainHandItem();
        if (stack.isEmpty())
        {
            return false;
        }
        InteractionResult result = body.bot().gameMode.useItem(body.bot(), body.bot().level(), stack,
                InteractionHand.MAIN_HAND);
        return result.consumesAction();
    }

    /**
     * Opens the wings, the double tap a player does with the space bar. The game refuses it without an elytra
     * on the chest, without being off the ground and without a free hand of the elytra, so this is the vanilla
     * gate rather than a rule of this class. Returns whether the wings are open afterwards.
     */
    public boolean deploy()
    {
        if (!body.bot().isFallFlying())
        {
            body.bot().tryToStartFallFlying();
        }
        return body.bot().isFallFlying();
    }

    /**
     * Folds the wings. A smash does not count while the wings are open, and the player way of taking them off
     * is to use the chest piece they were exchanged with, which leaves the elytra in the hand; folding the
     * state is what that ends up doing, one tick later.
     */
    public boolean fold()
    {
        if (!body.bot().isFallFlying())
        {
            return false;
        }
        body.bot().stopFallFlying();
        return true;
    }

    /** Opens or folds the elytra glide, which the action pack flies while the wings are worn on the chest. */
    public void glide(boolean open)
    {
        body.pack().setGlideEnabled(open);
    }
}
