package com.createaerotools.index;

import com.createaerotools.CAT;
import com.createaerotools.block.CapsuleStationBlock;
import com.createaerotools.block.DrivePortBlock;
import com.createaerotools.block.PneumaticPortBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import com.tterrag.registrate.util.entry.BlockEntry;

/** 方块注册表。 */
public final class CATBlocks {
    public static final BlockEntry<DrivePortBlock> DRIVE_PORT = CAT.REGISTRATE
            .block("drive_port", DrivePortBlock::new)
            .initialProperties(() -> Blocks.IRON_BLOCK)
            .properties(properties -> properties
                    .mapColor(MapColor.STONE)
                    .noOcclusion()
                    .strength(3.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.WOOD))
            .simpleItem()
            .register();

    public static final BlockEntry<PneumaticPortBlock> PNEUMATIC_PORT = CAT.REGISTRATE
            .block("pneumatic_port", PneumaticPortBlock::new)
            .initialProperties(() -> Blocks.IRON_BLOCK)
            .properties(properties -> properties
                    .mapColor(MapColor.COLOR_ORANGE)
                    .noOcclusion()
                    .strength(3.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.COPPER))
            .simpleItem()
            .register();

    public static final BlockEntry<CapsuleStationBlock> CAPSULE_STATION = CAT.REGISTRATE
            .block("capsule_station", CapsuleStationBlock::new)
            .initialProperties(() -> Blocks.IRON_BLOCK)
            .properties(properties -> properties
                    .mapColor(MapColor.TERRACOTTA_BROWN)
                    .noOcclusion()
                    .strength(3.5F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL))
            .simpleItem()
            .register();

    private CATBlocks() {
    }

    public static void register() {
    }
}
