package com.createaerotools.client;

import com.createaerotools.config.CATConfig;
import com.createaerotools.item.PhysicsToolGunItem;
import com.createaerotools.network.ToolGunActionPayload;
import com.createaerotools.network.ToolGunBeamsPayload;
import com.createaerotools.network.ToolGunDragPayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.createmod.catnip.outliner.LineOutline;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 物理工具枪客户端：抓取 / 冻结 / 滚轮调距与锯齿光束渲染。
 * <p>
 * 交互、约束瞄准与光束逻辑参考 Create Simulated Creative Physics Staff
 *（MIT，Copyright (c) The Simulated Team / The Creators of Aeronautics）。
 * 未使用 Simulated 的资源文件。
 */
public final class PhysicsToolGunClient {
    private static final int BEAM_COLOR = 0xFFF4DC;
    private static ClientDragSession session;
    private static GrabBeam beam;
    private static final Map<UUID, RemoteGrab> remoteGrabs = new HashMap<>();
    private static float lastYRot;
    private static float lastXRot;
    private static int lockCooldown;
    private static int dragSendCooldown;
    private static final Vector3d lastSentGoal = new Vector3d();
    private static final Quaterniond lastSentOrientation = new Quaterniond();
    private static boolean hasSentDrag;

    private PhysicsToolGunClient() {
    }

    public static void syncRemoteBeams(List<ToolGunBeamsPayload.Session> sessions) {
        Minecraft mc = Minecraft.getInstance();
        UUID self = mc.player == null ? null : mc.player.getUUID();
        Map<UUID, ToolGunBeamsPayload.Session> incoming = new HashMap<>();
        for (ToolGunBeamsPayload.Session entry : sessions) {
            if (!entry.player().equals(self)) {
                incoming.put(entry.player(), entry);
            }
        }
        Iterator<Map.Entry<UUID, RemoteGrab>> iterator = remoteGrabs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, RemoteGrab> entry = iterator.next();
            if (!incoming.containsKey(entry.getKey())) {
                entry.getValue().beam.release();
                iterator.remove();
            }
        }
        for (ToolGunBeamsPayload.Session entry : incoming.values()) {
            RemoteGrab existing = remoteGrabs.get(entry.player());
            if (existing == null) {
                remoteGrabs.put(entry.player(), new RemoteGrab(entry));
            } else {
                existing.update(entry);
            }
        }
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(PhysicsToolGunClient::onScroll);
        NeoForge.EVENT_BUS.addListener(PhysicsToolGunClient::onLeftClickEmpty);
        NeoForge.EVENT_BUS.addListener(PhysicsToolGunClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(PhysicsToolGunClient::onRenderLevel);
    }

    public static void onRightClick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !PhysicsToolGunItem.isHolding(player)) {
            return;
        }
        if (session != null) {
            stopDragging();
            return;
        }
        Aimed aimed = aim(player);
        if (aimed == null) {
            return;
        }
        double distance = clampDistance(player.getEyePosition().distanceTo(aimed.world()));
        session = new ClientDragSession(aimed.subLevel(), aimed.local(), new Quaterniond(aimed.subLevel().logicalPose().orientation()), distance);
        lastYRot = player.getYRot();
        lastXRot = player.getXRot();
        beam = new GrabBeam(muzzle(player, true), aimed.world());
        burst(player.level(), aimed.world());
        sendDrag(player);
    }

    public static void onLock() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !PhysicsToolGunItem.isHolding(player) || lockCooldown > 0) {
            return;
        }
        lockCooldown = 8;
        UUID structure = session != null ? session.subLevel.getUniqueId() : null;
        Vec3 spark = null;
        if (structure == null) {
            Aimed aimed = aim(player);
            if (aimed != null) {
                structure = aimed.subLevel().getUniqueId();
                spark = aimed.world();
            }
        } else {
            spark = worldAnchor(session, 1.0F);
        }
        if (structure == null) {
            return;
        }
        if (spark != null) {
            burst(player.level(), spark);
        }
        PacketDistributor.sendToServer(new ToolGunActionPayload(ToolGunActionPayload.Action.LOCK, structure));
        if (session != null) {
            stopDragging();
        }
    }

    private static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (event.getItemStack().getItem() instanceof PhysicsToolGunItem) {
            onLock();
        }
    }

    private static void onScroll(InputEvent.MouseScrollingEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || session == null || !PhysicsToolGunItem.isHolding(player) || Minecraft.getInstance().screen != null) {
            return;
        }
        boolean sprint = Minecraft.getInstance().options.keySprint.isDown();
        double multiplier = Math.max(1.0D, Math.sqrt(session.distance / 10.0D)) * (sprint ? 4.0D : 1.0D);
        session.distance = clampDistance(session.distance + event.getScrollDeltaY() * multiplier);
        event.setCanceled(true);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (lockCooldown > 0) {
            lockCooldown--;
        }
        if (player == null || level == null) {
            if (session != null) {
                stopDragging();
            }
            remoteGrabs.clear();
            beam = null;
            return;
        }
        if (!PhysicsToolGunItem.isHolding(player) && session != null) {
            stopDragging();
        }
        if (session != null && session.subLevel.isRemoved()) {
            stopDragging();
        }
        if (session != null) {
            if (player.isShiftKeyDown()) {
                rotate(player);
            }
            lastYRot = player.getYRot();
            lastXRot = player.getXRot();
            sendDrag(player);
            Vec3 end = worldAnchor(session, 1.0F);
            if (beam == null) {
                beam = new GrabBeam(muzzle(player, true), end);
            }
            beam.hold(end.distanceTo(muzzle(player, true)));
            sparkle(level, end);
        } else if (beam != null) {
            beam.release();
        }
        if (beam != null) {
            beam.tick();
            if (beam.dead()) {
                beam = null;
            }
        }
        tickRemote(level);
    }

    private static void tickRemote(Level level) {
        Iterator<RemoteGrab> iterator = remoteGrabs.values().iterator();
        while (iterator.hasNext()) {
            RemoteGrab grab = iterator.next();
            Vec3 end = grab.worldEnd(level, 1.0F);
            if (end != null) {
                sparkle(level, end);
                Player other = level.getPlayerByUUID(grab.player);
                Vec3 start = other instanceof Player p ? muzzle(p, false) : end;
                grab.beam.hold(start.distanceTo(end));
            } else {
                grab.beam.release();
            }
            grab.beam.tick();
            if (grab.beam.dead()) {
                iterator.remove();
            }
        }
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || (beam == null && remoteGrabs.isEmpty())) {
            return;
        }
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack ms = event.getPoseStack();
        SuperRenderTypeBuffer buffer = DefaultSuperRenderTypeBuffer.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        if (beam != null) {
            Vec3 start = muzzle(player, true);
            Vec3 end = session != null ? worldAnchor(session, pt) : beam.lastEnd;
            beam.render(start, end, ms, buffer, camera, pt);
        }
        for (RemoteGrab grab : remoteGrabs.values()) {
            Player other = level.getPlayerByUUID(grab.player);
            Vec3 end = grab.worldEnd(level, pt);
            if (other == null || end == null) {
                continue;
            }
            grab.beam.render(muzzle(other, false), end, ms, buffer, camera, pt);
        }
        buffer.draw();
        RenderSystem.enableCull();
    }

    private static Vec3 muzzle(Player player, boolean localFirstPerson) {
        boolean left = holdingArm(player) == HumanoidArm.LEFT;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (localFirstPerson && player == Minecraft.getInstance().player && !camera.isDetached()) {
            Vec3 origin = camera.getPosition();
            Vec3 look = new Vec3(camera.getLookVector());
            Vec3 up = new Vec3(camera.getUpVector());
            Vec3 right = look.cross(up).normalize();
            double side = left ? -0.52D : 0.52D;
            return origin.add(look.scale(0.72D)).add(right.scale(side)).add(up.scale(-0.28D));
        }
        Vec3 look = player.getLookAngle();
        Vec3 right = player.calculateViewVector(0.0F, player.getYRot() - 90.0F);
        double side = left ? -0.38D : 0.38D;
        return player.getEyePosition(1.0F)
                .add(look.scale(0.45D))
                .add(right.scale(side))
                .add(0.0D, -0.35D, 0.0D);
    }

    private static HumanoidArm holdingArm(Player player) {
        ItemStack held = PhysicsToolGunItem.held(player);
        if (held != null && held == player.getOffhandItem()
                && !(player.getMainHandItem().getItem() instanceof PhysicsToolGunItem)) {
            return player.getMainArm().getOpposite();
        }
        return player.getMainArm();
    }

    private static Vec3 worldAnchor(ClientDragSession drag, float partialTick) {
        Pose3d pose = new Pose3d();
        drag.subLevel.lastPose().lerp(drag.subLevel.logicalPose(), partialTick, pose);
        return pose.transformPosition(JOMLConversion.toMojang(drag.local));
    }

    private static void burst(Level level, Vec3 at) {
        RandomSource random = level.getRandom();
        for (int i = 0; i < 10; i++) {
            level.addParticle(ParticleTypes.END_ROD, at.x, at.y, at.z,
                    (random.nextDouble() - 0.5D) * 0.2D,
                    (random.nextDouble() - 0.5D) * 0.2D,
                    (random.nextDouble() - 0.5D) * 0.2D);
        }
    }

    private static void sparkle(Level level, Vec3 at) {
        if (level.getRandom().nextInt(5) != 0) {
            return;
        }
        RandomSource random = level.getRandom();
        level.addParticle(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z,
                (random.nextDouble() - 0.5D) * 0.08D,
                (random.nextDouble() - 0.5D) * 0.08D,
                (random.nextDouble() - 0.5D) * 0.08D);
    }

    private static void rotate(LocalPlayer player) {
        float dYaw = player.getYRot() - lastYRot;
        float dPitch = player.getXRot() - lastXRot;
        if (Math.abs(dYaw) + Math.abs(dPitch) < 0.01F) {
            return;
        }
        Vec3 axis = player.calculateViewVector(0.0F, player.getYRot() - 90.0F);
        session.orientation.rotateLocalY(Math.toRadians(dYaw));
        session.orientation.premul(new Quaterniond(new AxisAngle4d(Math.toRadians(-dPitch), axis.x, axis.y, axis.z)));
    }

    private static void sendDrag(Player player) {
        if (session == null || session.subLevel.getUniqueId() == null) {
            return;
        }
        Vec3 goal = player.getLookAngle().scale(session.distance);
        Vector3dc goalJoml = JOMLConversion.toJOML(goal);
        // 限流：位置/朝向几乎没变时最多每 2 tick 发一次
        boolean moved = !hasSentDrag
                || lastSentGoal.distanceSquared(goalJoml) > 1.0E-4D
                || Math.abs(lastSentOrientation.dot(session.orientation)) < 0.9999D
                || Math.abs(lastSentGoal.lengthSquared() - goalJoml.lengthSquared()) > 1.0E-4D;
        if (dragSendCooldown > 0 && !moved) {
            dragSendCooldown--;
            return;
        }
        if (dragSendCooldown > 0 && moved) {
            dragSendCooldown--;
            if (dragSendCooldown > 0 && lastSentGoal.distanceSquared(goalJoml) < 0.0025D) {
                return;
            }
        }
        dragSendCooldown = 2;
        hasSentDrag = true;
        lastSentGoal.set(goalJoml);
        lastSentOrientation.set(session.orientation);
        PacketDistributor.sendToServer(new ToolGunDragPayload(
                session.subLevel.getUniqueId(),
                new Vector3d(goalJoml),
                new Vector3d(session.local),
                new Quaterniond(session.orientation)
        ));
    }

    private static void stopDragging() {
        if (session != null && session.subLevel.getUniqueId() != null) {
            PacketDistributor.sendToServer(new ToolGunActionPayload(ToolGunActionPayload.Action.STOP_DRAG, session.subLevel.getUniqueId()));
        }
        session = null;
        hasSentDrag = false;
        dragSendCooldown = 0;
        if (beam != null) {
            beam.release();
        }
    }

    private static Aimed aim(Player player) {
        Level level = player.level();
        double range = CATConfig.SERVER.toolGunRange.get();
        HitResult hit = player.pick(range, 1.0F, false);
        if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK) {
            SubLevelAccess access = SableCompanion.INSTANCE.getContaining(level, hit.getLocation());
            if (access instanceof SubLevel subLevel && !subLevel.isRemoved() && subLevel.getUniqueId() != null) {
                Vector3d local = JOMLConversion.atCenterOf(block.getBlockPos());
                Vec3 world = subLevel.logicalPose().transformPosition(JOMLConversion.toMojang(local));
                return new Aimed(subLevel, local, world);
            }
        }
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(range));
        SubLevel best = null;
        Vec3 bestHit = null;
        double bestDist = range + 1.0D;
        for (SubLevel subLevel : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            if (subLevel.isRemoved() || subLevel.getUniqueId() == null) {
                continue;
            }
            AABB box = subLevel.boundingBox().toMojang();
            Optional<Vec3> clip = box.clip(start, end);
            if (clip.isEmpty()) {
                continue;
            }
            double dist = clip.get().distanceTo(start);
            if (dist < bestDist) {
                bestDist = dist;
                best = subLevel;
                bestHit = clip.get();
            }
        }
        if (best == null || bestHit == null) {
            return null;
        }
        Vec3 local = best.logicalPose().transformPositionInverse(bestHit);
        return new Aimed(best, new Vector3d(local.x, local.y, local.z), bestHit);
    }

    private static double clampDistance(double distance) {
        return Math.clamp(distance, 2.0D, CATConfig.SERVER.toolGunRange.get());
    }

    private record Aimed(SubLevel subLevel, Vector3dc local, Vec3 world) {
    }

    private static final class ClientDragSession {
        private final SubLevel subLevel;
        private final Vector3dc local;
        private final Quaterniond orientation;
        private double distance;

        private ClientDragSession(SubLevel subLevel, Vector3dc local, Quaterniond orientation, double distance) {
            this.subLevel = subLevel;
            this.local = local;
            this.orientation = orientation;
            this.distance = distance;
        }
    }

    private static final class RemoteGrab {
        private UUID player;
        private UUID structure;
        private Vector3dc anchor;
        private final GrabBeam beam;

        private RemoteGrab(ToolGunBeamsPayload.Session session) {
            this.player = session.player();
            this.structure = session.structure();
            this.anchor = session.anchor();
            this.beam = new GrabBeam(Vec3.ZERO, Vec3.ZERO);
        }

        private void update(ToolGunBeamsPayload.Session session) {
            player = session.player();
            structure = session.structure();
            anchor = session.anchor();
        }

        @Nullable
        private Vec3 worldEnd(Level level, float partialTick) {
            SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(structure);
            if (subLevel == null || subLevel.isRemoved()) {
                return null;
            }
            Pose3d pose = new Pose3d();
            subLevel.lastPose().lerp(subLevel.logicalPose(), partialTick, pose);
            return pose.transformPosition(JOMLConversion.toMojang(anchor));
        }
    }

    /**
     * Jagged staff-style beam. Node jitter is adapted from Simulated's PhysicsBeam (MIT).
     */
    private static final class GrabBeam {
        private static final float TARGET_SPACING = 1.5F;
        private static final int MIN_POINTS = 8;
        private final LineOutline line = new LineOutline();
        private final List<BeamNode> nodes = new ArrayList<>();
        private Vec3 lastEnd = Vec3.ZERO;
        private double length;
        private double nodeRadius;
        private float intensity = 1.0F;
        private boolean holding = true;

        private GrabBeam(Vec3 start, Vec3 end) {
            this.length = start.distanceTo(end);
            this.lastEnd = end;
            line.getParams().colored(BEAM_COLOR).disableLineNormals().lineWidth(0.65F / 16.0F);
            rebuild();
        }

        private void hold(double length) {
            this.length = length;
            holding = true;
            intensity = 1.0F;
        }

        private void release() {
            holding = false;
        }

        private boolean dead() {
            return !holding && intensity < 0.08F;
        }

        private void tick() {
            if (!holding) {
                intensity *= 0.62F;
            }
            rebuild();
            for (int i = 1; i < nodes.size() - 1; i++) {
                nodes.get(i).jolt();
            }
        }

        private void rebuild() {
            double scaled = length / TARGET_SPACING;
            double target = MIN_POINTS * MIN_POINTS / (scaled + MIN_POINTS) + scaled;
            if (target > 256.0D) {
                target = 256.0D;
            }
            nodeRadius = 0.18D * Math.sqrt(scaled / Math.max(1.0D, target));
            while (nodes.size() < target - 0.7D) {
                nodes.add(new BeamNode());
            }
            while (nodes.size() > target + 0.7D && !nodes.isEmpty()) {
                nodes.removeFirst();
            }
        }

        private void render(Vec3 start, Vec3 end, PoseStack ms, SuperRenderTypeBuffer buffer, Vec3 camera, float pt) {
            lastEnd = end;
            Vec3 relative = end.subtract(start);
            length = relative.length();
            if (length < 1.0E-4D || nodes.size() < 2) {
                return;
            }
            int alpha = Mth.clamp((int) (intensity * 255.0F), 0, 255);
            line.getParams().colored((alpha << 24) | (BEAM_COLOR & 0xFFFFFF));
            Vec3 last = start;
            for (int i = 1; i < nodes.size(); i++) {
                Vec3 offset = nodes.get(i).lerp(pt).scale(nodeRadius);
                Vec3 current = start.add(relative.scale(i / (float) nodes.size()).add(offset));
                line.set(last, current).render(ms, buffer, camera, pt);
                last = current;
            }
            line.set(last, end).render(ms, buffer, camera, pt);

            double cube = 0.12D * intensity;
            AABB box = new AABB(end, end).inflate(cube);
            line.set(new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.minY, box.minZ)).render(ms, buffer, camera, pt);
            line.set(new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.minX, box.maxY, box.minZ)).render(ms, buffer, camera, pt);
            line.set(new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.minX, box.minY, box.maxZ)).render(ms, buffer, camera, pt);
            line.set(new Vec3(box.maxX, box.maxY, box.maxZ), new Vec3(box.minX, box.maxY, box.maxZ)).render(ms, buffer, camera, pt);
            line.set(new Vec3(box.maxX, box.maxY, box.maxZ), new Vec3(box.maxX, box.minY, box.maxZ)).render(ms, buffer, camera, pt);
            line.set(new Vec3(box.maxX, box.maxY, box.maxZ), new Vec3(box.maxX, box.maxY, box.minZ)).render(ms, buffer, camera, pt);
        }
    }

    private static final class BeamNode {
        private Vec3 position = Vec3.ZERO;
        private Vec3 previous = Vec3.ZERO;

        private void jolt() {
            RandomSource random = Minecraft.getInstance().level.random;
            previous = position;
            position = position.offsetRandom(random, 3.0F).scale(0.5D);
        }

        private Vec3 lerp(float pt) {
            return previous.lerp(position, pt);
        }
    }
}
