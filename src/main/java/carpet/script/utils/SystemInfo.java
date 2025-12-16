package carpet.script.utils;

import carpet.script.CarpetContext;
import carpet.script.CarpetScriptHost;
import carpet.script.external.Carpet;
import carpet.script.external.Vanilla;
import carpet.script.value.BooleanValue;
import carpet.script.value.EntityValue;
import carpet.script.value.ListValue;
import carpet.script.value.MapValue;
import carpet.script.value.NumericValue;
import carpet.script.value.StringValue;
import carpet.script.value.Value;
import carpet.script.value.ValueConversions;
import com.sun.management.OperatingSystemMXBean;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec2;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public class SystemInfo
{
    private static final Map<String, Function<CarpetContext, Value>> options = new HashMap<>();

    static
    {
        options.put("app_name", c ->
        {
            String name = c.host.getName();
            return name == null ? Value.NULL : new StringValue(name);
        });
        options.put("app_list", c -> ListValue.wrap(((CarpetScriptHost) c.host).scriptServer().modules.keySet().stream().filter(Objects::nonNull).map(StringValue::new)));
        options.put("app_scope", c -> StringValue.of((c.host).isPerUser() ? "player" : "global"));
        options.put("app_players", c -> ListValue.wrap(c.host.getUserList().stream().map(StringValue::new)));

        options.put("world_name", c -> new StringValue(c.server().getWorldData().getLevelName()));
        options.put("world_seed", c -> new NumericValue(c.level().getSeed()));
        options.put("server_motd", c -> StringValue.of(c.server().getMotd()));
        options.put("world_path", c -> StringValue.of(c.server().getWorldPath(LevelResource.ROOT).toString()));
        options.put("world_folder", c -> {
            Path serverPath = c.server().getWorldPath(LevelResource.ROOT);
            int nodeCount = serverPath.getNameCount();
            if (nodeCount < 2)
            {
                return Value.NULL;
            }
            String tlf = serverPath.getName(nodeCount - 2).toString();
            return StringValue.of(tlf);
        });
        options.put("world_dimensions", c -> ListValue.wrap(c.server().levelKeys().stream().map(k -> ValueConversions.of(k.location()))));
        options.put("world_spawn_point", (CarpetContext c) -> ValueConversions.of(c.server().getRespawnData().pos()));

        options.put("world_bottom", c -> new NumericValue(c.level().getMinY()));

        options.put("world_top", c -> new NumericValue(c.level().getMaxY()));

        options.put("world_center", c -> {
            WorldBorder worldBorder = c.level().getWorldBorder();
            return ListValue.fromTriple(worldBorder.getCenterX(), 0, worldBorder.getCenterZ());
        });

        options.put("world_size", c -> new NumericValue(c.level().getWorldBorder().getSize() / 2));

        options.put("world_max_size", c -> new NumericValue(c.level().getWorldBorder().getAbsoluteMaxSize()));

        options.put("world_time", c -> new NumericValue(c.level().getGameTime()));

        options.put("game_difficulty", c -> StringValue.of(c.server().getWorldData().getDifficulty().getKey()));
        options.put("game_hardcore", c -> BooleanValue.of(c.server().getWorldData().isHardcore()));
        options.put("game_storage_format", c -> StringValue.of(c.server().getWorldData().getStorageVersionName(c.server().getWorldData().getVersion())));
        options.put("game_default_gamemode", c -> StringValue.of(c.server().getDefaultGameType().getName()));
        options.put("game_max_players", c -> new NumericValue(c.server().getMaxPlayers()));
        options.put("game_view_distance", c -> new NumericValue(c.server().getPlayerList().getViewDistance()));
        options.put("game_mod_name", c -> StringValue.of(c.server().getServerModName()));
        options.put("game_version", c -> StringValue.of(c.server().getServerVersion()));
        options.put("game_target", c -> StringValue.of(String.format("1.%d.%d",
                Vanilla.MinecraftServer_getReleaseTarget(c.server())[0],
                Vanilla.MinecraftServer_getReleaseTarget(c.server())[1])));
        options.put("game_protocol", c -> NumericValue.of(SharedConstants.getProtocolVersion()));
        options.put("game_major_target", c -> NumericValue.of(Vanilla.MinecraftServer_getReleaseTarget(c.server())[0]));
        options.put("game_minor_target", c -> NumericValue.of(Vanilla.MinecraftServer_getReleaseTarget(c.server())[1]));
        // 1.21.8: WorldVersion API changed; provide conservative placeholders to compile
        options.put("game_stable", c -> BooleanValue.FALSE);
        options.put("game_data_version", c -> NumericValue.of(0));
        options.put("game_pack_version", c -> NumericValue.of(0));

        options.put("server_ip", c -> StringValue.of(c.server().getLocalIp()));
        options.put("server_whitelisted", c -> BooleanValue.of(c.server().isEnforceWhitelist()));
        options.put("server_whitelist", c -> {
            MapValue whitelist = new MapValue(Collections.emptyList());
            for (String s : c.server().getPlayerList().getWhiteListNames())
            {
                whitelist.append(StringValue.of(s));
            }
            return whitelist;
        });
        options.put("server_banned_players", c -> {
            MapValue whitelist = new MapValue(Collections.emptyList());
            for (String s : c.server().getPlayerList().getBans().getUserList())
            {
                whitelist.append(StringValue.of(s));
            }
            return whitelist;
        });
        options.put("server_banned_ips", c -> {
            MapValue whitelist = new MapValue(Collections.emptyList());
            for (String s : c.server().getPlayerList().getIpBans().getUserList())
            {
                whitelist.append(StringValue.of(s));
            }
            return whitelist;
        });
        options.put("server_dev_environment", c -> BooleanValue.of(Vanilla.isDevelopmentEnvironment()));
        options.put("server_mods", c -> Vanilla.getServerMods(c.server()));
        options.put("server_last_tick_times", c -> {
            //assuming we are in the tick world section
            // might be off one tick when run in the off tasks or asynchronously.
            int currentReportedTick = c.server().getTickCount() - 1;
            List<Value> ticks = new ArrayList<>(100);
            long[] tickArray = c.server().getTickTimesNanos();
            for (int i = currentReportedTick + 100; i > currentReportedTick; i--)
            {
                ticks.add(new NumericValue((tickArray[i % 100]) / 1000000.0));
            }
            return ListValue.wrap(ticks);
        });

        options.put("java_max_memory", c -> new NumericValue(Runtime.getRuntime().maxMemory()));
        options.put("java_allocated_memory", c -> new NumericValue(Runtime.getRuntime().totalMemory()));
        options.put("java_used_memory", c -> new NumericValue(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
        options.put("java_cpu_count", c -> new NumericValue(Runtime.getRuntime().availableProcessors()));
        options.put("java_version", c -> StringValue.of(System.getProperty("java.version")));
        options.put("java_bits", c -> {
            for (String property : new String[]{"sun.arch.data.model", "com.ibm.vm.bitmode", "os.arch"})
            {
                String value = System.getProperty(property);
                if (value != null && value.contains("64"))
                {
                    return new NumericValue(64);
                }
            }
            return new NumericValue(32);
        });
        options.put("java_system_cpu_load", c -> {
            OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(
                    OperatingSystemMXBean.class);
            return new NumericValue(osBean.getCpuLoad());
        });
        options.put("java_process_cpu_load", c -> {
            OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(
                    OperatingSystemMXBean.class);
            return new NumericValue(osBean.getProcessCpuLoad());
        });
        options.put("world_carpet_rules", c -> Carpet.getAllCarpetRules());
        options.put("world_gamerules", c -> {
            Map<Value, Value> rules = new HashMap<>();
            GameRules gameRules = c.level().getGameRules();
            gameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor()
            {
                @Override
                public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type)
                {
                    rules.put(StringValue.of(key.getId()), StringValue.of(gameRules.getRule(key).toString()));
                }
            });
            return MapValue.wrap(rules);
        });
        options.put("world_min_spawning_light", c -> NumericValue.of(c.level().dimensionType().monsterSpawnBlockLightLimit()));

        options.put("source_entity", c -> EntityValue.of(c.source().getEntity()));
        options.put("source_position", c -> ValueConversions.of(c.source().getPosition()));
        options.put("source_dimension", c -> ValueConversions.of(c.level()));
        options.put("source_rotation", c -> {
            Vec2 rotation = c.source().getRotation();
            return ListValue.of(new NumericValue(rotation.x), new NumericValue(rotation.y));
        });
        options.put("scarpet_version", c -> StringValue.of(Carpet.getCarpetVersion()));
    }

    public static Value get(String what, CarpetContext cc)
    {
        return options.getOrDefault(what, c -> null).apply(cc);
    }

    public static Value getAll()
    {
        return ListValue.wrap(options.keySet().stream().map(StringValue::of));
    }

}
