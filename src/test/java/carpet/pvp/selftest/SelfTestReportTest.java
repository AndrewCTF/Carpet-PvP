package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTestReport.Result;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfTestReportTest
{
    private static final List<String> KNOWN = List.of("spawn", "nav_goto");

    @Test
    void allExpandsToEveryKnownScenario()
    {
        assertEquals(KNOWN, SelfTestReport.parseNames("all", KNOWN));
    }

    @Test
    void namesAreTrimmedDeduplicatedAndUnknownOnesKept()
    {
        assertEquals(List.of("nav_goto", "no_such_scenario"), SelfTestReport.parseNames(" nav_goto, no_such_scenario,,nav_goto", KNOWN));
    }

    @Test
    void oneFailedScenarioFailsTheReport()
    {
        Result good = new Result("spawn", true, 3, "ok");
        Result bad = new Result("no_such_scenario", false, 0, "unknown scenario");

        JsonObject report = JsonParser.parseString(SelfTestReport.toJson("26.2", List.of(good, bad))).getAsJsonObject();

        assertEquals("26.2", report.get("minecraft").getAsString());
        assertFalse(report.get("passed").getAsBoolean());
        JsonObject second = report.getAsJsonArray("scenarios").get(1).getAsJsonObject();
        assertEquals("no_such_scenario", second.get("name").getAsString());
        assertFalse(second.get("passed").getAsBoolean());
        assertEquals(0, second.get("ticks").getAsInt());
        assertEquals("unknown scenario", second.get("detail").getAsString());
        assertTrue(SelfTestReport.allPassed(List.of(good)));
    }

    @Test
    void runningNothingIsNotAPass()
    {
        assertFalse(SelfTestReport.allPassed(List.of()));
    }
}
