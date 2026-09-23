package com.createaerotools.item;

import com.createaerotools.block.CapsuleStationBlockEntity;
import com.createaerotools.config.CATConfig;
import com.createaerotools.util.WorldSpace;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 高级结构收纳器：在已接入应力的收纳底座范围内，
 * 收纳 / 释放由 Sable 关节或万向轴连成的一组结构。
 */
public class AdvancedStructureCapsuleItem extends Item {
    private static final String TAG_STRUCTURES = "StoredStructures";
    private static final String TAG_COUNT = "StoredCount";
    private static final String TAG_ANCHOR = "AnchorId";
    private static final String TAG_SIZE = "StoredSize";
    private static final String TAG_BOUND_POS = "BoundPos";
    private static final String TAG_BOUND_DIM = "BoundDim";

    public AdvancedStructureCapsuleItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (player == null) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        if (level.getBlockEntity(context.getClickedPos()) instanceof CapsuleStationBlockEntity) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            if (player.isShiftKeyDown() && isBound(stack)) {
                clearBound(stack);
                player.displayClientMessage(Component.translatable("createaerotools.message.capsule_unbound"), true);
                return InteractionResult.SUCCESS;
            }
            if (hasStructures(stack)) {
                return armRelease(level, player, stack, context.getClickedPos())
                        ? InteractionResult.SUCCESS : InteractionResult.FAIL;
            }
            return bind(level, player, stack, context.getClickedPos())
                    ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }
        if (hasStructures(stack)) {
            if (!isBound(stack)) {
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.translatable("createaerotools.message.adv_capsule_need_station")
                            .withStyle(ChatFormatting.RED), true);
                }
                return InteractionResult.FAIL;
            }
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            Vec3 at = releasePos(context, stack);
            return release(level, player, stack, at)
                    ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }
        if (!isBound(stack)) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("createaerotools.message.adv_capsule_bind_first")
                        .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResult.FAIL;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        return store(level, player, stack, context.getClickedPos())
                ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hasStructures(stack) && isBound(stack)) {
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
        if (!level.isClientSide) {
            String key = hasStructures(stack)
                    ? "createaerotools.message.adv_capsule_need_station"
                    : "createaerotools.message.adv_capsule_bind_first";
            player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true);
        }
        return InteractionResultHolder.fail(stack);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (level.isClientSide || !isBound(stack)) {
            return;
        }
        CapsuleStationBlockEntity station = boundStation(level, stack);
        if (station != null && inRange(station, entity.position())) {
            return;
        }
        clearBound(stack);
        if (entity instanceof Player player) {
            player.displayClientMessage(Component.translatable(station == null
                    ? "createaerotools.message.capsule_station_missing"
                    : "createaerotools.message.capsule_out_of_range")
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        if (hasStructures(stack)) {
            return Component.translatable("item.createaerotools.advanced_structure_capsule.filled",
                    CapsulePayloads.storedName(stack));
        }
        return super.getName(stack);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return hasStructures(stack) || super.isFoil(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (hasStructures(stack)) {
            tooltip.add(Component.translatable("createaerotools.tooltip.adv_capsule_filled",
                            CapsulePayloads.storedName(stack), storedCount(stack))
                    .withStyle(ChatFormatting.AQUA));
            tooltip.add(CapsulePower.requirementTooltip(storedSize(stack)).withStyle(ChatFormatting.GRAY));
            if (isBound(stack)) {
                tooltip.add(Component.translatable("createaerotools.tooltip.adv_capsule_release_ready").withStyle(ChatFormatting.AQUA));
            } else {
                tooltip.add(Component.translatable("createaerotools.tooltip.adv_capsule_release").withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        tooltip.add(Component.translatable("createaerotools.tooltip.adv_capsule_empty").withStyle(ChatFormatting.GRAY));
        boundPos(stack).ifPresent(pos -> tooltip.add(Component.translatable(
                "createaerotools.tooltip.adv_capsule_bound", pos.getX(), pos.getY(), pos.getZ()
        ).withStyle(ChatFormatting.AQUA)));
    }

    public static boolean hasStructures(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(TAG_STRUCTURES, Tag.TAG_LIST);
    }

    private static boolean bind(Level level, Player player, ItemStack stack, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof CapsuleStationBlockEntity)) {
            return false;
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.put(TAG_BOUND_POS, NbtUtils.writeBlockPos(pos));
        tag.putString(TAG_BOUND_DIM, level.dimension().location().toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        player.displayClientMessage(Component.translatable("createaerotools.message.capsule_bound"), true);
        level.playSound(null, pos, SoundEvents.WOODEN_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.5F, 1.2F);
        return true;
    }

    private static boolean armRelease(Level level, Player player, ItemStack stack, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof CapsuleStationBlockEntity station)) {
            return false;
        }
        if (!CapsulePower.tell(player, station, storedSize(stack))) {
            return false;
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.put(TAG_BOUND_POS, NbtUtils.writeBlockPos(pos));
        tag.putString(TAG_BOUND_DIM, level.dimension().location().toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        player.displayClientMessage(Component.translatable("createaerotools.message.adv_capsule_release_armed"), true);
        level.playSound(null, pos, SoundEvents.WOODEN_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.5F, 1.1F);
        return true;
    }

    private static boolean store(Level level, Player player, ItemStack stack, BlockPos clicked) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        CapsuleStationBlockEntity station = boundStation(serverLevel, player, stack);
        if (station == null) {
            return false;
        }

        SubLevelAccess access = SableCompanion.INSTANCE.getContaining(serverLevel, clicked);
        if (!(access instanceof ServerSubLevel subLevel) || subLevel.isRemoved()) {
            player.displayClientMessage(Component.translatable("createaerotools.message.capsule_not_structure")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }

        List<ServerSubLevel> group = StructureGroups.collect(serverLevel, subLevel);
        UUID stationStructure = WorldSpace.structureId(serverLevel, station.getBlockPos());
        if (StructureGroups.contains(group, stationStructure)) {
            player.displayClientMessage(Component.translatable("createaerotools.message.adv_capsule_includes_station")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (!ensureRange(player, stack, station, group)) {
            return false;
        }

        double size = CapsulePower.sizeOf(group);
        if (!CapsulePower.tell(player, station, size)) {
            return false;
        }

        StructureGroups.unlinkExternal(group, player);

        List<CompoundTag> payloads = StructureGroups.capture(group);
        if (payloads.isEmpty()) {
            return false;
        }

        String name = groupName(group);
        CompoundTag itemTag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        ListTag list = new ListTag();
        list.addAll(payloads);
        itemTag.put(TAG_STRUCTURES, list);
        itemTag.putInt(TAG_COUNT, payloads.size());
        itemTag.putDouble(TAG_SIZE, size);
        if (subLevel.getUniqueId() != null) {
            itemTag.putUUID(TAG_ANCHOR, subLevel.getUniqueId());
        } else {
            itemTag.remove(TAG_ANCHOR);
        }
        if (!name.isBlank()) {
            itemTag.putString(CapsulePayloads.TAG_NAME, name);
        } else {
            itemTag.remove(CapsulePayloads.TAG_NAME);
        }
        itemTag.remove(TAG_BOUND_POS);
        itemTag.remove(TAG_BOUND_DIM);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(itemTag));

        StructureGroups.beginRemoval(group);
        try {
            StructureGroups.removeAll(serverLevel, group);
        } finally {
            StructureGroups.endRemoval();
        }
        player.displayClientMessage(Component.translatable("createaerotools.message.adv_capsule_stored",
                CapsulePayloads.storedName(stack), payloads.size()), true);
        serverLevel.playSound(null, player.blockPosition(), SoundEvents.ENDER_CHEST_CLOSE, SoundSource.PLAYERS, 0.6F, 0.9F);
        return true;
    }

    private static boolean release(Level level, Player player, ItemStack stack, Vec3 at) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        CapsuleStationBlockEntity station = boundStation(serverLevel, player, stack);
        if (station == null) {
            return false;
        }
        if (!ensureRange(player, stack, station, at)) {
            return false;
        }

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        CompoundTag itemTag = data.copyTag();
        if (!itemTag.contains(TAG_STRUCTURES, Tag.TAG_LIST)) {
            return false;
        }
        ListTag list = itemTag.getList(TAG_STRUCTURES, Tag.TAG_COMPOUND);
        List<CompoundTag> payloads = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            payloads.add(list.getCompound(i));
        }
        double size = itemTag.contains(TAG_SIZE) ? itemTag.getDouble(TAG_SIZE) : CapsulePower.sizeOfStored(payloads);
        if (!CapsulePower.tell(player, station, size)) {
            return false;
        }
        UUID anchor = itemTag.hasUUID(TAG_ANCHOR) ? itemTag.getUUID(TAG_ANCHOR) : null;
        if (!StructureGroups.restore(serverLevel, payloads, at, anchor)) {
            player.displayClientMessage(Component.translatable("createaerotools.message.capsule_release_fail")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }

        itemTag.remove(TAG_STRUCTURES);
        itemTag.remove(CapsulePayloads.TAG_NAME);
        itemTag.remove(TAG_COUNT);
        itemTag.remove(TAG_SIZE);
        itemTag.remove(TAG_ANCHOR);
        itemTag.remove(TAG_BOUND_POS);
        itemTag.remove(TAG_BOUND_DIM);
        if (itemTag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(itemTag));
        }
        player.displayClientMessage(Component.translatable("createaerotools.message.adv_capsule_released", payloads.size()), true);
        serverLevel.playSound(null, BlockPos.containing(at), SoundEvents.ENDER_CHEST_OPEN, SoundSource.PLAYERS, 0.6F, 1.1F);
        return true;
    }

    private static Vec3 releasePos(UseOnContext context, ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        Vec3 com = Vec3.ZERO;
        BoundingBox3d box = null;
        if (data != null) {
            CompoundTag tag = data.copyTag();
            UUID anchor = tag.hasUUID(TAG_ANCHOR) ? tag.getUUID(TAG_ANCHOR) : null;
            ListTag list = tag.getList(TAG_STRUCTURES, Tag.TAG_COMPOUND);
            boolean foundAnchor = false;
            for (int i = 0; i < list.size(); i++) {
                CompoundTag payload = list.getCompound(i);
                BoundingBox3d part = CapsulePayloads.worldBounds(payload);
                if (part != null) {
                    if (box == null) {
                        box = new BoundingBox3d(part);
                    } else {
                        box.expandTo(part);
                    }
                }
                if (!foundAnchor && payload.contains("pose")
                        && (anchor == null || payload.getUUID("uuid").equals(anchor))) {
                    com = CapsulePayloads.posePosition(payload);
                    foundAnchor = true;
                }
            }
        }
        return CapsulePayloads.releaseAgainst(context, com, box);
    }

    @Nullable
    private static CapsuleStationBlockEntity boundStation(ServerLevel level, Player player, ItemStack stack) {
        Optional<BlockPos> bound = boundPos(stack);
        if (bound.isEmpty() || !sameDimension(stack, level)) {
            String key = hasStructures(stack)
                    ? "createaerotools.message.adv_capsule_need_station"
                    : "createaerotools.message.adv_capsule_bind_first";
            player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true);
            clearBound(stack);
            return null;
        }
        if (!(level.getBlockEntity(bound.get()) instanceof CapsuleStationBlockEntity station) || station.isRemoved()) {
            player.displayClientMessage(Component.translatable("createaerotools.message.capsule_station_missing")
                    .withStyle(ChatFormatting.RED), true);
            clearBound(stack);
            return null;
        }
        return station;
    }

    private static String groupName(List<ServerSubLevel> group) {
        for (ServerSubLevel subLevel : group) {
            String name = subLevel.getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return CapsulePayloads.unnamed();
    }

    private static int storedCount(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 0;
        }
        CompoundTag tag = data.copyTag();
        if (tag.contains(TAG_COUNT)) {
            return tag.getInt(TAG_COUNT);
        }
        return tag.contains(TAG_STRUCTURES, Tag.TAG_LIST) ? tag.getList(TAG_STRUCTURES, Tag.TAG_COMPOUND).size() : 0;
    }

    private static double storedSize(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 1.0D;
        }
        CompoundTag tag = data.copyTag();
        if (tag.contains(TAG_SIZE)) {
            return Math.max(1.0D, tag.getDouble(TAG_SIZE));
        }
        if (!tag.contains(TAG_STRUCTURES, Tag.TAG_LIST)) {
            return 1.0D;
        }
        ListTag list = tag.getList(TAG_STRUCTURES, Tag.TAG_COMPOUND);
        List<CompoundTag> payloads = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            payloads.add(list.getCompound(i));
        }
        return CapsulePower.sizeOfStored(payloads);
    }

    public static boolean isBound(ItemStack stack) {
        return boundPos(stack).isPresent();
    }

    public static Optional<BlockPos> boundPos(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return Optional.empty();
        }
        CompoundTag tag = data.copyTag();
        if (!tag.contains(TAG_BOUND_POS)) {
            return Optional.empty();
        }
        return NbtUtils.readBlockPos(tag, TAG_BOUND_POS);
    }

    public static boolean sameDimension(ItemStack stack, Level level) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        return level.dimension().location().toString().equals(data.copyTag().getString(TAG_BOUND_DIM));
    }

    public static boolean inRange(CapsuleStationBlockEntity station, Vec3 worldPos) {
        double range = CATConfig.SERVER.capsuleStationRange.get();
        if (range <= 0.0D) {
            return true;
        }
        Level level = station.getLevel();
        if (level == null) {
            return false;
        }
        return WorldSpace.worldBlockCenter(level, station.getBlockPos()).distanceToSqr(worldPos) <= range * range;
    }

    @Nullable
    public static CapsuleStationBlockEntity boundStation(Level level, ItemStack stack) {
        Optional<BlockPos> bound = boundPos(stack);
        if (bound.isEmpty() || !sameDimension(stack, level)) {
            return null;
        }
        if (level.getBlockEntity(bound.get()) instanceof CapsuleStationBlockEntity station && !station.isRemoved()) {
            return station;
        }
        return null;
    }

    private static boolean ensureRange(Player player, ItemStack stack, CapsuleStationBlockEntity station, Vec3 worldPos) {
        if (inRange(station, worldPos) && inRange(station, player.position())) {
            return true;
        }
        clearBound(stack);
        player.displayClientMessage(Component.translatable("createaerotools.message.capsule_out_of_range")
                .withStyle(ChatFormatting.RED), true);
        return false;
    }

    private static boolean ensureRange(Player player, ItemStack stack, CapsuleStationBlockEntity station,
            List<ServerSubLevel> group) {
        if (!inRange(station, player.position())) {
            clearBound(stack);
            player.displayClientMessage(Component.translatable("createaerotools.message.capsule_out_of_range")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        for (ServerSubLevel subLevel : group) {
            var pos = subLevel.logicalPose().position();
            if (!inRange(station, new Vec3(pos.x, pos.y, pos.z))) {
                clearBound(stack);
                player.displayClientMessage(Component.translatable("createaerotools.message.capsule_out_of_range")
                        .withStyle(ChatFormatting.RED), true);
                return false;
            }
        }
        return true;
    }

    private static void clearBound(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return;
        }
        CompoundTag tag = data.copyTag();
        tag.remove(TAG_BOUND_POS);
        tag.remove(TAG_BOUND_DIM);
        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }
}
