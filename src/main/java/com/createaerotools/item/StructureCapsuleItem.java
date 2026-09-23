package com.createaerotools.item;

import com.createaerotools.config.CATConfig;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 基础结构收纳器：收纳 / 释放单个物理结构。
 * 有外部万向轴时会先回收轴物品；有连到其他结构的 Sable 关节时需先断开。
 */
public class StructureCapsuleItem extends Item {
    private static final String TAG_STRUCTURE = "StoredStructure";

    public StructureCapsuleItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (hasStructure(stack)) {
            return release(context.getLevel(), player, stack, CapsulePayloads.releaseAgainst(context, storedPayload(stack)))
                    ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }
        return store(context.getLevel(), player, stack, context.getClickedPos())
                ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!hasStructure(stack)) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("createaerotools.message.capsule_empty")
                        .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResultHolder.fail(stack);
        }
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        Vec3 at = CapsulePayloads.airReleasePoint(player);
        if (at == null) {
            return InteractionResultHolder.pass(stack);
        }
        return release(level, player, stack, at)
                ? InteractionResultHolder.success(stack)
                : InteractionResultHolder.fail(stack);
    }

    @Override
    public Component getName(ItemStack stack) {
        if (hasStructure(stack)) {
            return Component.translatable("item.createaerotools.structure_capsule.filled",
                    CapsulePayloads.storedName(stack));
        }
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (hasStructure(stack)) {
            tooltip.add(Component.translatable("createaerotools.tooltip.capsule_filled",
                            CapsulePayloads.storedName(stack))
                    .withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.translatable("createaerotools.tooltip.capsule_release").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("createaerotools.tooltip.capsule_empty").withStyle(ChatFormatting.GRAY));
            double max = CATConfig.SERVER.capsuleMaxSize.get();
            if (max > 0.0D) {
                tooltip.add(Component.translatable("createaerotools.tooltip.capsule_max_size", formatSize(max))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }

    public static boolean hasStructure(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(TAG_STRUCTURE);
    }

    public static boolean tooLarge(SubLevel subLevel) {
        double max = CATConfig.SERVER.capsuleMaxSize.get();
        return max > 0.0D && CapsulePower.sizeOf(subLevel) > max;
    }

    private static String formatSize(double size) {
        return String.format("%.0f", size);
    }

    private static boolean store(Level level, Player player, ItemStack stack, BlockPos clicked) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(serverLevel, clicked);
        if (!(access instanceof ServerSubLevel subLevel) || subLevel.isRemoved()) {
            player.displayClientMessage(Component.translatable("createaerotools.message.capsule_not_structure")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (StructureGroups.isSableJoined(subLevel)) {
            player.displayClientMessage(Component.translatable("createaerotools.message.capsule_joined")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (tooLarge(subLevel)) {
            player.displayClientMessage(Component.translatable("createaerotools.message.capsule_too_large",
                    formatSize(CapsulePower.sizeOf(subLevel)), formatSize(CATConfig.SERVER.capsuleMaxSize.get()))
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }

        StructureGroups.unlinkExternal(List.of(subLevel), player);

        SubLevelData data = SubLevelSerializer.toData(subLevel, List.of());
        CompoundTag payload = data.fullTag().copy();
        CapsulePayloads.stripForBasicCapsule(payload);

        String name = subLevel.getName();
        CompoundTag itemTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        itemTag.put(TAG_STRUCTURE, payload);
        if (name != null && !name.isBlank()) {
            itemTag.putString(CapsulePayloads.TAG_NAME, name);
        } else {
            itemTag.remove(CapsulePayloads.TAG_NAME);
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(itemTag));

        StructureGroups.beginRemoval(List.of(subLevel));
        try {
            subLevel.getPlot().kickAllEntities();
            subLevel.deleteAllEntities();
            ServerSubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
            container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
        } finally {
            StructureGroups.endRemoval();
        }

        player.displayClientMessage(Component.translatable("createaerotools.message.capsule_stored",
                CapsulePayloads.storedName(stack)), true);
        serverLevel.playSound(null, player.blockPosition(), SoundEvents.ENDER_CHEST_CLOSE, SoundSource.PLAYERS, 0.6F, 1.2F);
        return true;
    }

    private static boolean release(Level level, Player player, ItemStack stack, Vec3 at) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        CompoundTag itemTag = data.copyTag();
        if (!itemTag.contains(TAG_STRUCTURE)) {
            return false;
        }

        CompoundTag payload = itemTag.getCompound(TAG_STRUCTURE).copy();
        payload.putUUID("uuid", UUID.randomUUID());
        CapsulePayloads.stripForBasicCapsule(payload);
        CapsulePayloads.translateTo(payload, at);

        ServerSubLevel loaded = SubLevelSerializer.fullyLoad(serverLevel, SubLevelSerializer.fromData(payload));
        if (loaded == null || loaded.isRemoved()) {
            player.displayClientMessage(Component.translatable("createaerotools.message.capsule_release_fail")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        loaded.latestLinearVelocity.set(0.0D, 0.0D, 0.0D);
        loaded.latestAngularVelocity.set(0.0D, 0.0D, 0.0D);
        loaded.logicalPose().position().set(at.x, at.y, at.z);
        loaded.updateLastPose();

        itemTag.remove(TAG_STRUCTURE);
        itemTag.remove(CapsulePayloads.TAG_NAME);
        if (itemTag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(itemTag));
        }

        player.displayClientMessage(Component.translatable("createaerotools.message.capsule_released"), true);
        serverLevel.playSound(null, BlockPos.containing(at), SoundEvents.ENDER_CHEST_OPEN, SoundSource.PLAYERS, 0.6F, 1.1F);
        return true;
    }

    @Nullable
    private static CompoundTag storedPayload(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.contains(TAG_STRUCTURE) ? tag.getCompound(TAG_STRUCTURE) : null;
    }
}
