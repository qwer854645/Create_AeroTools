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

    public static boolean restore(ServerLevel level, List<CompoundTag> stored, Vec3 releaseAt, @Nullable UUID anchorId) {
        Map<UUID, UUID> remap = new HashMap<>();
        Vec3 anchorPos = null;
        List<CompoundTag> payloads = new ArrayList<>();
        for (CompoundTag original : stored) {
            CompoundTag payload = original.copy();
            UUID oldId = payload.getUUID("uuid");
            UUID newId = UUID.randomUUID();
            remap.put(oldId, newId);
            if (anchorId != null && oldId.equals(anchorId) && payload.contains("pose")) {
                anchorPos = CapsulePayloads.posePosition(payload);
            }
            payloads.add(payload);
        }
        if (anchorPos == null && !payloads.isEmpty() && payloads.getFirst().contains("pose")) {
            anchorPos = CapsulePayloads.posePosition(payloads.getFirst());
        }
        Vec3 delta = anchorPos == null ? Vec3.ZERO : releaseAt.subtract(anchorPos);
        for (CompoundTag payload : payloads) {
            UUID oldId = payload.getUUID("uuid");
            payload.putUUID("uuid", remap.get(oldId));
            remapDependencies(payload, remap);
            CapsulePayloads.stripVelocities(payload);
            CapsulePayloads.translateBy(payload, delta.x, delta.y, delta.z);
        }

        boolean any = false;
        for (CompoundTag payload : sortByDependencies(payloads)) {
            ServerSubLevel loaded = SubLevelSerializer.fullyLoad(level, SubLevelSerializer.fromData(payload));
            if (loaded == null || loaded.isRemoved()) {
                continue;
            }
            loaded.latestLinearVelocity.set(0.0D, 0.0D, 0.0D);
            loaded.latestAngularVelocity.set(0.0D, 0.0D, 0.0D);
            if (payload.contains("pose")) {
                Vec3 pos = CapsulePayloads.posePosition(payload);
                loaded.logicalPose().position().set(pos.x, pos.y, pos.z);
            }
            loaded.updateLastPose();
            any = true;
        }
        return any;
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
