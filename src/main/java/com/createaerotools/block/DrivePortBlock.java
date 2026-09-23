package com.createaerotools.block;

import com.createaerotools.index.CATBlockEntities;
import com.createaerotools.index.CATItems;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 安山端接 Create 轴、黄铜端接万向轴的传动端口方块。
 * FACING 指向黄铜（链接）端。
 */
public class DrivePortBlock extends DirectionalKineticBlock implements IBE<DrivePortBlockEntity> {
    // 安山（动能）端与方块面齐平；黄铜（链接）端是较短的尖端
    private static final VoxelShape SHAPE_UP = Block.box(3.0D, 0.0D, 3.0D, 13.0D, 10.0D, 13.0D);
    private static final VoxelShape SHAPE_DOWN = Block.box(3.0D, 6.0D, 3.0D, 13.0D, 16.0D, 13.0D);
    private static final VoxelShape SHAPE_NORTH = Block.box(3.0D, 3.0D, 6.0D, 13.0D, 13.0D, 16.0D);
    private static final VoxelShape SHAPE_SOUTH = Block.box(3.0D, 3.0D, 0.0D, 13.0D, 13.0D, 10.0D);
    private static final VoxelShape SHAPE_WEST = Block.box(6.0D, 3.0D, 3.0D, 16.0D, 13.0D, 13.0D);
    private static final VoxelShape SHAPE_EAST = Block.box(0.0D, 3.0D, 3.0D, 10.0D, 13.0D, 13.0D);

    public DrivePortBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction preferred = getPreferredFacing(context);
        Direction facing;
        if (preferred != null && (context.getPlayer() == null || !context.getPlayer().isShiftKeyDown())) {
            // Preferred points at an adjacent Create shaft; andesite faces that shaft.
            facing = preferred.getOpposite();
        } else if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
            facing = context.getNearestLookingDirection();
        } else {
            // Andesite against the surface that was clicked (works for shafts and normal blocks).
            facing = context.getClickedFace();
        }
        return defaultBlockState().setValue(FACING, facing);
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
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DrivePortBlockEntity be) {
            DriveLinkTracker.breakPermanently(be);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void wasExploded(Level level, BlockPos pos, Explosion explosion) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DrivePortBlockEntity be) {
            DriveLinkTracker.breakPermanently(be);
        }
        super.wasExploded(level, pos, explosion);
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        if (!context.getLevel().isClientSide
                && context.getLevel().getBlockEntity(context.getClickedPos()) instanceof DrivePortBlockEntity be) {
            DriveLinkTracker.unlink(be, context.getPlayer(), true);
        }
        return super.onSneakWrenched(state, context);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case UP -> SHAPE_UP;
            case DOWN -> SHAPE_DOWN;
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            case EAST -> SHAPE_EAST;
        };
    }

    /** Full cube for attachment so levers/torches/buttons can sit on the sides. */
    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.block();
    }

    @Override
    public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, @Nullable Direction side) {
        return true;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return true;
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        // Grey andesite end takes Create kinetics; the facing / brass end takes the link shaft.
        return face == state.getValue(FACING).getOpposite();
    }

    @Override
    public Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
        return false;
    }

    @Override
    public Class<DrivePortBlockEntity> getBlockEntityClass() {
        return DrivePortBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends DrivePortBlockEntity> getBlockEntityType() {
        return CATBlockEntities.DRIVE_PORT.get();
    }
}
