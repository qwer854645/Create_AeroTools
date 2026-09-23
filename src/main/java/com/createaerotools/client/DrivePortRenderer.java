package com.createaerotools.client;

import com.createaerotools.block.DrivePortBlockEntity;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/** 传动端口 BER：安山端半轴旋转渲染。 */
public class DrivePortRenderer extends KineticBlockEntityRenderer<DrivePortBlockEntity> {
    public DrivePortRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected SuperByteBuffer getRotatedModel(DrivePortBlockEntity be, BlockState state) {
        return CachedBuffers.partial(AllPartialModels.SHAFT_HALF, state);
    }
}
