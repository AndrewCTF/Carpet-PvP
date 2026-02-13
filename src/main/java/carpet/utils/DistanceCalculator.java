package carpet.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class DistanceCalculator
{
    public static final HashMap<String, Vec3> START_POINT_STORAGE = new HashMap<>();

    public static boolean hasStartingPoint(CommandSourceStack source)
    {
        return START_POINT_STORAGE.containsKey(source.getTextName());
    }

    public static List<Component> findDistanceBetweenTwoPoints(Vec3 pos1, Vec3 pos2)
    {
        double dx = Mth.abs((float)pos1.x-(float)pos2.x);
        double dy = Mth.abs((float)pos1.y-(float)pos2.y);
        double dz = Mth.abs((float)pos1.z-(float)pos2.z);
        double manhattan = dx+dy+dz;
        double spherical = Math.sqrt(dx*dx + dy*dy + dz*dz);
        double cylindrical = Math.sqrt(dx*dx + dz*dz);
        List<Component> res = new ArrayList<>();
        res.add(Messenger.c("w Distance between ",
                Messenger.tp("c",pos1),"w  and ",
                Messenger.tp("c",pos2),"w :"));
        res.add(Messenger.c("w  - Spherical: ", String.format("wb %.2f", spherical)));
        res.add(Messenger.c("w  - Cylindrical: ", String.format("wb %.2f", cylindrical)));
        res.add(Messenger.c("w  - Manhattan: ", String.format("wb %.1f", manhattan)));
        return res;
    }

    public static int distance(CommandSourceStack source, Vec3 pos1, Vec3 pos2)
    {
        Messenger.send(source, findDistanceBetweenTwoPoints(pos1, pos2));
        // Return spherical distance as an integer (rounded) so it can be stored via /execute store
        double dx = pos1.x - pos2.x;
        double dy = pos1.y - pos2.y;
        double dz = pos1.z - pos2.z;
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    public static int setStart(CommandSourceStack source, Vec3 pos)
    {
        START_POINT_STORAGE.put(source.getTextName(), pos);
        Messenger.m(source,"gi Initial point set to: ", Messenger.tp("g",pos));
        return 1;
    }

    public static int setEnd(CommandSourceStack source, Vec3 pos)
    {
        if ( !hasStartingPoint(source) )
        {
            START_POINT_STORAGE.put(source.getTextName(), pos);
            Messenger.m(source,"gi There was no initial point for "+source.getTextName());
            Messenger.m(source,"gi Initial point set to: ", Messenger.tp("g",pos));
            return 0;
        }
        Vec3 start = START_POINT_STORAGE.get(source.getTextName());
        Messenger.send(source, findDistanceBetweenTwoPoints(start, pos));
        // Return spherical distance as integer for /execute store
        double dx = start.x - pos.x;
        double dy = start.y - pos.y;
        double dz = start.z - pos.z;
        return Math.max(1, (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)));
    }
}
