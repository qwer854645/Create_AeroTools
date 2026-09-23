package com.createaerotools.util;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.Collection;
import java.util.UUID;

/**
 * 世界坐标 ↔ Sable 物理结构（SubLevel）坐标换算。
 * <p>
 * 端口、轴约束、收纳器释放都依赖这里：方块可能在结构 plot 内，
 * 世界位姿由 {@link SubLevelAccess#logicalPose()} 提供。
 */
public final class WorldSpace {
    private static final Pose3d IDENTITY = new Pose3d();

    private WorldSpace() {
    }

    /** 该方块是否属于某个物理结构；不属于则返回 {@code null}。 */
    @Nullable
    public static UUID structureId(Level level, BlockPos pos) {
        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(level, pos);
        return access instanceof SubLevel subLevel ? subLevel.getUniqueId() : null;
    }

    @Nullable
    public static UUID structureId(BlockEntity be) {
        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(be);
        return access instanceof SubLevel subLevel ? subLevel.getUniqueId() : null;
    }

    /**
     * 方块实体是否位于给定结构上。
     * 依次用引用 / UUID / {@code Level == SubLevel} 判断（结构组装时引用可能变化）。
     */
    public static boolean isOn(BlockEntity be, @Nullable SubLevel subLevel) {
        if (be == null || subLevel == null || subLevel.isRemoved()) {
            return false;
        }
        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(be);
        if (access == subLevel) {
            return true;
        }
        UUID id = subLevel.getUniqueId();
        if (id != null && access instanceof SubLevel other && id.equals(other.getUniqueId())) {
            return true;
        }
        return be.getLevel() == (Object) subLevel;
    }

    public static boolean isOn(BlockEntity be, Collection<? extends SubLevel> group) {
        if (be == null || group == null || group.isEmpty()) {
            return false;
        }
        for (SubLevel subLevel : group) {
            if (isOn(be, subLevel)) {
                return true;
            }
        }
        return false;
    }

    public static Pose3dc pose(Level level, BlockPos pos) {
        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(level, pos);
        return access != null ? access.logicalPose() : IDENTITY;
    }

    public static Pose3dc poseOf(BlockEntity be) {
        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(be);
        return access != null ? access.logicalPose() : IDENTITY;
    }

    public static Pose3dc renderPoseOf(BlockEntity be, float partialTick) {
        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(be);
        if (access instanceof ClientSubLevelAccess client) {
            return client.renderPose(partialTick);
        }
        return access != null ? access.logicalPose() : IDENTITY;
    }

    /** 结构所在的父维度；在 SubLevel 上时返回外层世界。 */
    @Nullable
    public static Level parentLevel(BlockEntity be) {
        Level level = be.getLevel();
        if (level == null) {
            return null;
        }
        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(be);
        if (access instanceof SubLevel subLevel && subLevel.getLevel() != null) {
            return subLevel.getLevel();
        }
        return level;
    }

    /** 端口本地锚点：略缩进朝向面内侧，避免锚点落在 plot 外导致约束飞掉。 */
    public static Vec3 localLinkAnchor(BlockPos pos, Direction facing) {
        return Vec3.atCenterOf(pos).add(facing.getStepX() * 0.4D, facing.getStepY() * 0.4D, facing.getStepZ() * 0.4D);
    }

    public static Vec3 worldLinkAnchor(BlockEntity be, Direction facing) {
        return transformPosition(poseOf(be), localLinkAnchor(be.getBlockPos(), facing));
    }

    public static Vec3 worldLinkAnchor(BlockEntity be, Direction facing, float partialTick) {
        return transformPosition(renderPoseOf(be, partialTick), localLinkAnchor(be.getBlockPos(), facing));
    }

    public static Vec3 worldBlockCenter(Level level, BlockPos pos) {
        return transformPosition(pose(level, pos), Vec3.atCenterOf(pos));
    }

    public static Vec3 worldNormal(BlockEntity be, Direction facing) {
        return worldNormal(poseOf(be), facing);
    }

    public static Vec3 worldNormal(Level level, BlockPos pos, Direction facing) {
        return worldNormal(pose(level, pos), facing);
    }

    private static Vec3 worldNormal(Pose3dc pose, Direction facing) {
        Vec3 origin = transformPosition(pose, Vec3.ZERO);
        Vec3 tip = transformPosition(pose, Vec3.atLowerCornerOf(facing.getNormal()));
        Vec3 normal = tip.subtract(origin);
        return normal.lengthSqr() < 1.0E-12D ? new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ()) : normal.normalize();
    }

    public static Vec3 worldClickLocation(UseOnContext context) {
        Level level = context.getLevel();
        Vec3 click = context.getClickLocation();
        BlockPos pos = context.getClickedPos();
        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(level, pos);
        if (access == null) {
            return SableCompanion.INSTANCE.projectOutOfSubLevel(level, click);
        }
        if (click.distanceToSqr(Vec3.atCenterOf(pos)) <= 4.0D) {
            return transformPosition(access.logicalPose(), click);
        }
        return click;
    }

    /**
     * 释放结构时：沿点击面法线“坐”在表面上，切向对齐点击点。
     * {@code com}/{@code worldBox} 可能仍是收纳时的世界坐标，只取 COM 到支撑面的沿法线距离。
     */
    public static Vec3 sitOnFace(Vec3 com, @Nullable BoundingBox3dc worldBox, Vec3 hit, Vec3 normal) {
        Vec3 n = normal.normalize();
        if (n.lengthSqr() < 1.0E-8D) {
            return hit.add(0.0D, 0.51D, 0.0D);
        }
        if (worldBox == null) {
            return hit.add(n.scale(0.51D));
        }
        double minProj = supportMin(worldBox, n);
        // 沿法线保持 COM↔支撑距离；切向跟点击走，不跟旧 COM
        return hit.add(n.scale(com.dot(n) - minProj + 0.01D));
    }

    public static Vec3 releaseAgainst(UseOnContext context, Vec3 com, @Nullable BoundingBox3dc worldBox) {
        return sitOnFace(com, worldBox,
                worldClickLocation(context),
                worldNormal(context.getLevel(), context.getClickedPos(), context.getClickedFace()));
    }

    private static Vec3 transformPosition(Pose3dc pose, Vec3 local) {
        Vector3d out = pose.transformPosition(new Vector3d(local.x, local.y, local.z));
        return new Vec3(out.x, out.y, out.z);
    }

    private static double supportMin(BoundingBox3dc box, Vec3 normal) {
        return (normal.x >= 0.0D ? box.minX() : box.maxX()) * normal.x
                + (normal.y >= 0.0D ? box.minY() : box.maxY()) * normal.y
                + (normal.z >= 0.0D ? box.minZ() : box.maxZ()) * normal.z;
    }
}
