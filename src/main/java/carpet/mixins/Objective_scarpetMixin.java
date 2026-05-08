package carpet.mixins;

import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Objective.class)
public interface Objective_scarpetMixin
{
    void setCriterion(ObjectiveCriteria criterion);
}