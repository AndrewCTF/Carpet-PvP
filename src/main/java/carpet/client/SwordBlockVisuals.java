package carpet.client;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class SwordBlockVisuals {
    private static final Map<Integer, Integer> timers = new HashMap<>();

    public static void tick() {
        if (timers.isEmpty()) return;
        Iterator<Map.Entry<Integer, Integer>> it = timers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> e = it.next();
            int v = e.getValue() - 1;
            if (v <= 0) it.remove(); else e.setValue(v);
        }
    }

    public static void activate(Player player, int ticks) {
        if (ticks <= 0) timers.remove(player.getId());
        else timers.put(player.getId(), ticks);
    }

    public static boolean isActive(AbstractClientPlayer p) {
        Integer left = timers.get(p.getId());
        return left != null && left > 0;
    }

    public static int remaining(AbstractClientPlayer p) {
        Integer left = timers.get(p.getId());
        return left == null ? 0 : Math.max(0, left);
    }
}
