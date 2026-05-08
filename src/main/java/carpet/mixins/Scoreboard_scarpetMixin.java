package carpet.mixins;

import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.Map;

@Mixin(net.minecraft.world.scores.Scoreboard.class)
public interface Scoreboard_scarpetMixin
{
    @Invoker("getObjectivesByCriterion")
    Map<ObjectiveCriteria, List<Objective>> invokeGetObjectivesByCriterion(ObjectiveCriteria criterion);
}