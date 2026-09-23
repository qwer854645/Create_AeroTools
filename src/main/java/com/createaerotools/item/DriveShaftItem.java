package com.createaerotools.item;

import com.createaerotools.block.DriveLinkTracker;
import com.createaerotools.block.DrivePortBlockEntity;
import com.createaerotools.block.DriveShaftKind;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/** 万向轴物品；右键端口走 {@link DriveLinkTracker#handleShaft}。 */
public class DriveShaftItem extends Item {
    private final DriveShaftKind kind;

    public DriveShaftItem(Properties properties, DriveShaftKind kind) {
        super(properties.stacksTo(16));
        this.kind = kind;
    }

    public DriveShaftKind kind() {
        return kind;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof DrivePortBlockEntity be)) {
            return InteractionResult.PASS;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        ItemStack stack = context.getItemInHand();
        if (context.getLevel().isClientSide) {
            // Optimistic first-click so the link ghost appears without waiting for a sync tick.
            if (!player.isShiftKeyDown() && !be.isLinked()
                    && DriveLinkTracker.selectedPort(stack, context.getLevel()) == null) {
                DriveLinkTracker.storeSelection(stack, be);
            }
            return InteractionResult.SUCCESS;
        }
        return be.handleShaft(player, stack).result();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String key = kind == DriveShaftKind.BRASS
                ? "createaerotools.tooltip.drive_shaft_use"
                : "createaerotools.tooltip.andesite_drive_shaft_use";
        tooltip.add(Component.translatable(key).withStyle(ChatFormatting.GRAY));
    }
}
