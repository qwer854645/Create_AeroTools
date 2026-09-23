package com.createaerotools.item;

import com.createaerotools.CreateAeroTools;
import com.createaerotools.config.CATConfig;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 物理工具枪物品：右键抓取/放下，左键冻结（由 {@link com.createaerotools.CreateAeroTools} 转发到客户端）。
 * 有背罐时耗压缩空气，否则耗耐久。
 */
public class PhysicsToolGunItem extends Item {
    public PhysicsToolGunItem(Properties properties) {
        super(properties.stacksTo(1).durability(400));
    }

    public static boolean isHolding(Player player) {
        return held(player) != null;
    }

    @Nullable
    public static ItemStack held(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof PhysicsToolGunItem) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        return off.getItem() instanceof PhysicsToolGunItem ? off : null;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide) {
            CreateAeroTools.TOOL_GUN_USE.run();
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            HitResult hit = player.pick(player.blockInteractionRange(), 1.0F, false);
            if (hit.getType() != HitResult.Type.BLOCK) {
                CreateAeroTools.TOOL_GUN_USE.run();
            }
            return InteractionResultHolder.success(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return BacktankUtil.isBarVisible(stack, CATConfig.SERVER.toolGunAirUses.get()) || super.isBarVisible(stack);
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        if (BacktankUtil.isBarVisible(stack, CATConfig.SERVER.toolGunAirUses.get())) {
            return BacktankUtil.getBarWidth(stack, CATConfig.SERVER.toolGunAirUses.get());
        }
        return super.getBarWidth(stack);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        if (BacktankUtil.isBarVisible(stack, CATConfig.SERVER.toolGunAirUses.get())) {
            return BacktankUtil.getBarColor(stack, CATConfig.SERVER.toolGunAirUses.get());
        }
        return super.getBarColor(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("createaerotools.tooltip.tool_gun_use").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("createaerotools.tooltip.tool_gun_air").withStyle(ChatFormatting.DARK_GRAY));
    }
}
