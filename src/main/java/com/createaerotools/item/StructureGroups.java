package com.createaerotools.item;

import com.createaerotools.block.DriveLinkTracker;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 按 Sable 关节 + 万向轴连通性收集结构组；序列化 / 反序列化多结构 NBT。
 */
public final class StructureGroups {
    private StructureGroups() {
    }

    /** 从起点 BFS：关节邻居 + 万向轴对端结构。 */
    public static List<ServerSubLevel> collect(ServerLevel level, ServerSubLevel start) {
        List<ServerSubLevel> group = new ArrayList<>();
        for (SubLevel subLevel : collectConnected(level, start)) {
            if (subLevel instanceof ServerSubLevel server) {
                group.add(server);
            }
        }
        return group;
    }

    public static List<SubLevel> collectConnected(Level level, SubLevel start) {
        LinkedHashMap<UUID, SubLevel> group = new LinkedHashMap<>();
        ArrayDeque<SubLevel> queue = new ArrayDeque<>();
        enqueue(group, queue, start);
        var container = SubLevelContainer.getContainer(level);
        while (!queue.isEmpty()) {
            SubLevel current = queue.removeFirst();
            for (SubLevel other : SubLevelHelper.getConnectedChain(current)) {
                enqueue(group, queue, other);
            }
            UUID id = current.getUniqueId();
            if (id == null) {
                continue;
            }
            DriveLinkTracker.visitLinkedStructures(level, id, partnerId ->
                    enqueue(group, queue, container.getSubLevel(partnerId)));
        }
        return new ArrayList<>(group.values());
    }

    /**
     * Recover shafts that connect the group to the world or to structures outside the group.
     */
    public static int unlinkExternal(List<ServerSubLevel> group, Player player) {
        return DriveLinkTracker.unlinkExternal(group, player);
    }

    public static void beginRemoval(List<ServerSubLevel> group) {
        DriveLinkTracker.beginCapsuleRemoval(group);
    }

    public static void endRemoval() {
        DriveLinkTracker.endCapsuleRemoval();
    }

    public static boolean isSableJoined(SubLevel subLevel) {
        UUID id = subLevel.getUniqueId();
        for (SubLevel other : SubLevelHelper.getConnectedChain(subLevel)) {
            if (other != subLevel && other.getUniqueId() != null && !other.getUniqueId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public static List<CompoundTag> capture(List<ServerSubLevel> group) {
        Set<UUID> ids = new HashSet<>();
        for (ServerSubLevel subLevel : group) {
            if (subLevel.getUniqueId() != null) {
                ids.add(subLevel.getUniqueId());
            }
        }
        List<CompoundTag> payloads = new ArrayList<>();
        for (ServerSubLevel subLevel : group) {
            List<UUID> deps = new ArrayList<>();
            for (SubLevel other : SubLevelHelper.getConnectedChain(subLevel)) {
                UUID otherId = other.getUniqueId();
                if (otherId != null && ids.contains(otherId) && !otherId.equals(subLevel.getUniqueId())) {
                    deps.add(otherId);
                }
            }
            SubLevelData data = SubLevelSerializer.toData(subLevel, deps);
            CompoundTag payload = data.fullTag().copy();
            CapsulePayloads.stripVelocities(payload);
            payloads.add(payload);
        }
        return payloads;
    }

    public static void removeAll(ServerLevel level, List<ServerSubLevel> group) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        for (ServerSubLevel subLevel : group) {
            if (subLevel.isRemoved()) {
                continue;
            }
            subLevel.getPlot().kickAllEntities();
            subLevel.deleteAllEntities();
            container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
        }
    }

    /**
     * 释放结果：已加载数量 + 仍应留在收纳器里的原始 payload（加载失败的那些）。
     */
    public record RestoreOutcome(int loaded, List<CompoundTag> remaining) {
        public boolean anyLoaded() {
            return loaded > 0;
        }

        public boolean allLoaded() {
            return remaining.isEmpty() && loaded > 0;
        }
    }

    /**
     * 按依赖顺序加载结构。失败的条目放进 {@link RestoreOutcome#remaining}，
     * 调用方只应从物品中移除已成功加载的，避免部分失败时整包清空。
     */
    public static RestoreOutcome restore(ServerLevel level, List<CompoundTag> stored, Vec3 releaseAt, @Nullable UUID anchorId) {
        Map<UUID, UUID> remap = new HashMap<>();
        Vec3 anchorPos = null;
        record Entry(CompoundTag original, CompoundTag working) {
        }
        List<Entry> entries = new ArrayList<>();
        for (CompoundTag original : stored) {
            if (!original.hasUUID("uuid")) {
                continue;
            }
            CompoundTag payload = original.copy();
            UUID oldId = payload.getUUID("uuid");
            UUID newId = UUID.randomUUID();
            remap.put(oldId, newId);
            if (anchorId != null && oldId.equals(anchorId) && payload.contains("pose")) {
                anchorPos = CapsulePayloads.posePosition(payload);
            }
            entries.add(new Entry(original, payload));
        }
        if (entries.isEmpty()) {
            return new RestoreOutcome(0, List.copyOf(stored));
        }
        if (anchorPos == null && entries.getFirst().working.contains("pose")) {
            anchorPos = CapsulePayloads.posePosition(entries.getFirst().working);
        }
        Vec3 delta = anchorPos == null ? Vec3.ZERO : releaseAt.subtract(anchorPos);
        for (Entry entry : entries) {
            UUID oldId = entry.working.getUUID("uuid");
            entry.working.putUUID("uuid", remap.get(oldId));
            remapDependencies(entry.working, remap);
            CapsulePayloads.stripVelocities(entry.working);
            CapsulePayloads.translateBy(entry.working, delta.x, delta.y, delta.z);
        }

        Map<UUID, Entry> byNewId = new HashMap<>();
        for (Entry entry : entries) {
            byNewId.put(entry.working.getUUID("uuid"), entry);
        }
        List<CompoundTag> worklist = new ArrayList<>();
        for (Entry entry : entries) {
            worklist.add(entry.working);
        }

        int loaded = 0;
        List<CompoundTag> remaining = new ArrayList<>();
        // 缺 uuid 的原始条目原样保留
        for (CompoundTag original : stored) {
            if (!original.hasUUID("uuid")) {
                remaining.add(original.copy());
            }
        }
        for (CompoundTag payload : sortByDependencies(worklist)) {
            Entry entry = byNewId.get(payload.getUUID("uuid"));
            ServerSubLevel sub = SubLevelSerializer.fullyLoad(level, SubLevelSerializer.fromData(payload));
            if (sub == null || sub.isRemoved()) {
                if (entry != null) {
                    remaining.add(entry.original.copy());
                }
                continue;
            }
            sub.latestLinearVelocity.set(0.0D, 0.0D, 0.0D);
            sub.latestAngularVelocity.set(0.0D, 0.0D, 0.0D);
            if (payload.contains("pose")) {
                Vec3 pos = CapsulePayloads.posePosition(payload);
                sub.logicalPose().position().set(pos.x, pos.y, pos.z);
            }
            sub.updateLastPose();
            loaded++;
        }
        return new RestoreOutcome(loaded, List.copyOf(remaining));
    }

    public static boolean contains(List<ServerSubLevel> group, @Nullable UUID structureId) {
        if (structureId == null) {
            return false;
        }
        for (ServerSubLevel subLevel : group) {
            if (structureId.equals(subLevel.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    private static void enqueue(LinkedHashMap<UUID, SubLevel> group, ArrayDeque<SubLevel> queue, SubLevel other) {
        if (other == null || other.isRemoved()) {
            return;
        }
        UUID id = other.getUniqueId();
        if (id == null || group.containsKey(id)) {
            return;
        }
        group.put(id, other);
        queue.add(other);
    }

    private static void remapDependencies(CompoundTag payload, Map<UUID, UUID> remap) {
        if (!payload.contains("loading_dependencies", Tag.TAG_LIST)) {
            return;
        }
        ListTag deps = payload.getList("loading_dependencies", Tag.TAG_INT_ARRAY);
        ListTag remapped = new ListTag();
        for (int i = 0; i < deps.size(); i++) {
            UUID old = NbtUtils.loadUUID(deps.get(i));
            remapped.add(NbtUtils.createUUID(remap.getOrDefault(old, old)));
        }
        payload.put("loading_dependencies", remapped);
    }

    private static List<CompoundTag> sortByDependencies(List<CompoundTag> payloads) {
        Set<UUID> all = new HashSet<>();
        for (CompoundTag payload : payloads) {
            all.add(payload.getUUID("uuid"));
        }
        List<CompoundTag> remaining = new ArrayList<>(payloads);
        List<CompoundTag> ordered = new ArrayList<>();
        Set<UUID> placed = new HashSet<>();
        while (!remaining.isEmpty()) {
            boolean progress = false;
            Iterator<CompoundTag> iterator = remaining.iterator();
            while (iterator.hasNext()) {
                CompoundTag payload = iterator.next();
                if (dependenciesReady(payload, placed, all)) {
                    ordered.add(payload);
                    placed.add(payload.getUUID("uuid"));
                    iterator.remove();
                    progress = true;
                }
            }
            if (!progress) {
                ordered.addAll(remaining);
                break;
            }
        }
        return ordered;
    }

    private static boolean dependenciesReady(CompoundTag payload, Set<UUID> placed, Set<UUID> all) {
        if (!payload.contains("loading_dependencies", Tag.TAG_LIST)) {
            return true;
        }
        ListTag deps = payload.getList("loading_dependencies", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < deps.size(); i++) {
            UUID dep = NbtUtils.loadUUID(deps.get(i));
            if (all.contains(dep) && !placed.contains(dep)) {
                return false;
            }
        }
        return true;
    }
}
