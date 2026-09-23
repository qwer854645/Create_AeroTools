package com.createaerotools.client;

import com.createaerotools.block.DriveLinkTracker;
import com.createaerotools.block.DrivePortBlockEntity;
import com.createaerotools.util.WorldSpace;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 已连接万向轴的世界空间渲染（两端锚点之间的伸缩轴模型）。 */
public final class DriveShaftRenderer {
    private static final Vector3f MODEL_UP = new Vector3f(0.0F, 1.0F, 0.0F);

    private DriveShaftRenderer() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(DriveShaftRenderer::onRender);
    }

    private static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack poseStack = event.getPoseStack();
        VertexConsumer consumer = mc.renderBuffers().bufferSource().getBuffer(RenderType.solid());
        Vec3 camera = event.getCamera().getPosition();
        BlockState shaftState = AllBlocks.SHAFT.getDefaultState();
        if (shaftState.hasProperty(BlockStateProperties.AXIS)) {
            shaftState = shaftState.setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
        }

        for (DrivePortBlockEntity be : DriveLinkTracker.loaded()) {
            if (!be.isMaster() || be.isRemoved() || WorldSpace.parentLevel(be) != level) {
                continue;
            }
            DrivePortBlockEntity partner = DriveLinkTracker.partner(be);
            if (partner == null || partner.isRemoved()) {
                continue;
            }
            Vec3 from = WorldSpace.worldLinkAnchor(be, be.facing(), partialTick);
            Vec3 to = WorldSpace.worldLinkAnchor(partner, partner.facing(), partialTick);
            Vec3 delta = to.subtract(from);
            double length = delta.length();
            if (length < 0.05D) {
                continue;
            }
            Vec3 mid = from.add(to).scale(0.5D);
            Vec3 dir = delta.scale(1.0D / length);
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(mid));
            DrivePortBlockEntity spinning = Math.abs(be.getSpeed()) >= Math.abs(partner.getSpeed()) ? be : partner;
            float angle = KineticBlockEntityRenderer.getAngleForBe(spinning, spinning.getBlockPos(), Direction.Axis.Y);

            poseStack.pushPose();
            poseStack.translate(mid.x - camera.x, mid.y - camera.y, mid.z - camera.z);
            poseStack.mulPose(new Quaternionf().rotationTo(MODEL_UP, new Vector3f((float) dir.x, (float) dir.y, (float) dir.z)));
            poseStack.mulPose(Axis.YP.rotation(angle));
            poseStack.scale(1.0F, (float) length, 1.0F);
            poseStack.translate(-0.5F, -0.5F, -0.5F);
            SuperByteBuffer shaft = CachedBuffers.block(shaftState);
            shaft.light(light).renderInto(poseStack, consumer);
            poseStack.popPose();
        }
    }
}
