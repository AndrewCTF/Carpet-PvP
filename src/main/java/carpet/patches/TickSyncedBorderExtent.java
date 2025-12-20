package carpet.patches;

import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.BorderStatus;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

/**
 * This class is essentially a copy of {@link net.minecraft.world.level.border.WorldBorder.MovingBorderExtent}
 * but instead of using real time to lerp the border
 * this class uses the in game ticks.
 */
@SuppressWarnings("JavadocReference")
public class TickSyncedBorderExtent implements WorldBorder.BorderExtent
{
    private final WorldBorder border;
    private final long realDuration;
    private final double tickDuration;
    private final double from;
    private final double to;

    private int ticks;

    public TickSyncedBorderExtent(WorldBorder border, long realDuration, double from, double to)
    {
        this.border = border;
        this.realDuration = realDuration;
        this.tickDuration = realDuration / 50.0;
        this.from = from;
        this.to = to;
        this.ticks = 0;
    }

    @Override
    public double getMinX(float tickDelta)
    {
        int maxSize = this.border.getAbsoluteMaxSize();
        return Mth.clamp(this.border.getCenterX() - this.getSize() / 2.0, -maxSize, maxSize);
    }

    @Override
    public double getMaxX(float tickDelta)
    {
        int maxSize = this.border.getAbsoluteMaxSize();
        return Mth.clamp(this.border.getCenterX() + this.getSize() / 2.0, -maxSize, maxSize);
    }

    @Override
    public double getMinZ(float tickDelta)
    {
        int maxSize = this.border.getAbsoluteMaxSize();
        return Mth.clamp(this.border.getCenterZ() - this.getSize() / 2.0, -maxSize, maxSize);
    }

    @Override
    public double getMaxZ(float tickDelta)
    {
        int maxSize = this.border.getAbsoluteMaxSize();
        return Mth.clamp(this.border.getCenterZ() + this.getSize() / 2.0, -maxSize, maxSize);
    }

    @Override
    public double getSize()
    {
        double progress = this.ticks / this.tickDuration;
        return progress < 1.0 ? Mth.lerp(progress, this.from, this.to) : this.to;
    }

    @Override
    public double getLerpSpeed()
    {
        return Math.abs(this.from - this.to) / this.realDuration;
    }

    @Override
    public double getLerpTarget()
    {
        return this.to;
    }

    @Override
    public long getLerpTime()
    {
        return (long)Math.max(0, this.tickDuration - this.ticks);
    }

    @NotNull
    @Override
    public BorderStatus getStatus()
    {
        return this.to < this.from ? BorderStatus.SHRINKING : BorderStatus.GROWING;
    }

    @Override
    public void onAbsoluteMaxSizeChange()
    {

    }

    @Override
    public void onCenterChange()
    {

    }

    @NotNull
    @Override
    public WorldBorder.BorderExtent update()
    {
        if (this.ticks++ % 20 == 0)
        {
            // We need to update any listeners
            // Most importantly those that send updates to the client
            // This is because the client logic uses real time
            // So if the tick speed has changed we need to tell the client
            double progress = Mth.clamp(this.ticks / this.tickDuration, 0.0, 1.0);
            long now = Util.getMillis();
            long lerpStartTime = now - (long)(progress * this.realDuration);
            for (BorderChangeListener listener : this.border.getListeners())
            {
                listener.onLerpSize(this.border, this.from, this.to, this.realDuration, lerpStartTime);
            }
        }

        return this.ticks >= this.tickDuration ? this.border.new StaticBorderExtent(this.to) : this;
    }

    @NotNull
    @Override
    public VoxelShape getCollisionShape()
    {
        return Shapes.join(
            Shapes.INFINITY,
            Shapes.box(
                Math.floor(this.getMinX()),
                Double.NEGATIVE_INFINITY,
                Math.floor(this.getMinZ()),
                Math.ceil(this.getMaxX()),
                Double.POSITIVE_INFINITY,
                Math.ceil(this.getMaxZ())
            ),
            BooleanOp.ONLY_FIRST
        );
    }
}
