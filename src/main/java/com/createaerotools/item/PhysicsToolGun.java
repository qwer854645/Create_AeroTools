package com.createaerotools.item;

import com.createaerotools.config.CATConfig;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import com.createaerotools.network.ToolGunBeamsPayload;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 生存物理工具枪的服务端逻辑（SavedData 按维度持久化抓取会话）。
 * <p>
 * 拖拽 / 冻结约束电机改编自 Create Simulated 的 Creative Physics Staff
 *（MIT，Copyright (c) The Simulated Team / The Creators of Aeronautics）。
 */
public final class PhysicsToolGun extends SavedData implements SubLevelObserver {
    public static final String ID = "createaerotools_tool_gun";

    private final Map<UUID, Lock> locks = new HashMap<>();
    private final Map<UUID, DragSession> sessions = new HashMap<>();
    private final Map<UUID, Integer> consumeTicks = new HashMap<>();
    private ServerLevel level;
    private boolean observing;
    private boolean beamsDirty;

    public PhysicsToolGun() {
    }

    public static PhysicsToolGun get(ServerLevel level) {
        PhysicsToolGun data = level.getChunkSource().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PhysicsToolGun::new, PhysicsToolGun::load, null), ID);
        data.level = level;
        data.ensureObserver();
        return data;
    }

    public static PhysicsToolGun load(CompoundTag tag, HolderLookup.Provider registries) {
        PhysicsToolGun data = new PhysicsToolGun();
        ListTag list = tag.getList("Locks", Tag.TAG_INT_ARRAY);
        for (Tag entry : list) {
            UUID id = NbtUtils.loadUUID(entry);
            data.locks.put(id, new Lock(id, null));
        }
        return data;
    }

    public static void tickLevel(ServerLevel level) {
        get(level).tick();
    }

    public static void physicsTick(SubLevelPhysicsSystem physicsSystem) {
        ServerLevel level = physicsSystem.getLevel();
        if (level != null) {
            get(level).onPhysicsTick(physicsSystem);
        }
    }

    public static void clearLevel(ServerLevel level) {
        PhysicsToolGun data = get(level);
        data.sessions.values().forEach(DragSession::onRemoved);
        data.sessions.clear();
        data.consumeTicks.clear();
        data.broadcastBeams();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (UUID id : locks.keySet()) {
            list.add(NbtUtils.createUUID(id));
        }
        tag.put("Locks", list);
        return tag;
    }

    public void drag(Player player, UUID structure, Vector3dc relativeGoal, Vector3dc localAnchor, Quaterniondc orientation) {
        ItemStack stack = PhysicsToolGunItem.held(player);
        if (stack == null || !(level.getPlayerByUUID(player.getUUID()) instanceof Player)) {
            return;
        }
        SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(structure);
        if (!(subLevel instanceof ServerSubLevel server) || server.isRemoved()) {
            return;
        }
        removeLock(server);
        DragSession session = sessions.get(player.getUUID());
        if (session == null) {
            if (!pay(player, stack)) {
                return;
            }
            session = new DragSession(player.getUUID(), server);
            sessions.put(player.getUUID(), session);
            consumeTicks.put(player.getUUID(), 0);
            beamsDirty = true;
        } else if (session.subLevel.getUniqueId() == null || !session.subLevel.getUniqueId().equals(structure)
                || session.plotAnchor.distanceSquared(localAnchor) > 1.0E-6D) {
            beamsDirty = true;
        }
        session.relativeGoal.set(relativeGoal);
        session.plotAnchor.set(localAnchor);
        session.orientation.set(orientation);
        if (beamsDirty) {
            broadcastBeams();
        }
    }

    public void stopDragging(UUID playerId) {
        DragSession session = sessions.remove(playerId);
        consumeTicks.remove(playerId);
        if (session != null) {
            session.onRemoved();
            broadcastBeams();
        }
    }

    public void sendBeamsTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, snapshot());
    }

    public void toggleLock(UUID structure) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        SubLevel subLevel = container.getSubLevel(structure);
        if (!(subLevel instanceof ServerSubLevel server)) {
            return;
        }
        Lock existing = locks.remove(structure);
        if (existing != null) {
            existing.remove();
            setDirty();
            return;
        }
        locks.put(structure, new Lock(structure, addLock(container, server)));
        setDirty();
    }

    public void toggleLock(Player player, UUID structure) {
        ItemStack stack = PhysicsToolGunItem.held(player);
        if (stack == null) {
            return;
        }
        if (locks.containsKey(structure)) {
            toggleLock(structure);
            return;
        }
        if (!pay(player, stack)) {
            return;
        }
        toggleLock(structure);
    }

    @Override
    public void onSubLevelAdded(SubLevel subLevel) {
        applyLockIfNeeded(subLevel);
    }

    @Override
    public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
        Lock lock = locks.get(subLevel.getUniqueId());
        if (lock != null) {
            lock.remove();
            lock.handle = null;
        }
        sessions.values().removeIf(session -> {
            UUID id = session.subLevel.getUniqueId();
            if (id != null && id.equals(subLevel.getUniqueId())) {
                session.onRemoved();
                beamsDirty = true;
                return true;
            }
            return false;
        });
        if (beamsDirty) {
            broadcastBeams();
        }
    }

    private void tick() {
        Iterator<Map.Entry<UUID, DragSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, DragSession> entry = iterator.next();
            DragSession session = entry.getValue();
            Player player = level.getPlayerByUUID(entry.getKey());
            ItemStack stack = player == null ? null : PhysicsToolGunItem.held(player);
            if (player == null || stack == null || session.subLevel.isRemoved()) {
                session.onRemoved();
                consumeTicks.remove(entry.getKey());
                iterator.remove();
                beamsDirty = true;
                continue;
            }
            int ticks = consumeTicks.getOrDefault(entry.getKey(), 0) + 1;
            consumeTicks.put(entry.getKey(), ticks);
            if (ticks % Math.max(1, CATConfig.SERVER.toolGunConsumeTicks.get()) == 0 && !pay(player, stack)) {
                session.onRemoved();
                consumeTicks.remove(entry.getKey());
                iterator.remove();
                beamsDirty = true;
            }
        }
        if (beamsDirty) {
            broadcastBeams();
        }
    }

    private void broadcastBeams() {
        beamsDirty = false;
        if (level != null) {
            PacketDistributor.sendToPlayersInDimension(level, snapshot());
        }
    }

    private ToolGunBeamsPayload snapshot() {
        List<ToolGunBeamsPayload.Session> list = new ArrayList<>(sessions.size());
        for (DragSession session : sessions.values()) {
            UUID structure = session.subLevel.getUniqueId();
            if (structure == null) {
                continue;
            }
            list.add(new ToolGunBeamsPayload.Session(session.playerId, structure, new Vector3d(session.plotAnchor)));
        }
        return new ToolGunBeamsPayload(list);
    }

    private void onPhysicsTick(SubLevelPhysicsSystem physicsSystem) {
        for (DragSession session : sessions.values()) {
            session.physicsTick(physicsSystem);
        }
    }

    private void applyLockIfNeeded(SubLevel subLevel) {
        Lock lock = locks.get(subLevel.getUniqueId());
        if (lock == null || (lock.handle != null && lock.handle.isValid()) || !(subLevel instanceof ServerSubLevel server)) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        lock.handle = addLock(container, server);
    }

    private void removeLock(ServerSubLevel subLevel) {
        Lock lock = locks.remove(subLevel.getUniqueId());
        if (lock != null) {
            lock.remove();
            setDirty();
        }
    }

    private static PhysicsConstraintHandle addLock(ServerSubLevelContainer container, ServerSubLevel subLevel) {
        return container.physicsSystem().getPipeline().addConstraint(null, subLevel, new FixedConstraintConfiguration(
                subLevel.logicalPose().position(),
                subLevel.logicalPose().rotationPoint(),
                subLevel.logicalPose().orientation()
        ));
    }

    private void ensureObserver() {
        if (observing || level == null) {
            return;
        }
        SubLevelContainer.getContainer(level).addObserver(this);
        observing = true;
        for (SubLevel subLevel : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            applyLockIfNeeded(subLevel);
        }
    }

    private static boolean pay(Player player, ItemStack stack) {
        if (player.getAbilities().instabuild) {
            return true;
        }
        if (BacktankUtil.canAbsorbDamage(player, CATConfig.SERVER.toolGunAirUses.get())) {
            return true;
        }
        if (!stack.isDamageableItem()) {
            return false;
        }
        EquipmentSlot slot = player.getMainHandItem() == stack ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
        stack.hurtAndBreak(1, player, slot);
        return !stack.isEmpty();
    }

    private static final class Lock {
        private final UUID structure;
        @Nullable
        private PhysicsConstraintHandle handle;

        private Lock(UUID structure, @Nullable PhysicsConstraintHandle handle) {
            this.structure = structure;
            this.handle = handle;
        }

        private void remove() {
            if (handle != null) {
                handle.remove();
                handle = null;
            }
        }
    }

    private static final class DragSession {
        private final UUID playerId;
        private final ServerSubLevel subLevel;
        private final Vector3d plotAnchor = new Vector3d();
        private final Vector3d relativeGoal = new Vector3d();
        private final Vector3d localGoal = new Vector3d();
        private final Quaterniond orientation = new Quaterniond();
        @Nullable
        private PhysicsConstraintHandle constraint;

        private DragSession(UUID playerId, ServerSubLevel subLevel) {
            this.playerId = playerId;
            this.subLevel = subLevel;
        }

        private void physicsTick(SubLevelPhysicsSystem physicsSystem) {
            if (subLevel.isRemoved()) {
                return;
            }
            if (constraint != null) {
                constraint.remove();
                constraint = null;
            }
            PhysicsPipeline pipeline = physicsSystem.getPipeline();
            constraint = pipeline.addConstraint(null, subLevel,
                    new FreeConstraintConfiguration(JOMLConversion.ZERO, plotAnchor, orientation));
            if (constraint == null) {
                return;
            }
            float angularStiffness = CATConfig.SERVER.toolGunAngularStiffness.get().floatValue();
            float angularDamping = CATConfig.SERVER.toolGunAngularDamping.get().floatValue();
            float linearStiffness = CATConfig.SERVER.toolGunLinearStiffness.get().floatValue();
            float linearDamping = CATConfig.SERVER.toolGunLinearDamping.get().floatValue();
            // hasForceLimit=true：与 DriveShaftPhysics 相同，避免无限力弹飞结构
            double maxAngular = CATConfig.SERVER.toolGunMaxAngularForce.get();
            double maxLinear = CATConfig.SERVER.toolGunMaxLinearForce.get();
            boolean limitAngular = maxAngular > 0.0D;
            boolean limitLinear = maxLinear > 0.0D;
            for (ConstraintJointAxis axis : ConstraintJointAxis.ANGULAR) {
                constraint.setMotor(axis, 0.0D, angularStiffness, angularDamping, limitAngular, maxAngular);
            }
            Player player = subLevel.getLevel().getPlayerByUUID(playerId);
            if (player == null) {
                return;
            }
            double pt = physicsSystem.getPartialPhysicsTick();
            double eyeX = Mth.lerp(pt, player.xOld, player.getX());
            double eyeY = Mth.lerp(pt, player.yOld, player.getY()) + player.getEyeHeight();
            double eyeZ = Mth.lerp(pt, player.zOld, player.getZ());
            localGoal.set(relativeGoal).add(eyeX, eyeY, eyeZ);
            orientation.transformInverse(localGoal);
            constraint.setMotor(ConstraintJointAxis.LINEAR_X, localGoal.x(), linearStiffness, linearDamping, limitLinear, maxLinear);
            constraint.setMotor(ConstraintJointAxis.LINEAR_Y, localGoal.y(), linearStiffness, linearDamping, limitLinear, maxLinear);
            constraint.setMotor(ConstraintJointAxis.LINEAR_Z, localGoal.z(), linearStiffness, linearDamping, limitLinear, maxLinear);
        }

        private void onRemoved() {
            if (constraint != null) {
                constraint.remove();
                constraint = null;
            }
        }
    }
}
