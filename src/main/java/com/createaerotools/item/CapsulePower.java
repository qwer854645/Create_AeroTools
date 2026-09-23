package com.createaerotools.item;

import com.createaerotools.block.CapsuleStationBlockEntity;
import com.createaerotools.config.CATConfig;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * 高级收纳器对收纳底座的转速 / 应力门槛计算与失败提示。
 */
public final class CapsulePower {
    public enum Fail {
        NONE,
        UNPOWERED,
        RPM,
        STRESS
    }

    private CapsulePower() {
    }

    public static double sizeOf(List<? extends SubLevel> group) {
        double size = 0.0D;
        for (SubLevel subLevel : group) {
            size += sizeOf(subLevel);
        }
        return Math.max(1.0D, size);
    }

    public static double sizeOf(SubLevel subLevel) {
        BoundingBox3ic box = subLevel.getPlot().getBoundingBox();
        if (box != null && box.volume() > 0) {
            return box.volume();
        }
        if (subLevel instanceof ServerSubLevel server) {
            MassData mass = server.getMassTracker();
            if (mass != null && !mass.isInvalid() && mass.getMass() > 0.0D) {
                return mass.getMass();
            }
        }
        return 1.0D;
    }

    public static double sizeOfStored(List<CompoundTag> payloads) {
        double size = 0.0D;
        for (CompoundTag payload : payloads) {
            if (payload.contains("world_bounds")) {
                size += Math.max(1.0D, SableNBTUtils.readBoundingBox(payload.getCompound("world_bounds")).volume());
            } else {
                size += 1.0D;
            }
        }
        return Math.max(1.0D, size);
    }

    public static float requiredRpm(double size) {
        return scaled(size, CATConfig.SERVER.capsuleRpmPerSize.get(), CATConfig.SERVER.capsuleMaxRpm.get());
    }

    public static float requiredStress(double size) {
        return scaled(size, CATConfig.SERVER.capsuleStressPerSize.get(), CATConfig.SERVER.capsuleMaxStress.get());
    }

    public static Fail evaluate(CapsuleStationBlockEntity station, double size) {
        if (station == null || !station.isReady()) {
            return Fail.UNPOWERED;
        }
        float rpm = Math.abs(station.getSpeed());
        float neededRpm = requiredRpm(size);
        if (neededRpm > 0.0F && rpm + 0.05F < neededRpm) {
            return Fail.RPM;
        }
        float stress = rpm * Math.abs(station.calculateStressApplied());
        float neededStress = requiredStress(size);
        if (neededStress > 0.0F && stress + 0.05F < neededStress) {
            return Fail.STRESS;
        }
        return Fail.NONE;
    }

    public static boolean tell(Player player, CapsuleStationBlockEntity station, double size) {
        Fail fail = evaluate(station, size);
        if (fail == Fail.NONE) {
            return true;
        }
        player.displayClientMessage(message(fail, station, size).withStyle(ChatFormatting.RED), true);
        return false;
    }

    public static MutableComponent requirementTooltip(double size) {
        float rpm = requiredRpm(size);
        float stress = requiredStress(size);
        if (rpm > 0.0F && stress > 0.0F) {
            return Component.translatable("createaerotools.tooltip.adv_capsule_power", format(rpm), format(stress));
        }
        if (rpm > 0.0F) {
            return Component.translatable("createaerotools.tooltip.adv_capsule_power_rpm", format(rpm));
        }
        if (stress > 0.0F) {
            return Component.translatable("createaerotools.tooltip.adv_capsule_power_stress", format(stress));
        }
        return Component.translatable("createaerotools.tooltip.adv_capsule_release");
    }

    private static MutableComponent message(Fail fail, CapsuleStationBlockEntity station, double size) {
        float rpm = station == null ? 0.0F : Math.abs(station.getSpeed());
        float stress = station == null ? 0.0F : rpm * Math.abs(station.calculateStressApplied());
        return switch (fail) {
            case RPM -> Component.translatable("createaerotools.message.capsule_need_rpm",
                    format(requiredRpm(size)), format(rpm));
            case STRESS -> Component.translatable("createaerotools.message.capsule_need_stress",
                    format(requiredStress(size)), format(stress));
            default -> Component.translatable("createaerotools.message.capsule_station_unpowered");
        };
    }

    private static float scaled(double size, double ratio, double max) {
        if (ratio <= 0.0D) {
            return 0.0F;
        }
        double value = Math.max(0.0D, size) * ratio;
        if (max > 0.0D) {
            value = Math.min(value, max);
        }
        return (float) value;
    }

    private static String format(float value) {
        return String.format("%.0f", (double) Mth.ceil(value));
    }
}
