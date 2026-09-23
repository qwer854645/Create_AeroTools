package com.createaerotools.block;

import com.createaerotools.CreateAeroTools;
import com.createaerotools.config.CATConfig;
import com.createaerotools.util.WorldSpace;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.createmod.catnip.lang.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 铜质气动端口。
 * <p>
 * 黄铜轴：红石映射目标长度（侧面可调比例）；安山轴：侧面长度滚轮（类似创造马达）。
 * 长度由限力滑移关节弹簧追目标，不做世界空间冲量。
 * <p>
 * 只注册一个 {@link ScrollValueBehaviour}：Create 按类型存 behaviour，第二个会盖掉第一个。
 */
public class PneumaticPortBlockEntity extends DrivePortBlockEntity {
    private static final double MIN_LENGTH = 0.5D;
    private static final int DEFAULT_SIGNAL_SCALE = 100;
    private static final int MIN_SIGNAL_SCALE = 5;
    private static final int MAX_SIGNAL_SCALE = 200;
    /** 误差超过此值进入动作；低于释放阈值才退出（滞回）。 */
    private static final double ACTUATION_ARM_EPS = 0.2D;
    private static final double ACTUATION_RELEASE_EPS = 0.06D;

    private double targetLength;
    private int signalScalePercent = DEFAULT_SIGNAL_SCALE;
    private ScrollValueBehaviour sideScroll;
    private boolean brassScaleMode;
    private boolean updatingScroll;
    private int lastRedstone = -1;
    private boolean actuationLatched;

    public PneumaticPortBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        sideScroll = new ScrollValueBehaviour(
                Component.translatable("createaerotools.pneumatic.length"),
                this,
                new CenteredSideValueBoxTransform((state, face) ->
                        face.getAxis() != state.getValue(DrivePortBlock.FACING).getAxis()));
        sideScroll.between(tenths(MIN_LENGTH), tenths(maxScrollLength()));
        sideScroll.withFormatter(value -> String.format("%.1f", value / 10.0D));
        sideScroll.withCallback(this::onSideScroll);
        sideScroll.onlyActiveWhen(this::showSideScroll);
        sideScroll.setValue(tenths(MIN_LENGTH));
        behaviours.add(sideScroll);
    }

    @Override
    public boolean controlsLength() {
        return true;
    }

    /**
     * Snap relative to max travel, not linked rest length — otherwise redstone/scroll
     * extension past ~1.5× rest would break the shaft mid-stroke.
     */
    @Override
    public double snapLength() {
        double max = maxLengthForKind();
        if (max <= 0.0D) {
            return Double.POSITIVE_INFINITY;
        }
        return max * CATConfig.SERVER.pneumaticBreakPercent.get() / 100.0D;
    }

    @Override
    public boolean wantsLengthActuation() {
        if (!isLinked()) {
            actuationLatched = false;
            return false;
        }
        double current = liveLength();
        if (current < MIN_LENGTH) {
            actuationLatched = false;
            return false;
        }
        double error = Math.abs(desiredLength() - current);
        if (actuationLatched) {
            if (error < ACTUATION_RELEASE_EPS) {
                actuationLatched = false;
            }
            return actuationLatched;
        }
        if (error > ACTUATION_ARM_EPS) {
            actuationLatched = true;
            return true;
        }
        return false;
    }

    private double liveLength() {
        DrivePortBlockEntity partner = DriveLinkTracker.partner(this);
        if (partner == null) {
            return restLength();
        }
        return WorldSpace.worldLinkAnchor(this, facing())
                .distanceTo(WorldSpace.worldLinkAnchor(partner, partner.facing()));
    }

    @Override
    public double desiredLength() {
        if (targetLength >= MIN_LENGTH) {
            return targetLength;
        }
        return restLength() >= MIN_LENGTH ? restLength() : MIN_LENGTH;
    }

    @Override
    public boolean springsToTarget() {
        // Idle linked pneumatic shafts must not spring/impulse — that launches on link.
        return wantsLengthActuation();
    }

    @Override
    public void bind(DrivePortBlockEntity partner, double length, boolean isMaster, DriveShaftKind kind) {
        super.bind(partner, length, isMaster, kind);
        // Keep the linked length as the target. Do not snap to redstone on the next tick
        // (power 0 would become 0.5 blocks and yank the crafts apart/together).
        setTargetLength(length, true);
        lastRedstone = currentRedstonePower();
        refreshSideScrollMode();
        if (kind == DriveShaftKind.BRASS) {
            syncSignalScaleToPartner();
        }
    }

    @Override
    public void clearLink() {
        super.clearLink();
        targetLength = 0.0D;
        lastRedstone = -1;
        actuationLatched = false;
        signalScalePercent = DEFAULT_SIGNAL_SCALE;
        refreshSideScrollMode();
    }

    @Override
    public void tick() {
        super.tick();
        Level level = getLevel();
        if (level == null || level.isClientSide || !isLinked() || !isMaster()) {
            return;
        }
        if (shaftKind() == DriveShaftKind.BRASS) {
            tickRedstoneLength();
        }
    }

    private void tickRedstoneLength() {
        int power = currentRedstonePower();
        if (lastRedstone < 0) {
            lastRedstone = power;
            return;
        }
        if (power == lastRedstone) {
            return;
        }
        lastRedstone = power;
        applyRedstoneTarget(power, true);
    }

    private void applyRedstoneTarget(int power, boolean syncPartner) {
        double max = maxLengthForKind();
        double span = Math.max(0.0D, max - MIN_LENGTH);
        double fraction = (power / 15.0D) * (signalScalePercent / 100.0D);
        double target = MIN_LENGTH + span * fraction;
        setTargetLength(target, syncPartner);
    }

    private int currentRedstonePower() {
        Level level = getLevel();
        if (level == null) {
            return 0;
        }
        int local = level.getBestNeighborSignal(worldPosition);
        int partner = partnerLocalPower();
        if (partner < 0) {
            return local;
        }
        return CATConfig.SERVER.pneumaticRedstoneCombine.get() == CATConfig.PneumaticRedstoneCombine.LOWEST
                ? Math.min(local, partner)
                : Math.max(local, partner);
    }

    /** Partner's own neighbor signal, or -1 if unavailable. */
    private int partnerLocalPower() {
        DrivePortBlockEntity partner = DriveLinkTracker.partner(this);
        if (partner == null || partner.getLevel() == null) {
            return -1;
        }
        return partner.getLevel().getBestNeighborSignal(partner.getBlockPos());
    }

    void onNeighborChanged() {
        // Tick compares live power to lastRedstone; no forced retarget.
    }

    private boolean showSideScroll() {
        return isLinked() && (shaftKind() == DriveShaftKind.ANDESITE || shaftKind() == DriveShaftKind.BRASS);
    }

    private void onSideScroll(int value) {
        if (updatingScroll || getLevel() == null || getLevel().isClientSide || !showSideScroll()) {
            return;
        }
        if (brassScaleMode) {
            setSignalScale(value, true);
        } else {
            setTargetLength(value / 10.0D, true);
        }
    }

    /** Andesite → length scroll; brass → redstone scale scroll. */
    private void refreshSideScrollMode() {
        if (sideScroll == null) {
            return;
        }
        boolean brass = isLinked() && shaftKind() == DriveShaftKind.BRASS;
        brassScaleMode = brass;
        updatingScroll = true;
        try {
            if (brass) {
                sideScroll.setLabel(Component.translatable("createaerotools.pneumatic.signal_scale"));
                sideScroll.between(MIN_SIGNAL_SCALE, MAX_SIGNAL_SCALE);
                sideScroll.withFormatter(v -> v + "%");
                sideScroll.setValue(signalScalePercent);
            } else {
                sideScroll.setLabel(Component.translatable("createaerotools.pneumatic.length"));
                sideScroll.between(tenths(MIN_LENGTH), tenths(maxScrollLength()));
                sideScroll.withFormatter(v -> String.format("%.1f", v / 10.0D));
                int lengthValue = tenths(targetLength >= MIN_LENGTH ? targetLength : MIN_LENGTH);
                sideScroll.setValue(lengthValue);
            }
        } finally {
            updatingScroll = false;
        }
    }

    private void setSideScrollQuietly(int value) {
        if (sideScroll == null || sideScroll.getValue() == value) {
            return;
        }
        updatingScroll = true;
        try {
            sideScroll.setValue(value);
        } finally {
            updatingScroll = false;
        }
    }

    private void setSignalScale(int percent, boolean syncPartner) {
        int clamped = Mth.clamp(percent, MIN_SIGNAL_SCALE, MAX_SIGNAL_SCALE);
        boolean changed = signalScalePercent != clamped;
        signalScalePercent = clamped;
        if (brassScaleMode) {
            setSideScrollQuietly(clamped);
        }
        if (syncPartner) {
            syncSignalScaleToPartner();
        }
        if (changed) {
            notifyUpdate();
            // Always remap from the master so either end's scroll takes effect.
            if (isLinked() && shaftKind() == DriveShaftKind.BRASS) {
                PneumaticPortBlockEntity controller = controllingPneumatic();
                if (controller != null) {
                    int power = controller.currentRedstonePower();
                    controller.lastRedstone = power;
                    controller.applyRedstoneTarget(power, true);
                }
            }
        }
    }

    @Nullable
    private PneumaticPortBlockEntity controllingPneumatic() {
        if (isMaster()) {
            return this;
        }
        DrivePortBlockEntity partner = DriveLinkTracker.partner(this);
        if (partner instanceof PneumaticPortBlockEntity other && other.isMaster()) {
            return other;
        }
        return null;
    }

    private void syncSignalScaleToPartner() {
        if (!(DriveLinkTracker.partner(this) instanceof PneumaticPortBlockEntity other)) {
            return;
        }
        if (other.signalScalePercent == signalScalePercent) {
            if (other.brassScaleMode) {
                other.setSideScrollQuietly(signalScalePercent);
            }
            return;
        }
        other.signalScalePercent = signalScalePercent;
        if (other.brassScaleMode) {
            other.setSideScrollQuietly(signalScalePercent);
        }
        other.notifyUpdate();
    }

    private void setTargetLength(double length, boolean syncPartner) {
        double max = maxLengthForKind();
        double upper = Double.isFinite(max) && max > MIN_LENGTH ? max : 1024.0D;
        double clamped = Mth.clamp(length, MIN_LENGTH, upper);
        int scrollValue = tenths(clamped);
        if (Math.abs(targetLength - clamped) < 0.001D
                && (!brassScaleMode && sideScroll != null && sideScroll.getValue() == scrollValue)) {
            return;
        }
        targetLength = clamped;
        if (!brassScaleMode) {
            setSideScrollQuietly(scrollValue);
        }
        notifyUpdate();
        if (syncPartner && DriveLinkTracker.partner(this) instanceof PneumaticPortBlockEntity other) {
            other.targetLength = clamped;
            if (!other.brassScaleMode) {
                other.setSideScrollQuietly(scrollValue);
            }
            other.notifyUpdate();
        }
    }

    private double maxLengthForKind() {
        double max = shaftKind().pneumaticMaxLength();
        if (max <= 0.0D) {
            return Double.POSITIVE_INFINITY;
        }
        return max;
    }

    private double maxScrollLength() {
        return Math.max(MIN_LENGTH, maxLengthForKind() == Double.POSITIVE_INFINITY
                ? 1024.0D
                : maxLengthForKind());
    }

    private static int tenths(double length) {
        return Mth.clamp((int) Math.round(length * 10.0D), 1, 10240);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("SignalScale", signalScalePercent);
        if (isLinked()) {
            tag.putDouble("TargetLength", targetLength);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains("SignalScale")) {
            signalScalePercent = Mth.clamp(tag.getInt("SignalScale"), MIN_SIGNAL_SCALE, MAX_SIGNAL_SCALE);
        } else {
            signalScalePercent = DEFAULT_SIGNAL_SCALE;
        }
        if (tag.contains("TargetLength")) {
            targetLength = tag.getDouble("TargetLength");
        } else if (isLinked()) {
            targetLength = restLength();
        } else {
            targetLength = 0.0D;
        }
        refreshSideScrollMode();
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        if (!isLinked()) {
            return true;
        }
        Lang.builder(CreateAeroTools.MOD_ID)
                .translate("goggle.pneumatic_header")
                .forGoggles(tooltip);
        Lang.builder(CreateAeroTools.MOD_ID)
                .translate("goggle.pneumatic_target", String.format("%.1f", desiredLength()))
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip);
        DrivePortBlockEntity partner = DriveLinkTracker.partner(this);
        if (partner != null) {
            double length = WorldSpace.worldLinkAnchor(this, facing())
                    .distanceTo(WorldSpace.worldLinkAnchor(partner, partner.facing()));
            Lang.builder(CreateAeroTools.MOD_ID)
                    .translate("goggle.pneumatic_current", String.format("%.1f", length))
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);
        }
        if (shaftKind() == DriveShaftKind.BRASS) {
            Lang.builder(CreateAeroTools.MOD_ID)
                    .translate("goggle.pneumatic_control_redstone")
                    .style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip);
            Lang.builder(CreateAeroTools.MOD_ID)
                    .translate("goggle.pneumatic_signal_scale", signalScalePercent)
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);
            int power = currentRedstonePower();
            Lang.builder(CreateAeroTools.MOD_ID)
                    .translate("goggle.pneumatic_signal", power)
                    .style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip);
            boolean highest = CATConfig.SERVER.pneumaticRedstoneCombine.get()
                    == CATConfig.PneumaticRedstoneCombine.HIGHEST;
            Lang.builder(CreateAeroTools.MOD_ID)
                    .translate(highest
                            ? "goggle.pneumatic_signal_highest"
                            : "goggle.pneumatic_signal_lowest")
                    .style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip);
        } else {
            Lang.builder(CreateAeroTools.MOD_ID)
                    .translate("goggle.pneumatic_control_scroll")
                    .style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip);
        }
        if (wantsLengthActuation()) {
            Lang.builder(CreateAeroTools.MOD_ID)
                    .translate("goggle.pneumatic_actuating")
                    .style(ChatFormatting.YELLOW)
                    .forGoggles(tooltip);
        }
        return true;
    }
}
