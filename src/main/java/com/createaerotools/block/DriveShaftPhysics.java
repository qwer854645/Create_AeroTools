package com.createaerotools.block;

import com.createaerotools.CreateAeroTools;
import com.createaerotools.config.CATConfig;
import com.createaerotools.util.WorldSpace;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可伸缩万向轴 / 气动轴的 Sable 物理。
 *
 * <h2>碰撞方案（已锁定，勿回退）</h2>
 * 安山+传动端口碰撞回归后定稿，须同时满足：
 * <ul>
 *   <li><b>始终</b>生成 {@link BoxPhysicsObject} 轴盒，禁止端口↔端口直接约束（会失去轴碰撞）。</li>
 *   <li><b>对世界保持接触</b>：一端是世界（body==null）时 {@code setContactsEnabled(true)}；
 *       对结构所有者可关接触，避免盒子和关节互顶弹飞。绝不要对世界关接触。</li>
 *   <li><b>安山传动端口</b>（自由滑）：不要 {@code setMotor}；长度漂移时重建盒子使碰撞体跟可见轴一致。</li>
 *   <li><b>气动端口</b>：同样始终有盒子 + 世界接触规则。长度漂移就重建。
 *       未达目标前空闲（滞回）；接入时重建使关节零点对齐实测长度，再用限速限力滑移电机追目标。不做 COM 冲量。</li>
 *   <li><b>黄铜传动端口</b>：LINEAR_X 弹簧电机追 {@code desiredLength()}。</li>
 * </ul>
 */
public final class DriveShaftPhysics {
    private static final Set<ConstraintJointAxis> BALL = EnumSet.of(
            ConstraintJointAxis.LINEAR_X,
            ConstraintJointAxis.LINEAR_Y,
            ConstraintJointAxis.LINEAR_Z
    );
    private static final Set<ConstraintJointAxis> SLIDE = EnumSet.of(
            ConstraintJointAxis.LINEAR_Y,
            ConstraintJointAxis.LINEAR_Z
    );
    private static final Vector3d JOINT_X = new Vector3d(1.0D, 0.0D, 0.0D);
    private static final double FACE_INSET = 0.22D;
    private static final double RESIZE_HALF_TOLERANCE = 0.35D;
    /** 连续多少物理步都超差才重建，避免相对运动下每步 thrash。 */
    private static final int RESIZE_DEBOUNCE = 4;
    /** 指令可比实测长度领先多少，再等结构跟上。 */
    private static final double PNEUMATIC_MAX_LEAD = 0.35D;
    /** 约在这么多秒内达到动作速度 → 力 ≈ μ·(speed / time)。 */
    private static final double PNEUMATIC_ACCEL_TIME = 0.3D;
    private static final double PNEUMATIC_FORCE_GAIN = 2.25D;
    private static final float PNEUMATIC_FORCE_MIN = 280.0F;
    private static final float PNEUMATIC_FORCE_MAX = 16000.0F;
    private static final double FALLBACK_END_MASS = 40.0D;

    private static final Map<UUID, Shaft> SHAFTS = new ConcurrentHashMap<>();

    private DriveShaftPhysics() {
    }

    public static void sync(DrivePortBlockEntity a, DrivePortBlockEntity b) {
        Level parent = WorldSpace.parentLevel(a);
        if (!(parent instanceof ServerLevel serverLevel)) {
            return;
        }
        SHAFTS.computeIfAbsent(a.portId(), id -> new Shaft(serverLevel));
    }

    public static void destroy(UUID masterId) {
        Shaft shaft = SHAFTS.remove(masterId);
        if (shaft != null) {
            shaft.remove();
        }
    }

    public static void clearLevel(Level level) {
        Iterator<Shaft> iterator = SHAFTS.values().iterator();
        while (iterator.hasNext()) {
            Shaft shaft = iterator.next();
            if (shaft.level == level) {
                shaft.remove();
                iterator.remove();
            }
        }
    }

    public static void physicsTick() {
        Iterator<Map.Entry<UUID, Shaft>> iterator = SHAFTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Shaft> entry = iterator.next();
            DrivePortBlockEntity master = DriveLinkTracker.loadedMaster(entry.getKey());
            if (master == null || master.isRemoved()) {
                entry.getValue().remove();
                iterator.remove();
                continue;
            }
            DrivePortBlockEntity partner = DriveLinkTracker.partner(master);
            if (partner == null || partner.isRemoved()) {
                // 对端已注销时立刻拆掉，避免幽灵碰撞体卡到 BE tick 才 destroy
                entry.getValue().remove();
                iterator.remove();
                continue;
            }
            entry.getValue().keep(master, partner);
        }
    }

    private static final class Shaft {
        private final ServerLevel level;
        @Nullable
        private BoxPhysicsObject box;
        @Nullable
        private GenericConstraintHandle jointA;
        @Nullable
        private GenericConstraintHandle jointB;

        private int retry;
        /** {@code createLength - commandedLength} — same convention as brass {@code length - rest}. */
        private double springTarget;
        private double createLength;
        private double commandedLength;
        private boolean springs;
        private boolean pneumatic;
        private long lastNanos;
        /** Rebuild once when actuation arms so joint zero matches the live length. */
        private boolean rebaseOnEngage;
        private int resizeHold;

        private Shaft(ServerLevel level) {
            this.level = level;
        }

        private boolean shouldResize(DrivePortBlockEntity a, DrivePortBlockEntity b) {
            if (!needsResize(a, b)) {
                resizeHold = 0;
                return false;
            }
            resizeHold++;
            if (resizeHold < RESIZE_DEBOUNCE) {
                return false;
            }
            resizeHold = 0;
            return true;
        }

        private void keep(DrivePortBlockEntity a, DrivePortBlockEntity b) {
            if (isLive()) {
                pneumatic = a.controlsLength();
                boolean wantSpring = a.springsToTarget();
                if (pneumatic && wantSpring && !springs) {
                    rebaseOnEngage = true;
                }
                if (springs && !wantSpring) {
                    clearMotor();
                }
                springs = wantSpring;
                if (!springs) {
                    // Idle pneumatic must resize like andesite — otherwise the collider stays at
                    // the old length while the visible shaft stretches and "has no collision".
                    if (shouldResize(a, b)) {
                        remove();
                        try {
                            create(a, b);
                        } catch (RuntimeException exception) {
                            remove();
                            retry = 20;
                        }
                    }
                    return;
                }
                if (pneumatic && (rebaseOnEngage || shouldResize(a, b))) {
                    rebaseOnEngage = false;
                    remove();
                    try {
                        create(a, b);
                    } catch (RuntimeException exception) {
                        remove();
                        retry = 20;
                    }
                    return;
                }
                double desired = a.desiredLength();
                if (desired < 0.35D) {
                    desired = createLength;
                }
                if (pneumatic) {
                    tickPneumatic(a, b, desired);
                } else {
                    commandedLength = desired;
                    springTarget = createLength - commandedLength;
                    applySpring(a, b);
                }
                return;
            }
            if (retry > 0) {
                retry--;
                return;
            }
            remove();
            try {
                create(a, b);
            } catch (RuntimeException exception) {
                remove();
                if (retry == 0) {
                    CreateAeroTools.LOGGER.warn(
                            "Drive shaft constraint was rejected: {}", exception.toString());
                }
                retry = 20;
            }
        }

        private void tickPneumatic(DrivePortBlockEntity a, DrivePortBlockEntity b, double desired) {
            double current = WorldSpace.worldLinkAnchor(a, a.facing())
                    .distanceTo(WorldSpace.worldLinkAnchor(b, b.facing()));
            if (current < 0.35D) {
                return;
            }
            double dt = tickDelta();
            double speed = CATConfig.SERVER.pneumaticActuationSpeed.get();
            commandedLength = moveToward(commandedLength, desired, speed * dt);
            // Do not let the spring target race more than a short lead ahead of reality.
            if (commandedLength > current + PNEUMATIC_MAX_LEAD) {
                commandedLength = current + PNEUMATIC_MAX_LEAD;
            } else if (commandedLength < current - PNEUMATIC_MAX_LEAD) {
                commandedLength = current - PNEUMATIC_MAX_LEAD;
            }
            springTarget = createLength - commandedLength;
            applySpring(a, b);
            if (box != null) {
                box.wakeUp();
            }
        }

        private double tickDelta() {
            long now = System.nanoTime();
            double dt;
            if (lastNanos == 0L) {
                dt = 1.0D / 60.0D;
            } else {
                dt = (now - lastNanos) * 1.0E-9D;
                if (dt < 1.0E-4D) {
                    dt = 1.0E-4D;
                } else if (dt > 0.05D) {
                    dt = 0.05D;
                }
            }
            lastNanos = now;
            return dt;
        }

        private static double moveToward(double current, double target, double maxDelta) {
            double delta = target - current;
            if (Math.abs(delta) <= maxDelta) {
                return target;
            }
            return current + Math.copySign(maxDelta, delta);
        }

        private boolean needsResize(DrivePortBlockEntity a, DrivePortBlockEntity b) {
            if (box == null) {
                return true;
            }
            double length = WorldSpace.worldLinkAnchor(a, a.facing())
                    .distanceTo(WorldSpace.worldLinkAnchor(b, b.facing()));
            if (length < 0.35D) {
                return false;
            }
            double expectedHalf = Math.max(0.15D, length * 0.5D - FACE_INSET);
            Vector3dc extents = box.getHalfExtents();
            return Math.abs(extents.x() - expectedHalf) > RESIZE_HALF_TOLERANCE;
        }

        private boolean isLive() {
            return box != null && !box.isRemoved()
                    && jointA != null && jointA.isValid()
                    && jointB != null && jointB.isValid();
        }

        private void create(DrivePortBlockEntity a, DrivePortBlockEntity b) {
            PhysicsPipelineBody bodyA = body(a);
            PhysicsPipelineBody bodyB = body(b);
            if (bodyA != null && bodyA == bodyB) {
                retry = 40;
                return;
            }
            Vec3 faceA = WorldSpace.worldLinkAnchor(a, a.facing());
            Vec3 faceB = WorldSpace.worldLinkAnchor(b, b.facing());
            Vec3 delta = faceB.subtract(faceA);
            double length = delta.length();
            if (length < 0.35D) {
                retry = 40;
                return;
            }
            SubLevelPhysicsSystem physics = SubLevelPhysicsSystem.get(level);
            if (physics == null) {
                return;
            }
            PhysicsPipeline pipeline = physics.getPipeline();
            Vector3d axis = new Vector3d(delta.x, delta.y, delta.z).div(length);
            Quaterniond worldOri = new Quaterniond().rotationTo(JOINT_X, axis);
            springs = a.springsToTarget();
            pneumatic = a.controlsLength();
            createLength = length;
            commandedLength = length;
            lastNanos = 0L;
            rebaseOnEngage = false;
            double maxAngle = Math.toRadians(CATConfig.SERVER.driveMaxAngle.get());

            double radius = CATConfig.SERVER.driveShaftRadius.get();
            double half = Math.max(0.15D, length * 0.5D - FACE_INSET);
            double mass = Math.max(3.0D, length * 1.5D);
            Vector3d mid = new Vector3d(
                    (faceA.x + faceB.x) * 0.5D,
                    (faceA.y + faceB.y) * 0.5D,
                    (faceA.z + faceB.z) * 0.5D);
            box = new BoxPhysicsObject(pose(mid, worldOri), new Vector3d(half, radius, radius), mass);
            physics.addObject(box);
            Vector3d endA = new Vector3d(-length * 0.5D, 0.0D, 0.0D);
            Vector3d endB = new Vector3d(length * 0.5D, 0.0D, 0.0D);

            jointA = pipeline.addConstraint(bodyA, box,
                    new GenericConstraintConfiguration(
                            anchor(a, bodyA),
                            endA,
                            frame(a, bodyA, worldOri),
                            JOMLConversion.QUAT_IDENTITY,
                            BALL));
            jointB = pipeline.addConstraint(bodyB, box,
                    new GenericConstraintConfiguration(
                            anchor(b, bodyB),
                            endB,
                            frame(b, bodyB, worldOri),
                            JOMLConversion.QUAT_IDENTITY,
                            SLIDE));
            if (jointA == null || jointB == null) {
                remove();
                retry = 20;
                return;
            }
            jointA.setContactsEnabled(bodyA == null);
            jointB.setContactsEnabled(bodyB == null);
            double desired = a.desiredLength();
            if (desired < 0.35D) {
                desired = length;
            }
            if (!pneumatic) {
                commandedLength = desired;
            }
            springTarget = length - commandedLength;
            jointA.setLimit(ConstraintJointAxis.ANGULAR_Y, -maxAngle, maxAngle);
            jointA.setLimit(ConstraintJointAxis.ANGULAR_Z, -maxAngle, maxAngle);
            jointB.setLimit(ConstraintJointAxis.ANGULAR_Y, -maxAngle, maxAngle);
            jointB.setLimit(ConstraintJointAxis.ANGULAR_Z, -maxAngle, maxAngle);
            applySpring(a, b);
            box.wakeUp();
        }

        private void applySpring(DrivePortBlockEntity a, DrivePortBlockEntity b) {
            if (jointB == null || !jointB.isValid() || !springs) {
                return;
            }
            float stiffness;
            float damping;
            float maxForce;
            if (pneumatic) {
                double mu = reducedMass(endMass(a), endMass(b));
                if (box != null) {
                    mu = Math.max(mu, Math.max(box.getMass(), 1.0D) * 0.5D);
                }
                double speed = Math.max(0.1D, CATConfig.SERVER.pneumaticActuationSpeed.get());
                double force = mu * (speed / PNEUMATIC_ACCEL_TIME) * PNEUMATIC_FORCE_GAIN;
                maxForce = (float) Mth.clamp(force, PNEUMATIC_FORCE_MIN, PNEUMATIC_FORCE_MAX);
                // Hit the force cap at full lead so light crafts stay soft and heavy ones push hard.
                stiffness = (float) (maxForce / Math.max(PNEUMATIC_MAX_LEAD, 0.05D));
                damping = (float) Mth.clamp(2.0D * Math.sqrt(stiffness * mu), 24.0D, maxForce * 1.5D);
            } else {
                stiffness = CATConfig.SERVER.driveStiffness.get().floatValue();
                damping = CATConfig.SERVER.driveDamping.get().floatValue();
                maxForce = CATConfig.SERVER.driveMaxForce.get().floatValue();
                if (maxForce <= 0.0F) {
                    maxForce = 400.0F;
                }
            }
            // Sable's boolean is hasForceLimit — false ignores maxForce and can launch crafts.
            jointB.setMotor(ConstraintJointAxis.LINEAR_X, springTarget, stiffness, damping, true, maxForce);
        }

        private static double endMass(DrivePortBlockEntity be) {
            PhysicsPipelineBody owner = body(be);
            if (!(owner instanceof ServerSubLevel subLevel) || subLevel.isRemoved()) {
                // World / unloaded end: treat as immovable so the free end takes the acceleration.
                return Double.POSITIVE_INFINITY;
            }
            MassData mass = subLevel.getMassTracker();
            if (mass == null || mass.isInvalid() || !(mass.getMass() > 0.0D)) {
                return FALLBACK_END_MASS;
            }
            return Math.max(mass.getMass(), 1.0D);
        }

        private static double reducedMass(double massA, double massB) {
            boolean aWorld = Double.isInfinite(massA);
            boolean bWorld = Double.isInfinite(massB);
            if (aWorld && bWorld) {
                return FALLBACK_END_MASS;
            }
            if (aWorld) {
                return massB;
            }
            if (bWorld) {
                return massA;
            }
            double sum = massA + massB;
            if (sum < 1.0E-6D) {
                return FALLBACK_END_MASS;
            }
            return massA * massB / sum;
        }

        private void clearMotor() {
            if (jointB == null || !jointB.isValid()) {
                return;
            }
            jointB.setMotor(ConstraintJointAxis.LINEAR_X, 0.0D, 0.0D, 0.0D, true, 0.0D);
            springTarget = 0.0D;
        }

        private void remove() {
            if (jointA != null) {
                jointA.remove();
                jointA = null;
            }
            if (jointB != null) {
                jointB.remove();
                jointB = null;
            }
            SubLevelPhysicsSystem physics = SubLevelPhysicsSystem.get(level);
            if (physics != null && box != null) {
                physics.removeObject(box);
            }
            box = null;
            rebaseOnEngage = false;
        }
    }

    @Nullable
    private static PhysicsPipelineBody body(DrivePortBlockEntity be) {
        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(be);
        return access instanceof ServerSubLevel subLevel && !subLevel.isRemoved() ? subLevel : null;
    }

    private static Vector3d anchor(DrivePortBlockEntity be, @Nullable PhysicsPipelineBody owner) {
        if (owner == null) {
            Vec3 world = WorldSpace.worldLinkAnchor(be, be.facing());
            return new Vector3d(world.x, world.y, world.z);
        }
        Vec3 local = WorldSpace.localLinkAnchor(be.getBlockPos(), be.facing());
        return new Vector3d(local.x, local.y, local.z);
    }

    private static Quaterniond frame(DrivePortBlockEntity be, @Nullable PhysicsPipelineBody owner, Quaterniond worldOri) {
        if (!(owner instanceof ServerSubLevel subLevel)) {
            return new Quaterniond(worldOri);
        }
        return new Quaterniond(subLevel.logicalPose().orientation()).conjugate().mul(worldOri, new Quaterniond());
    }

    private static Pose3d pose(Vector3d position, Quaterniond orientation) {
        Pose3d pose = new Pose3d();
        pose.position().set(position);
        pose.orientation().set(orientation);
        pose.scale().set(1.0D, 1.0D, 1.0D);
        return pose;
    }
}
