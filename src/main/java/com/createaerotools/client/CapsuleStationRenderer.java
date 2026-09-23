package com.createaerotools.client;

import com.createaerotools.block.CapsuleStationBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/** 收纳底座 BER：轴向轴旋转渲染。 */
public class CapsuleStationRenderer extends KineticBlockEntityRenderer<CapsuleStationBlockEntity> {
    public CapsuleStationRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected SuperByteBuffer getRotatedModel(CapsuleStationBlockEntity be, BlockState state) {
        return CachedBuffers.block(KINETIC_BLOCK, shaft(getRotationAxisOf(be)));
    }
}
