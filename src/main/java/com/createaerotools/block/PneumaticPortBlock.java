package com.createaerotools.block;

import com.createaerotools.index.CATBlockEntities;
import com.createaerotools.index.CATItems;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** 气动端口方块；逻辑见 {@link PneumaticPortBlockEntity}。 */
public class PneumaticPortBlock extends DrivePortBlock {
    public PneumaticPortBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!CATItems.DRIVE_SHAFT.isIn(stack) && !CATItems.ANDESITE_DRIVE_SHAFT.isIn(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        return onBlockEntityUseItemOn(level, pos, be -> be.handleShaft(player, stack));
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
                                boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof PneumaticPortBlockEntity be) {
            be.onNeighborChanged();
        }
    }

    @Override
    public Class<DrivePortBlockEntity> getBlockEntityClass() {
        return DrivePortBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends DrivePortBlockEntity> getBlockEntityType() {
        return CATBlockEntities.PNEUMATIC_PORT.get();
    }
}
