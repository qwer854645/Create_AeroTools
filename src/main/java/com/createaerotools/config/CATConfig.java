package com.createaerotools.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 服务端配置：收纳底座、万向轴、气动与物理工具枪参数。
 * 改动后一般需重启或重载配置才完全生效。
 */
public final class CATConfig {
    public static final ModConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    static {
        var pair = new ModConfigSpec.Builder().configure(Server::new);
        SERVER = pair.getLeft();
        SERVER_SPEC = pair.getRight();
    }

    private CATConfig() {
    }

    public static final class Server {
        public final ModConfigSpec.DoubleValue capsuleStationStress;
        public final ModConfigSpec.DoubleValue capsuleRpmPerSize;
        public final ModConfigSpec.DoubleValue capsuleMaxRpm;
        public final ModConfigSpec.DoubleValue capsuleStressPerSize;
        public final ModConfigSpec.DoubleValue capsuleMaxStress;
        public final ModConfigSpec.DoubleValue capsuleMaxSize;
        public final ModConfigSpec.DoubleValue capsuleStationRange;
        public final ModConfigSpec.DoubleValue driveMaxDistance;
        public final ModConfigSpec.DoubleValue driveMaxAngle;
        public final ModConfigSpec.DoubleValue driveBrassBreakPercent;
        public final ModConfigSpec.DoubleValue driveAndesiteMaxLength;
        public final ModConfigSpec.DoubleValue driveStiffness;
        public final ModConfigSpec.DoubleValue driveDamping;
        public final ModConfigSpec.DoubleValue driveMaxForce;
        public final ModConfigSpec.DoubleValue driveShaftRadius;
        public final ModConfigSpec.DoubleValue driveMaxStress;
        public final ModConfigSpec.DoubleValue pneumaticActuationSpeed;
        public final ModConfigSpec.DoubleValue pneumaticBreakPercent;
        public final ModConfigSpec.EnumValue<PneumaticRedstoneCombine> pneumaticRedstoneCombine;
        public final ModConfigSpec.DoubleValue toolGunRange;
        public final ModConfigSpec.IntValue toolGunAirUses;
        public final ModConfigSpec.IntValue toolGunConsumeTicks;
        public final ModConfigSpec.DoubleValue toolGunLinearStiffness;
        public final ModConfigSpec.DoubleValue toolGunLinearDamping;
        public final ModConfigSpec.DoubleValue toolGunAngularStiffness;
        public final ModConfigSpec.DoubleValue toolGunAngularDamping;

        private Server(ModConfigSpec.Builder builder) {
            builder.comment("Advanced structure capsule station.").push("capsule");
            capsuleStationStress = builder
                    .comment("Stress Units consumed per RPM by the advanced capsule station while it is spinning.")
                    .defineInRange("capsuleStationStress", 8.0D, 0.0D, 10_000.0D);
            capsuleRpmPerSize = builder
                    .comment("Required RPM per cubic block of stored structure bounds. 0 disables the RPM requirement.")
                    .defineInRange("capsuleRpmPerSize", 1.0D, 0.0D, 256.0D);
            capsuleMaxRpm = builder
                    .comment("Maximum required RPM. 0 means uncapped.")
                    .defineInRange("capsuleMaxRpm", 256.0D, 0.0D, 1024.0D);
            capsuleStressPerSize = builder
                    .comment("Required stress (SU at the station) per cubic block of stored structure bounds. Default 0.5 is 1 SU per 2 blocks. 0 disables the stress requirement.")
                    .defineInRange("capsuleStressPerSize", 0.5D, 0.0D, 10_000.0D);
            capsuleMaxStress = builder
                    .comment("Maximum required stress in SU. 0 means uncapped.")
                    .defineInRange("capsuleMaxStress", 16384.0D, 0.0D, 1_000_000.0D);
            capsuleMaxSize = builder
                    .comment("Maximum cubic blocks the basic structure capsule can store. 0 means unlimited.")
                    .defineInRange("capsuleMaxSize", 512.0D, 0.0D, 1_000_000.0D);
            capsuleStationRange = builder
                    .comment("World-space range of the advanced capsule station, in blocks. Bound capsules unbind when the player leaves this range. 0 means unlimited.")
                    .defineInRange("capsuleStationRange", 32.0D, 0.0D, 1024.0D);
            builder.pop();

            builder.comment("Universal drive ports.").push("drive");
            driveMaxDistance = builder
                    .comment("Maximum brass shaft length when linking, and hard length cap while a brass shaft is linked. 0 means unlimited. Andesite shafts use andesiteMaxLength instead.")
                    .defineInRange("maxDistance", 8.0D, 0.0D, 1024.0D);
            driveMaxAngle = builder
                    .comment("Maximum swing of the shaft around each port, in degrees.")
                    .defineInRange("maxAngle", 45.0D, 5.0D, 80.0D);
            driveBrassBreakPercent = builder
                    .comment("Brass shaft snaps when its length exceeds this percent of the linked rest length. 150 means 1.5× the linked length.")
                    .defineInRange("brassBreakPercent", 150.0D, 100.0D, 1000.0D);
            driveAndesiteMaxLength = builder
                    .comment("Maximum andesite shaft length in blocks. Used when linking and as the only snap limit while linked. 0 means unlimited.")
                    .defineInRange("andesiteMaxLength", 8.0D, 0.0D, 1024.0D);
            driveStiffness = builder
                    .comment("Spring stiffness of brass shafts pulling back to the linked rest length.")
                    .defineInRange("stiffness", 80.0D, 0.0D, 1_000_000.0D);
            driveDamping = builder
                    .comment("Damping of the rest-length spring.")
                    .defineInRange("damping", 12.0D, 0.0D, 1_000_000.0D);
            driveMaxForce = builder
                    .comment("Maximum spring force. 0 uses a built-in cap of 400 so linking cannot launch the crafts.")
                    .defineInRange("maxForce", 400.0D, 0.0D, 1_000_000.0D);
            driveShaftRadius = builder
                    .comment("Physics radius of the telescoping shaft, in blocks.")
                    .defineInRange("shaftRadius", 0.125D, 0.05D, 0.5D);
            driveMaxStress = builder
                    .comment("Maximum SU transferred through one pair. 0 means unlimited.")
                    .defineInRange("maxStressUnits", 0.0D, 0.0D, 1_000_000.0D);
            pneumaticActuationSpeed = builder
                    .comment("How fast a pneumatic port ramps its spring target toward the set length, in blocks per second. Lower is more stable.")
                    .defineInRange("pneumaticActuationSpeed", 2.0D, 0.1D, 32.0D);
            pneumaticBreakPercent = builder
                    .comment("Pneumatic shafts snap when longer than this percent of their max travel length (brass maxDistance / andesite maxLength). 200 means 2× max travel, so redstone/scroll actuation cannot self-break.")
                    .defineInRange("pneumaticBreakPercent", 200.0D, 100.0D, 1000.0D);
            pneumaticRedstoneCombine = builder
                    .comment("When both ends of a brass pneumatic shaft have redstone: HIGHEST uses the stronger signal, LOWEST uses the weaker.")
                    .defineEnum("pneumaticRedstoneCombine", PneumaticRedstoneCombine.HIGHEST);
            builder.pop();

            builder.comment("Survival physics tool gun.").push("tool_gun");
            toolGunRange = builder
                    .comment("Maximum grab range of the physics tool gun, in blocks.")
                    .defineInRange("toolGunRange", 24.0D, 4.0D, 128.0D);
            toolGunAirUses = builder
                    .comment("How many uses a full copper backtank is worth. Higher values consume less air per action.")
                    .defineInRange("toolGunAirUses", 80, 1, 10_000);
            toolGunConsumeTicks = builder
                    .comment("How often grabbing consumes durability or backtank air, in ticks.")
                    .defineInRange("toolGunConsumeTicks", 8, 1, 200);
            toolGunLinearStiffness = builder
                    .comment("Linear stiffness of the grab joint. Adapted from Simulated's physics staff defaults.")
                    .defineInRange("toolGunLinearStiffness", 2650.0D, 0.0D, 1_000_000.0D);
            toolGunLinearDamping = builder
                    .comment("Linear damping of the grab joint.")
                    .defineInRange("toolGunLinearDamping", 125.0D, 0.0D, 1_000_000.0D);
            toolGunAngularStiffness = builder
                    .comment("Angular stiffness of the grab joint.")
                    .defineInRange("toolGunAngularStiffness", 10000.0D, 0.0D, 1_000_000.0D);
            toolGunAngularDamping = builder
                    .comment("Angular damping of the grab joint.")
                    .defineInRange("toolGunAngularDamping", 850.0D, 0.0D, 1_000_000.0D);
            builder.pop();
        }
    }

    /** 黄铜气动轴两端都有红石时如何合并信号。 */
    public enum PneumaticRedstoneCombine {
        HIGHEST,
        LOWEST
    }
}
