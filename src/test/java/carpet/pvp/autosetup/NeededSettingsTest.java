package carpet.pvp.autosetup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Two sessions of two players share {@code fakePlayerNavigation}. The first one to stop must not take
 * it away from the one still running, in either order of stopping.
 */
class NeededSettingsTest
{
    private static final String NAVIGATION = "fakePlayerNavigation";

    @Test
    void theSettingGoesBackWhenTheSessionThatTurnedItOnEnds()
    {
        NeededSettings needed = new NeededSettings();
        assertEquals(NAVIGATION + "=false", needed.hold("Ann", NAVIGATION, "false"));
        assertEquals("false", needed.release("Ann", NAVIGATION));
        assertNull(needed.release("Ann", NAVIGATION), "a second release has nothing to put back");
    }

    @Test
    void theFirstSessionToStopLeavesTheSettingOnForTheOther()
    {
        NeededSettings needed = new NeededSettings();
        assertEquals(NAVIGATION + "=false", needed.hold("Ann", NAVIGATION, "false"));
        // The second player finds it already on, and still has to put it back when she is done.
        assertEquals(NAVIGATION + "=false", needed.hold("Bo", NAVIGATION, "true"));
        assertNull(needed.release("Ann", NAVIGATION), "Bo is still fighting");
        assertEquals("false", needed.release("Bo", NAVIGATION));
    }

    @Test
    void theSecondSessionToStopLeavesTheSettingOnForTheOther()
    {
        NeededSettings needed = new NeededSettings();
        needed.hold("Ann", NAVIGATION, "false");
        needed.hold("Bo", NAVIGATION, "true");
        assertNull(needed.release("Bo", NAVIGATION), "Ann is still fighting");
        assertEquals("false", needed.release("Ann", NAVIGATION));
    }

    @Test
    void theFileOfACrashedSessionKeepsTheSettingUntilItsPlayerIsGivenBack()
    {
        NeededSettings needed = new NeededSettings();
        needed.hold("Ann", NAVIGATION, "false");
        // The server went down with Ann's session running: nothing is put back, and the file that is
        // left behind says what Ann needs held for her.
        needed.forget("Ann");
        assertNull(needed.release("Ann", NAVIGATION), "Ann's claim was dropped with the session");
        needed.holdAll("Ann", List.of(NAVIGATION + "=false"));
        assertEquals("false", needed.release("Ann", NAVIGATION));
    }

    @Test
    void forgettingOneOfTwoKeepsWhatTheOtherStillNeeds()
    {
        NeededSettings needed = new NeededSettings();
        needed.hold("Ann", NAVIGATION, "false");
        needed.hold("Bo", NAVIGATION, "true");
        // Ann's session is lost, but her file is read again and says what she needs held for her.
        needed.forget("Ann");
        needed.holdAll("Ann", List.of(NAVIGATION + "=false"));
        assertNull(needed.release("Ann", NAVIGATION), "Bo is still fighting");
        assertEquals("false", needed.release("Bo", NAVIGATION));
    }

    @Test
    void aSettingThisServerDoesNotHaveIsNotHeld()
    {
        NeededSettings needed = new NeededSettings();
        assertNull(needed.hold("Ann", "noSuchSetting", null));
        assertNull(needed.release("Ann", "noSuchSetting"));
    }
}
