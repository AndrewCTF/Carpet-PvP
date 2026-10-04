package carpet.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DelayedTasksTest
{
    @AfterEach
    void clear()
    {
        DelayedTasks.clear();
    }

    @Test
    void aTaskMayScheduleAnotherWhileItRuns()
    {
        // a fake player's death does this: the respawn task schedules a follow-up task
        List<String> ran = new ArrayList<>();
        DelayedTasks.add(1, () ->
        {
            ran.add("first");
            DelayedTasks.add(2, () -> ran.add("follow-up"));
        });
        DelayedTasks.add(1, () -> ran.add("second"));

        DelayedTasks.runDue(1);
        assertEquals(List.of("first", "second"), ran);
        DelayedTasks.runDue(2);
        assertEquals(List.of("first", "second", "follow-up"), ran);
    }

    @Test
    void aTaskRunsOnceAndNotBeforeItIsDue()
    {
        int[] runs = {0};
        DelayedTasks.add(5, () -> runs[0]++);
        DelayedTasks.runDue(4);
        assertEquals(0, runs[0]);
        DelayedTasks.runDue(5);
        DelayedTasks.runDue(6);
        assertEquals(1, runs[0]);
    }
}
