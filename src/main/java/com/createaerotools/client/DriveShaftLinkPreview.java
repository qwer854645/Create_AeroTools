package com.createaerotools.client;

import com.createaerotools.block.DriveLinkTracker;
import com.createaerotools.block.DrivePortBlockEntity;
import com.createaerotools.block.DriveShaftKind;
import com.createaerotools.item.DriveShaftItem;
import com.createaerotools.util.WorldSpace;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 万向轴已选中第一端后，向瞄准端口 / 视线点绘制半透明轴预览（与真实链接同模型）。
 * 染色表示是否可连接。
 */
public final class DriveShaftLinkPreview {
    /** 柔和染色：保留轴纹理可读性，只提示合法 / 非法 / 瞄准中。 */
    private static final int TINT_OK = 0xA0FFE0C0;
    private static final int TINT_BAD = 0xA0FF8080;
    private static final int TINT_AIM = 0x90FFE8A0;
    private static final Vector3f MODEL_UP = new Vector3f(0.0F, 1.0F, 0.0F);

    private DriveShaftLinkPreview() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(DriveShaftLinkPreview::onRender);
    }

    private static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || player.isShiftKeyDown()) {
            return;
        }
        ItemStack stack = heldShaft(player);
        if (stack.isEmpty()) {
            return;
        }
        DrivePortBlockEntity first = DriveLinkTracker.selectedPort(stack, level);
        if (first == null || first.isRemoved() || WorldSpace.parentLevel(first) != level) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 from = WorldSpace.worldLinkAnchor(first, first.facing(), partialTick);
        DrivePortBlockEntity aimed = aimedPort(mc, level, first);
        DriveShaftKind kind = DriveShaftKind.from(stack);
        Vec3 to;
        int tint;
        if (aimed != null) {
            to = WorldSpace.worldLinkAnchor(aimed, aimed.facing(), partialTick);
            double length = from.distanceTo(to);
            String reject = DriveLinkTracker.validateLink(first, aimed, length, kind);
            tint = reject == null ? TINT_OK : TINT_BAD;
        } else {
            to = aimPoint(player, first, from, kind);
            double length = from.distanceTo(to);
            tint = length < 0.35D || !lengthWithinMax(length, kind) ? TINT_BAD : TINT_AIM;
        }

        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 0.05D) {
            return;
        }
        Vec3 mid = from.add(to).scale(0.5D);
        Vec3 dir = delta.scale(1.0D / length);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();

        BlockState shaftState = AllBlocks.SHAFT.getDefaultState();
        if (shaftState.hasProperty(BlockStateProperties.AXIS)) {
            shaftState = shaftState.setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
        }

        int a = (tint >>> 24) & 0xFF;
        int r = (tint >>> 16) & 0xFF;
        int g = (tint >>> 8) & 0xFF;
        int b = tint & 0xFF;

        VertexConsumer consumer = mc.renderBuffers().bufferSource().getBuffer(RenderType.translucent());
        poseStack.pushPose();
        poseStack.translate(mid.x - camera.x, mid.y - camera.y, mid.z - camera.z);
        poseStack.mulPose(new Quaternionf().rotationTo(MODEL_UP, new Vector3f((float) dir.x, (float) dir.y, (float) dir.z)));
        // Same layout as DriveShaftRenderer: stretch Create shaft along Y to span the link.
        poseStack.scale(1.0F, (float) length, 1.0F);
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        SuperByteBuffer shaft = CachedBuffers.block(shaftState);
        shaft.light(LightTexture.FULL_BRIGHT)
                .color(r, g, b, a)
                .disableDiffuse()
                .renderInto(poseStack, consumer);
        poseStack.popPose();
        // 交给帧末统一 flush，避免每帧强制 endBatch 打断半透明合批
    }

    private static ItemStack heldShaft(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof DriveShaftItem) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof DriveShaftItem) {
            return off;
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    private static DrivePortBlockEntity aimedPort(Minecraft mc, Level level, DrivePortBlockEntity first) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() == HitResult.Type.MISS) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(hit.getBlockPos());
        if (!(be instanceof DrivePortBlockEntity port) || port.isRemoved()) {
            return null;
        }
        if (port.portId().equals(first.portId())) {
            return null;
        }
        if (WorldSpace.parentLevel(port) != level) {
            return null;
        }
        return port;
    }

    private static Vec3 aimPoint(LocalPlayer player, DrivePortBlockEntity first, Vec3 from, DriveShaftKind kind) {
        double max = kind.maxLinkLength();
        if (max <= 0.0D) {
            max = 8.0D;
        }
        Vec3 lookHit = player.pick(Math.min(player.blockInteractionRange(), max + 1.0D), 0.0F, false).getLocation();
        Vec3 facing = WorldSpace.worldNormal(first, first.facing());
        Vec3 toward = lookHit.subtract(from);
        if (toward.lengthSqr() < 1.0E-6D) {
            toward = facing;
        }
        Vec3 blended = toward.normalize().scale(0.85D).add(facing.scale(0.15D)).normalize();
        double reach = Math.min(toward.length(), max);
        reach = Math.max(reach, 0.5D);
        return from.add(blended.scale(reach));
    }

    private static boolean lengthWithinMax(double length, DriveShaftKind kind) {
        double max = kind.maxLinkLength();
        return max <= 0.0D || length <= max;
    }
}
