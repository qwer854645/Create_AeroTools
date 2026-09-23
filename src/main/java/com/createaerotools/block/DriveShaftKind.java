package com.createaerotools.block;

import com.createaerotools.config.CATConfig;
import com.createaerotools.index.CATItems;
import com.createaerotools.item.DriveShaftItem;
import net.minecraft.world.item.ItemStack;

/**
 * 万向轴材质：黄铜回弹到链接长度；安山自由伸缩，固定最大长度拉断。
 */
public enum DriveShaftKind {
    BRASS,
    ANDESITE;

    public boolean springsToRestLength() {
        return this == BRASS;
    }

    /** 拉断长度：黄铜相对链接基准；安山用配置的绝对上限。 */
    public double snapLength(double restLength) {
        return switch (this) {
            case BRASS -> {
                double byPercent = restLength * CATConfig.SERVER.driveBrassBreakPercent.get() / 100.0D;
                double max = CATConfig.SERVER.driveMaxDistance.get();
                yield max > 0.0D ? Math.min(byPercent, max) : byPercent;
            }
            case ANDESITE -> {
                double max = CATConfig.SERVER.driveAndesiteMaxLength.get();
                yield max > 0.0D ? max : Double.POSITIVE_INFINITY;
            }
        };
    }

    public double maxLinkLength() {
        return switch (this) {
            case BRASS -> CATConfig.SERVER.driveMaxDistance.get();
            case ANDESITE -> CATConfig.SERVER.driveAndesiteMaxLength.get();
        };
    }

    /** 气动端口上该材质的行程 / 链接上限。 */
    public double pneumaticMaxLength() {
        return switch (this) {
            case BRASS -> CATConfig.SERVER.pneumaticBrassMaxLength.get();
            case ANDESITE -> CATConfig.SERVER.pneumaticAndesiteMaxLength.get();
        };
    }

    /** 按端口类型选择链接上限：气动用气动配置，传动用传动配置。 */
    public double maxLinkLengthFor(DrivePortBlockEntity port) {
        return port instanceof PneumaticPortBlockEntity ? pneumaticMaxLength() : maxLinkLength();
    }

    public ItemStack asStack() {
        return (this == BRASS ? CATItems.DRIVE_SHAFT : CATItems.ANDESITE_DRIVE_SHAFT).asStack();
    }

    public static DriveShaftKind from(ItemStack stack) {
        if (stack.getItem() instanceof DriveShaftItem shaft) {
            return shaft.kind();
        }
        return BRASS;
    }
}
