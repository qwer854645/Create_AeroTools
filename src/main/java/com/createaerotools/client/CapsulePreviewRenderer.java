package com.createaerotools.client;

import com.createaerotools.block.CapsuleStationBlockEntity;
import com.createaerotools.util.WorldSpace;
import com.createaerotools.item.AdvancedStructureCapsuleItem;
import com.createaerotools.item.CapsulePower;
import com.createaerotools.item.StructureCapsuleItem;
import com.createaerotools.item.StructureGroups;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.UUID;

/** 手持收纳器时用 Outliner 高亮将被收纳的结构（含高级收纳器连通组）。 */
public final class CapsulePreviewRenderer {
    private static final int COLOR = 0x7AD4FF;
    private static int tickCounter;

    private CapsulePreviewRenderer() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CapsulePreviewRenderer::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        // 高级收纳 BFS 较贵：隔一 tick 再扫
        if ((++tickCounter & 1) != 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) {
            return;
        }
        ItemStack stack = heldCapsule(player);
        if (stack.isEmpty() || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() == HitResult.Type.MISS) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        if (level.getBlockEntity(pos) instanceof CapsuleStationBlockEntity) {
            return;
        }
        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(level, pos);
        if (!(access instanceof SubLevel subLevel) || subLevel.isRemoved()) {
            return;
        }

        List<SubLevel> preview = null;
        if (stack.getItem() instanceof StructureCapsuleItem) {
            if (StructureCapsuleItem.hasStructure(stack) || StructureGroups.isSableJoined(subLevel)
                    || StructureCapsuleItem.tooLarge(subLevel)) {
                return;
            }
            preview = List.of(subLevel);
        } else if (stack.getItem() instanceof AdvancedStructureCapsuleItem) {
            preview = advancedPreview(level, stack, subLevel);
        }
        if (preview == null || preview.isEmpty()) {
            return;
        }

        int index = 0;
        for (SubLevel part : preview) {
            AABB box = part.boundingBox().toMojang();
            if (box.getXsize() < 1.0E-4D && box.getYsize() < 1.0E-4D && box.getZsize() < 1.0E-4D) {
                continue;
            }
            UUID id = part.getUniqueId();
            Object key = id != null ? Pair.of("cat_capsule", id) : Pair.of("cat_capsule", index);
            Outliner.getInstance().showAABB(key, box.inflate(0.06D))
                    .colored(COLOR)
                    .lineWidth(1 / 16.0F);
            index++;
        }
    }

    private static List<SubLevel> advancedPreview(Level level, ItemStack stack, SubLevel aimed) {
        if (AdvancedStructureCapsuleItem.hasStructures(stack) || !AdvancedStructureCapsuleItem.isBound(stack)) {
            return null;
        }
        CapsuleStationBlockEntity station = AdvancedStructureCapsuleItem.boundStation(level, stack);
        if (station == null) {
            return null;
        }
        if (!AdvancedStructureCapsuleItem.inRange(station, Minecraft.getInstance().player.position())) {
            return null;
        }
        List<SubLevel> group = StructureGroups.collectConnected(level, aimed);
        UUID stationId = WorldSpace.structureId(level, station.getBlockPos());
        if (stationId != null) {
            for (SubLevel part : group) {
                if (stationId.equals(part.getUniqueId())) {
                    return null;
                }
            }
        }
        for (SubLevel part : group) {
            var pos = part.logicalPose().position();
            if (!AdvancedStructureCapsuleItem.inRange(station, new Vec3(pos.x, pos.y, pos.z))) {
                return null;
            }
        }
        if (CapsulePower.evaluate(station, CapsulePower.sizeOf(group)) != CapsulePower.Fail.NONE) {
            return null;
        }
        return group;
    }

    private static ItemStack heldCapsule(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (isCapsule(main)) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        return isCapsule(off) ? off : ItemStack.EMPTY;
    }

    private static boolean isCapsule(ItemStack stack) {
        return stack.getItem() instanceof StructureCapsuleItem
                || stack.getItem() instanceof AdvancedStructureCapsuleItem;
    }
}
