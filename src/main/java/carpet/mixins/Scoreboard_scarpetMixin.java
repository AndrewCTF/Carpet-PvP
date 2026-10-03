package carpet.mixins;

//? if >=26.1 {
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
//?} else {
/*import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
*///?}
import org.spongepowered.asm.mixin.Mixin;
//~ if >=26.1 'Accesso' -> 'Invoke'
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
//? if >=26.1 {
import java.util.Map;
//?} else {
/*import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
*///?}

//? if >=26.1 {
@Mixin(net.minecraft.world.scores.Scoreboard.class)
public interface Scoreboard_scarpetMixin
{
    @Invoker("getObjectivesByCriterion")
    Map<ObjectiveCriteria, List<Objective>> invokeGetObjectivesByCriterion(ObjectiveCriteria criterion);
}
//?} else {
/*@Mixin(Scoreboard.class)
public interface Scoreboard_scarpetMixin {
    @Accessor("objectivesByCriteria")
    Reference2ObjectMap<ObjectiveCriteria, List<Objective>> getObjectivesByCriterion();
}
*///?}