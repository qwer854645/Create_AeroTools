package com.createaerotools.item;

import com.createaerotools.util.WorldSpace;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 两种收纳器共用的 NBT / 瞄准辅助。
 */
public final class CapsulePayloads {
    public static final String TAG_NAME = "StoredName";

    private CapsulePayloads() {
    }

    public static String unnamed() {
        return Component.translatable("createaerotools.tooltip.capsule_unnamed").getString();
    }

    public static String storedName(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return unnamed();
        }
        String name = data.copyTag().getString(TAG_NAME);
        return name == null || name.isBlank() ? unnamed() : name;
    }

    /** 未瞄准方块时沿视线约 4 格释放；{@code null} 表示改走 useOn。 */
    @Nullable
    public static Vec3 airReleasePoint(Player player) {
        HitResult hit = player.pick(player.blockInteractionRange(), 1.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            return null;
        }
        return player.getEyePosition().add(player.getLookAngle().scale(4.0D));
    }

    public static void stripVelocities(CompoundTag payload) {
        payload.remove("linear_velocity");
        payload.remove("angular_velocity");
    }

    public static void stripForBasicCapsule(CompoundTag payload) {
        stripVelocities(payload);
        payload.remove("loading_dependencies");
    }

    /** 平移 pose / 包围盒，使结构 COM 落在 {@code at}。 */
    public static void translateTo(CompoundTag payload, Vec3 at) {
        Pose3d pose = readPose(payload);
        translateBy(payload, at.x - pose.position().x, at.y - pose.position().y, at.z - pose.position().z);
    }

    public static void translateBy(CompoundTag payload, double dx, double dy, double dz) {
        if (dx == 0.0D && dy == 0.0D && dz == 0.0D) {
            return;
        }
        Pose3d pose = readPose(payload);
        pose.position().add(dx, dy, dz);
        payload.put("pose", SableNBTUtils.writePose3d(pose));
        if (payload.contains("world_bounds")) {
            BoundingBox3d box = SableNBTUtils.readBoundingBox(payload.getCompound("world_bounds"));
            box.move(dx, dy, dz);
            payload.put("world_bounds", SableNBTUtils.writeBoundingBox(box));
        }
    }

    public static Vec3 posePosition(CompoundTag payload) {
        Pose3d pose = readPose(payload);
        return new Vec3(pose.position().x, pose.position().y, pose.position().z);
    }

    @Nullable
    public static BoundingBox3d worldBounds(CompoundTag payload) {
        if (!payload.contains("world_bounds")) {
            return null;
        }
        return SableNBTUtils.readBoundingBox(payload.getCompound("world_bounds"));
    }

    public static Vec3 releaseAgainst(UseOnContext context, @Nullable CompoundTag payload) {
        if (payload == null) {
            return WorldSpace.releaseAgainst(context, Vec3.ZERO, null);
        }
        return WorldSpace.releaseAgainst(context, posePosition(payload), worldBounds(payload));
    }

    public static Vec3 releaseAgainst(UseOnContext context, Vec3 com, @Nullable BoundingBox3d box) {
        return WorldSpace.releaseAgainst(context, com, box);
    }

    private static Pose3d readPose(CompoundTag payload) {
        return payload.contains("pose")
                ? SableNBTUtils.readPose3d(payload.getCompound("pose"))
                : new Pose3d();
    }
}
