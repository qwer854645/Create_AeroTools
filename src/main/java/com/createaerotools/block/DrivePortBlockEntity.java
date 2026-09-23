package com.createaerotools.block;

import com.createaerotools.CreateAeroTools;
import com.createaerotools.util.WorldSpace;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.createmod.catnip.lang.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 万向传动端口方块实体。
 * <p>
 * 安山端接入 Create 应力网络；黄铜端接万向轴。
 * 连接成功后两端通过 Create 原生传播（{@link #addPropagationLocations} /
 * {@link #propagateRotationTo}）合并为同一网络，不是自定义应力桥。
 */
public class DrivePortBlockEntity extends KineticBlockEntity {
    private UUID portId = UUID.randomUUID();
    @Nullable
    private UUID partnerId;
    /** 主端负责物理同步与拉断检测。 */
    private boolean master;
    private double restLength;
    private DriveShaftKind shaftKind = DriveShaftKind.BRASS;
    private LinkStatus status = LinkStatus.DISCONNECTED;

    public DrivePortBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public UUID portId() {
        return portId;
    }

    @Nullable
    public UUID partnerId() {
        return partnerId;
    }

    public boolean isMaster() {
        return master && isLinked();
    }

    public boolean isLinked() {
        return partnerId != null;
    }

    public double restLength() {
        return restLength;
    }

    public DriveShaftKind shaftKind() {
        return shaftKind;
    }

    /**
     * 拉断长度。普通端口按轴类型规则；气动端口会覆盖，避免红石/滚轮行程自断。
     */
    public double snapLength() {
        return shaftKind.snapLength(restLength);
    }

    /** 是否主动驱动轴长（气动端口为 true）。 */
    public boolean controlsLength() {
        return false;
    }

    /**
     * 气动：仅当玩家/红石把目标长度拉离链接时的基准长度后才为 true，
     * 防止一连上就猛推结构。
     */
    public boolean wantsLengthActuation() {
        return false;
    }

    /** 物理弹簧 / 电机应维持的目标长度。 */
    public double desiredLength() {
        return restLength;
    }

    /** 是否应向 {@link #desiredLength()} 回弹（黄铜轴）。 */
    public boolean springsToTarget() {
        return shaftKind.springsToRestLength();
    }

    public LinkStatus status() {
        return status;
    }

    public Direction facing() {
        return getBlockState().getValue(DrivePortBlock.FACING);
    }

    public ItemInteractionResult handleShaft(Player player, ItemStack stack) {
        return DriveLinkTracker.handleShaft(this, player, stack);
    }

    public void bind(DrivePortBlockEntity partner, double length, boolean isMaster, DriveShaftKind kind) {
        this.partnerId = partner.portId;
        this.master = isMaster;
        this.restLength = length;
        this.shaftKind = kind;
        this.status = LinkStatus.CONNECTED;
        notifyUpdate();
    }

    public void clearLink() {
        boolean wasLive = status == LinkStatus.CONNECTED;
        partnerId = null;
        master = false;
        restLength = 0.0D;
        shaftKind = DriveShaftKind.BRASS;
        status = LinkStatus.DISCONNECTED;
        notifyUpdate();
        if (wasLive) {
            rebuildKineticLinks();
        }
    }

    void setStatus(LinkStatus status) {
        if (this.status == status) {
            return;
        }
        LinkStatus previous = this.status;
        this.status = status;
        sendData();
        if (previous == LinkStatus.CONNECTED || status == LinkStatus.CONNECTED) {
            rebuildKineticLinks();
        }
    }

    /**
     * 重新跑 Create 邻居发现，使轴连接作为真实动能边出现/消失。
     */
    public void rebuildKineticLinks() {
        if (level == null || level.isClientSide) {
            return;
        }
        detachKinetics();
        removeSource();
        attachKinetics();
    }

    private boolean carriesLiveShaft(DrivePortBlockEntity other) {
        return isLinked()
                && status == LinkStatus.CONNECTED
                && other != null
                && !other.isRemoved()
                && partnerId != null
                && partnerId.equals(other.portId())
                && other.partnerId() != null
                && other.partnerId().equals(portId)
                && other.status == LinkStatus.CONNECTED;
    }

    /**
     * 沿安山端动能轴的有符号 1:1 传动比。两端世界轴向相反则取反。
     */
    private float kineticRatio(DrivePortBlockEntity other) {
        Vec3 axisA = WorldSpace.worldNormal(this, facing().getOpposite());
        Vec3 axisB = WorldSpace.worldNormal(other, other.facing().getOpposite());
        return axisA.dot(axisB) < 0.0D ? -1.0F : 1.0F;
    }

    @Override
    public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
        super.addPropagationLocations(block, state, neighbours);
        if (status != LinkStatus.CONNECTED || !isLinked()) {
            return neighbours;
        }
        DrivePortBlockEntity partner = DriveLinkTracker.partner(this);
        if (partner != null && !partner.isRemoved() && partner.getLevel() == level) {
            neighbours.add(partner.getBlockPos());
        }
        return neighbours;
    }

    @Override
    public float propagateRotationTo(KineticBlockEntity target, BlockState stateFrom, BlockState stateTo,
                                     BlockPos diff, boolean connectedViaAxes, boolean connectedViaCogs) {
        if (target instanceof DrivePortBlockEntity other && carriesLiveShaft(other)) {
            return kineticRatio(other);
        }
        return super.propagateRotationTo(target, stateFrom, stateTo, diff, connectedViaAxes, connectedViaCogs);
    }

    @Override
    public boolean isCustomConnection(KineticBlockEntity other, BlockState state, BlockState otherState) {
        return other instanceof DrivePortBlockEntity port && carriesLiveShaft(port);
    }

    @Override
    public void tick() {
        super.tick();
        if (level != null && !level.isClientSide) {
            DriveLinkTracker.tick(this);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        DriveLinkTracker.register(this);
    }

    @Override
    public void remove() {
        if (level != null && !level.isClientSide) {
            DriveLinkTracker.onBlockGone(this);
        } else {
            DriveLinkTracker.unregister(this);
        }
        super.remove();
    }

    @Override
    public void onChunkUnloaded() {
        DriveLinkTracker.unregister(this);
        super.onChunkUnloaded();
    }

    @Override
    public AABB getRenderBoundingBox() {
        DrivePortBlockEntity partner = DriveLinkTracker.partner(this);
        if (partner == null || level == null) {
            return super.getRenderBoundingBox();
        }
        return new AABB(worldPosition).minmax(new AABB(partner.getBlockPos())).inflate(2.0D);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putUUID("PortId", portId);
        if (partnerId != null) {
            tag.putUUID("PartnerId", partnerId);
            tag.putBoolean("Master", master);
            tag.putDouble("RestLength", restLength);
            tag.putString("ShaftKind", shaftKind.name());
        }
        tag.putString("LinkStatus", status.name());
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        UUID previous = portId;
        if (tag.hasUUID("PortId")) {
            portId = tag.getUUID("PortId");
        }
        if (level != null && !previous.equals(portId)) {
            DriveLinkTracker.reregister(this, previous);
        }
        if (tag.hasUUID("PartnerId")) {
            partnerId = tag.getUUID("PartnerId");
            master = tag.getBoolean("Master");
            restLength = tag.getDouble("RestLength");
            try {
                shaftKind = DriveShaftKind.valueOf(tag.getString("ShaftKind"));
            } catch (IllegalArgumentException ignored) {
                shaftKind = DriveShaftKind.BRASS;
            }
        } else {
            partnerId = null;
            master = false;
            restLength = 0.0D;
            shaftKind = DriveShaftKind.BRASS;
        }
        try {
            status = LinkStatus.valueOf(tag.getString("LinkStatus"));
        } catch (IllegalArgumentException ignored) {
            status = isLinked() ? LinkStatus.WAITING : LinkStatus.DISCONNECTED;
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        Lang.builder(CreateAeroTools.MOD_ID)
                .translate("goggle.drive_header")
                .forGoggles(tooltip);

        ChatFormatting statusColor = switch (status) {
            case CONNECTED -> ChatFormatting.GREEN;
            case WAITING, CONFLICT -> ChatFormatting.YELLOW;
            case DISCONNECTED -> ChatFormatting.DARK_GRAY;
        };
        Lang.builder(CreateAeroTools.MOD_ID)
                .translate("goggle.drive_status",
                        Component.translatable(status.key()).withStyle(statusColor))
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        if (!isLinked()) {
            return true;
        }

        Lang.builder(CreateAeroTools.MOD_ID)
                .translate("goggle.drive_kind",
                        Component.translatable(shaftKind == DriveShaftKind.BRASS
                                        ? "createaerotools.goggle.drive_kind_brass"
                                        : "createaerotools.goggle.drive_kind_andesite")
                                .withStyle(ChatFormatting.GOLD))
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        DrivePortBlockEntity partner = DriveLinkTracker.partner(this);
        double length = partner == null
                ? restLength
                : WorldSpace.worldLinkAnchor(this, facing())
                        .distanceTo(WorldSpace.worldLinkAnchor(partner, partner.facing()));
        double snapAt = snapLength();

        Lang.builder(CreateAeroTools.MOD_ID)
                .translate("goggle.drive_length", String.format("%.1f", length))
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        if (shaftKind == DriveShaftKind.BRASS) {
            Lang.builder(CreateAeroTools.MOD_ID)
                    .translate("goggle.drive_rest", String.format("%.1f", restLength))
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);
        }

        Lang.builder(CreateAeroTools.MOD_ID)
                .translate("goggle.drive_snap", String.format("%.1f", snapAt))
                .style(ChatFormatting.DARK_GRAY)
                .forGoggles(tooltip);

        if (Double.isFinite(snapAt) && snapAt > 0.05D) {
            double stretchPct = length / snapAt * 100.0D;
            ChatFormatting stretchColor = stretchPct >= 90.0D
                    ? ChatFormatting.RED
                    : stretchPct >= 70.0D ? ChatFormatting.YELLOW : ChatFormatting.GRAY;
            Lang.builder(CreateAeroTools.MOD_ID)
                    .translate("goggle.drive_stretch_pct", String.format("%.0f", stretchPct))
                    .style(stretchColor)
                    .forGoggles(tooltip);
        }

        if (Math.abs(getSpeed()) > 0.05F) {
            Lang.builder(CreateAeroTools.MOD_ID)
                    .translate("goggle.drive_speed", String.format("%.1f", getSpeed()))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip);
        }
        return true;
    }

    public enum LinkStatus {
        /** 未连接 */
        DISCONNECTED("createaerotools.goggle.drive_disconnected"),
        /** 已记录对端，但对端未就绪（组装中等） */
        WAITING("createaerotools.goggle.drive_waiting"),
        /** 正常传递应力 */
        CONNECTED("createaerotools.goggle.drive_connected"),
        /** 转速冲突 */
        CONFLICT("createaerotools.goggle.drive_conflict");

        private final String key;

        LinkStatus(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}
