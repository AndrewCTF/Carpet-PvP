package carpet.pvp.selftest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Scenario selection and report format of the self-test. No Minecraft classes, so it is unit testable.
 */
public final class SelfTestReport
{
    public record Result(String name, boolean passed, int ticks, String detail) {}

    private SelfTestReport() {}

    /**
     * Expands the comma-separated {@code carpet.selftest} value; {@code all} stands for every known scenario.
     * Unknown names are kept, so that the runner reports them as failures instead of skipping them.
     */
    public static List<String> parseNames(String property, List<String> known)
    {
        List<String> names = new ArrayList<>();
        for (String raw : property.split(","))
        {
            String name = raw.trim();
            for (String expanded : name.equals("all") ? known : List.of(name))
            {
                if (!expanded.isEmpty() && !names.contains(expanded)) names.add(expanded);
            }
        }
        return names;
    }

    /** A run that executed nothing is not a pass. */
    public static boolean allPassed(List<Result> results)
    {
        return !results.isEmpty() && results.stream().allMatch(Result::passed);
    }

    public static String toJson(String minecraftVersion, List<Result> results)
    {
        JsonArray scenarios = new JsonArray();
        for (Result result : results)
        {
            JsonObject scenario = new JsonObject();
            scenario.addProperty("name", result.name());
            scenario.addProperty("passed", result.passed());
            scenario.addProperty("ticks", result.ticks());
            scenario.addProperty("detail", result.detail());
            scenarios.add(scenario);
        }
        JsonObject report = new JsonObject();
        report.addProperty("minecraft", minecraftVersion);
        report.addProperty("passed", allPassed(results));
        report.add("scenarios", scenarios);
        return new GsonBuilder().setPrettyPrinting().create().toJson(report);
    }
}
