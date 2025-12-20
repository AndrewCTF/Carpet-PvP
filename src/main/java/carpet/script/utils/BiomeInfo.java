package carpet.script.utils;

import carpet.script.external.Vanilla;
import carpet.script.value.ListValue;
import carpet.script.value.NumericValue;
import carpet.script.value.StringValue;
import carpet.script.value.Value;
import carpet.script.value.ValueConversions;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

public class BiomeInfo
{
    public static final Map<String, BiFunction<ServerLevel, Biome, Value>> biomeFeatures = createBiomeFeatures();

    private static Map<String, BiFunction<ServerLevel, Biome, Value>> createBiomeFeatures()
    {
        Map<String, BiFunction<ServerLevel, Biome, Value>> map = new HashMap<>();
        map.put("tags", (w, b) -> ListValue.wrap(w.registryAccess().lookupOrThrow(Registries.BIOME).getTags().filter(p -> p.stream().anyMatch(h -> h.value() == b)).map(ValueConversions::of)));
        map.put("temperature", (w, b) -> NumericValue.of(b.getBaseTemperature()));
        map.put("fog_color", (w, b) -> Value.NULL);
        map.put("foliage_color", (w, b) -> ValueConversions.ofRGB(b.getFoliageColor()));
        map.put("sky_color", (w, b) -> Value.NULL);
        map.put("water_color", (w, b) -> ValueConversions.ofRGB(b.getWaterColor()));
        map.put("water_fog_color", (w, b) -> Value.NULL);
        map.put("humidity", (w, b) -> NumericValue.of(Vanilla.Biome_getClimateSettings(b).downfall()));
        map.put("precipitation", (w, b) -> StringValue.of(b.getPrecipitationAt(new BlockPos(0, w.getSeaLevel(), 0), w.getSeaLevel()).name().toLowerCase(Locale.ROOT)));
        map.put("features", (w, b) -> {
            Registry<ConfiguredFeature<?, ?>> registry = w.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
            return ListValue.wrap(
                    b.getGenerationSettings().features().stream().map(step ->
                            ListValue.wrap(step.stream().map(cfp ->
                                    ValueConversions.of(registry.getKey(cfp.value().feature().value())))
                            )
                    )
            );
        });
        return map;
    }
}
