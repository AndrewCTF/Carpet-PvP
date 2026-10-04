package carpet.pvp.mace;

import carpet.pvp.BotBody;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/**
 * The actions a mace fighter needs that {@link BotBody} does not carry: using the item in its hand and
 * the elytra glide. Both go through the body and through the vanilla paths a player would drive them
 * with, so the aim, the click rate and the hotbar delay stay the ones every other bot lives with. The
 * item's own use cooldown is what keeps the bot from throwing two wind charges in one tick.
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
     * actually used: an item on cooldown, or an empty hand, is a click that does nothing.
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

    /** Opens or folds the elytra, which the action pack flies while the wings are worn on the chest. */
    public void glide(boolean open)
    {
        body.pack().setGlideEnabled(open);
    }
}
