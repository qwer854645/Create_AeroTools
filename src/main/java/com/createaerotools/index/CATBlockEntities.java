package com.createaerotools.index;

import com.createaerotools.CAT;
import com.createaerotools.block.CapsuleStationBlockEntity;
import com.createaerotools.block.DrivePortBlockEntity;
import com.createaerotools.block.PneumaticPortBlockEntity;
import com.createaerotools.client.CapsuleStationRenderer;
import com.createaerotools.client.DrivePortRenderer;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

/** 方块实体注册表（含 Flywheel 视觉与 BER）。 */
public final class CATBlockEntities {
    public static final BlockEntityEntry<DrivePortBlockEntity> DRIVE_PORT = CAT.REGISTRATE
            .blockEntity("drive_port", DrivePortBlockEntity::new)
            .visual(() -> SingleAxisRotatingVisual.of(com.simibubi.create.AllPartialModels.SHAFT_HALF))
            .validBlocks(CATBlocks.DRIVE_PORT)
            .renderer(() -> DrivePortRenderer::new)
            .register();

    public static final BlockEntityEntry<PneumaticPortBlockEntity> PNEUMATIC_PORT = CAT.REGISTRATE
            .blockEntity("pneumatic_port", PneumaticPortBlockEntity::new)
            .visual(() -> SingleAxisRotatingVisual.of(com.simibubi.create.AllPartialModels.SHAFT_HALF))
            .validBlocks(CATBlocks.PNEUMATIC_PORT)
            .renderer(() -> DrivePortRenderer::new)
            .register();

    public static final BlockEntityEntry<CapsuleStationBlockEntity> CAPSULE_STATION = CAT.REGISTRATE
            .blockEntity("capsule_station", CapsuleStationBlockEntity::new)
            .visual(() -> SingleAxisRotatingVisual::shaft)
            .validBlocks(CATBlocks.CAPSULE_STATION)
            .renderer(() -> CapsuleStationRenderer::new)
            .register();

    private CATBlockEntities() {
    }

    public static void register() {
    }
}
