package carpet.pvp.sim;

import java.util.List;

/**
 * The five difficulty levels, easiest first. Each level below the top one is the level above it with a shallower
 * search, a shorter swing and the human limits turned up, and the levels were then moved until measurement put
 * every one of them clearly above the one below: DifficultyPresetsTest is that measurement.
 */
public final class DifficultyPresets
{
    /**
     * What {@link Tuner} settled on: 40 generations of the (1+lambda) search from the defaults at 16 candidates a
     * generation, scored over 256 duels against the eight scripted opponents on seeds from 4000 upwards. The
     * defaults score 0.368 there and 0.361 on the held-out seeds at 5004000, these score 0.420 and 0.424.
     */
    public static final PlannerParams BEST =
            new PlannerParams(12, 48, 12000, 0.27755290065887456, 1.9822831355668893, 2.66453474345892,
                    0.15079970796158326, 1.5328570993171913);

    private DifficultyPresets()
    {
    }

    public static final DifficultyPreset BEGINNER =
            new DifficultyPreset("beginner", new PlannerParams(6, 6, 1500, 0.5, 1.0, 2.5, 0.2, 1.5), 4, 0.45);
    public static final DifficultyPreset AMATEUR =
            new DifficultyPreset("amateur", new PlannerParams(8, 6, 3000, 0.3, 1.0, 2.0, 0.1, 2.0), 3, 0.28);
    public static final DifficultyPreset SKILLED =
            new DifficultyPreset("skilled", new PlannerParams(10, 8, 4000, 0.25, 1.0, 1.5, 0.06, 2.4), 2, 0.22);
    public static final DifficultyPreset EXPERT =
            new DifficultyPreset("expert", new PlannerParams(10, 24, 9000, 0.22, 1.7, 2.2, 0.12, 1.8), 1, 0.08);
    public static final DifficultyPreset MASTER = new DifficultyPreset("master", BEST, 0, 0.02);

    /** Easiest first. */
    public static List<DifficultyPreset> ladder()
    {
        return List.of(BEGINNER, AMATEUR, SKILLED, EXPERT, MASTER);
    }
}