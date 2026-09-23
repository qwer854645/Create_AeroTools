package com.createaerotools.block;

import com.createaerotools.CreateAeroTools;
import com.createaerotools.config.CATConfig;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.createmod.catnip.lang.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** 高级收纳底座：接入应力且转速非零时允许高级收纳器工作。 */
public class CapsuleStationBlockEntity extends KineticBlockEntity {
    public CapsuleStationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public boolean isReady() {
        return !isOverStressed() && Math.abs(getSpeed()) > 1.0E-3F;
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = CATConfig.SERVER.capsuleStationStress.get().floatValue();
        return lastStressApplied;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        Lang.builder(CreateAeroTools.MOD_ID)
                .translate(isReady()
                        ? "goggle.capsule_station_ready"
                        : "goggle.capsule_station_unpowered")
                .style(isReady() ? ChatFormatting.GREEN : ChatFormatting.RED)
                .forGoggles(tooltip);
        double range = CATConfig.SERVER.capsuleStationRange.get();
        if (range > 0.0D) {
            Lang.builder(CreateAeroTools.MOD_ID)
                    .translate("goggle.capsule_station_range", String.format("%.0f", range))
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);
        }
        return true;
    }
}
