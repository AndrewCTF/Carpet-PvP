package carpet.helpers;

//? if >=26.3 {
import carpet.script.utils.FeatureGenerator;
//?} else {
/*import carpet.fakes.CoralFeatureInterface;
*///?}
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseCoralPlantTypeBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.CoralClawFeature;
import net.minecraft.world.level.levelgen.feature.CoralTreeFeature;
import net.minecraft.world.level.material.MapColor;
//? if >=26.3 {
import net.minecraft.data.worldgen.placement.PlacementUtils;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.levelgen.feature.CuboidPlacement;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.SimpleRandomSelectorFeature;
import net.minecraft.world.level.levelgen.placement.OffsetPlacement;
import net.minecraft.world.level.levelgen.placement.RandomChancePlacement;

import java.util.stream.Stream;
//?} else {
/*import net.minecraft.world.level.levelgen.feature.CoralFeature;
import net.minecraft.world.level.levelgen.feature.CoralMushroomFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
*///?}

/**
 * Deduplicates logic for the different behaviors of the {@code renewableCoral} rule
 */
public interface FertilizableCoral extends BonemealableBlock {
    /**
     * @return Whether the rule for this behavior is enabled
     */
    boolean isEnabled();

    @Override
    public default boolean isValidBonemealTarget(LevelReader world, BlockPos pos, BlockState state/*? if >=26.3 {*/, BonemealSource bonemealSource/*?}*/)
    {
        return isEnabled()
                && state.getValue(BaseCoralPlantTypeBlock.WATERLOGGED)
                && world.getFluidState(pos.above()).is(FluidTags.WATER);
    }

    @Override
    public default boolean isBonemealSuccess(Level world, RandomSource random, BlockPos pos, BlockState state/*? if >=26.3 {*/, BonemealSource bonemealSource/*?}*/)
    {
        return random.nextFloat() < 0.15D;
    }

    @Override
    public default void performBonemeal(ServerLevel worldIn, RandomSource random, BlockPos pos, BlockState blockUnder/*? if >=26.3 {*/, BonemealSource bonemealSource/*?}*/)
    {
        //? if <26.3 {
        /*int variant = random.nextInt(3);
        CoralFeature coral = switch (variant) {
            case 0 -> new CoralClawFeature(NoneFeatureConfiguration.CODEC);
            case 1 -> new CoralTreeFeature(NoneFeatureConfiguration.CODEC);
            default -> new CoralMushroomFeature(NoneFeatureConfiguration.CODEC);
        };

        *///?}
        MapColor color = blockUnder.getMapColor(worldIn, pos);
        BlockState properBlock = blockUnder;
        HolderSet.Named<Block> coralBlocks = worldIn.registryAccess().lookupOrThrow(Registries.BLOCK).get(BlockTags.CORAL_BLOCKS).orElseThrow();
        for (Holder<Block> block: coralBlocks)
        {
            properBlock = block.value().defaultBlockState();
            if (properBlock.getMapColor(worldIn, pos) == color)
            {
                break;
            }
        }
        worldIn.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_NONE);

        //? if >=26.3 {
        var blocks = worldIn.registryAccess().lookupOrThrow(Registries.BLOCK);
        Block actualProperBlock = properBlock.getBlock();
        var features = worldIn.registryAccess().lookupOrThrow(Registries.FEATURE);
        Feature feature = new SimpleRandomSelectorFeature( HolderSet.direct(Stream.of(actualProperBlock)
                .map(block -> FeatureGenerator.coral(features, blocks, actualProperBlock))
                .flatMap(
                        coralType -> Stream.of(
                                PlacementUtils.inlinePlaced(new CoralTreeFeature(
                                        PlacementUtils.inlinePlaced(coralType, FeatureGenerator.coralPlacement))
                                ),
                                PlacementUtils.inlinePlaced(new CoralClawFeature(
                                        PlacementUtils.inlinePlaced(coralType, FeatureGenerator.coralPlacement))),
                                PlacementUtils.inlinePlaced(
                                        coralType,
                                        OffsetPlacement.vertical(UniformInt.of(-3, -1)),
                                        new CuboidPlacement(
                                                UniformInt.of(3, 5),
                                                UniformInt.of(3, 5),
                                                false,
                                                false
                                        ),
                                        new RandomChancePlacement(0.9f),
                                        FeatureGenerator.coralPlacement
                                )
                        )
                ).toList())
        );
        if (!feature.place(worldIn, worldIn.getChunkSource().getGenerator(), random, pos))
        //?} else {
        /*if (!((CoralFeatureInterface)coral).growSpecific(worldIn, random, pos, properBlock))
        *///?}
        {
            worldIn.setBlock(pos, blockUnder, 3);
        }
        else
        {
            if (worldIn.getRandom().nextInt(10) == 0)
            {
                BlockPos randomPos = pos.offset(worldIn.getRandom().nextInt(16) - 8, worldIn.getRandom().nextInt(8), worldIn.getRandom().nextInt(16) - 8);
                // Check if block at position is in coralBlocks tag
                Block targetBlock = worldIn.getBlockState(randomPos).getBlock();
                boolean found = false;
                for (Holder<Block> holder : coralBlocks) {
                    if (holder.value() == targetBlock) {
                        found = true;
                        break;
                    }
                }
                if (found)
                {
                    worldIn.setBlock(randomPos, Blocks.WET_SPONGE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }
}
