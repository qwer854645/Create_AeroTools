package com.createaerotools.block;

import com.createaerotools.config.CATConfig;
import com.createaerotools.util.WorldSpace;
import com.simibubi.create.AllItems;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 万向轴连接的全局跟踪器。
 * <p>
 * 职责概览：
 * <ul>
 *   <li>按 UUID 索引两端端口（服务端 / 客户端各一份）</li>
 *   <li>手持轴物品的两次点选连接（选择存在 ItemStack NBT，类似 Create 传送带）</li>
 *   <li>结构组装/收纳时的「孤儿」宽限与外部轴回收，避免吞轴</li>
 *   <li>每 tick 检测拉断，并驱动 {@link DriveShaftPhysics}</li>
 * </ul>
 */
public final class DriveLinkTracker {
    /** 轴物品上记录的第一次选中端口（Create 传送带风格：跟物品走，不跟玩家走）。 */
    private static final String FIRST_PORT = "FirstPort";
    private static final Map<UUID, DrivePortBlockEntity> SERVER = new ConcurrentHashMap<>();
    private static final Map<UUID, DrivePortBlockEntity> CLIENT = new ConcurrentHashMap<>();
    /** 端口暂时消失（组装/拆解）时等待对端回来的宽限条目。 */
    private static final Map<UUID, Orphan> ORPHANS = new ConcurrentHashMap<>();
    /** 长度瞬间爆炸式变长时的容错计数，避免误断。 */
    private static final Map<UUID, Integer> INSANE_HOLD = new ConcurrentHashMap<>();
    /** 收纳移除中、两端都在组内的端口：跳过孤儿宽限（内部链接写进 NBT）。 */
    private static final Set<UUID> CAPSULE_SUSPEND = ConcurrentHashMap.newKeySet();
    private static final int ORPHAN_GRACE = 60;

    private DriveLinkTracker() {
    }

    public static void register(DrivePortBlockEntity be) {
        ports(be).put(be.portId(), be);
        if (be.getLevel() != null && !be.getLevel().isClientSide) {
            ORPHANS.remove(be.portId());
        }
    }

    public static void reregister(DrivePortBlockEntity be, UUID previous) {
        ports(be).remove(previous, be);
        register(be);
    }

    /**
     * 端口方块随结构组装/拆解暂时消失。
     * 保持链接，等待同一 portId 回来；收纳挂起时按内外轴分别处理。
     */
    public static void onBlockGone(DrivePortBlockEntity be) {
        DrivePortBlockEntity current = SERVER.get(be.portId());
        if (current != null && current != be && !current.isRemoved()) {
            unregister(be);
            return;
        }
        if (CAPSULE_SUSPEND.contains(be.portId())) {
            ORPHANS.remove(be.portId());
            // 组内两端都挂起：内部轴保留在收纳 NBT；对端在组外则必须立刻回收物品
            UUID partnerId = be.partnerId();
            boolean internal = partnerId != null && CAPSULE_SUSPEND.contains(partnerId);
            if (be.isLinked() && !internal) {
                unlink(be, null, false);
            }
            unregister(be);
            return;
        }
        if (be.isLinked() && WorldSpace.parentLevel(be) instanceof ServerLevel server && be.partnerId() != null) {
            ORPHANS.put(be.portId(), new Orphan(be.partnerId(), server, ORPHAN_GRACE));
        }
        unregister(be);
    }

    /**
     * 收纳开始移除结构时调用：仅把「两端都在本组内」的轴加入挂起集合。
     * 外部轴不能挂起，否则会吞物品、不掉落。
     */
    public static void beginCapsuleRemoval(Collection<? extends SubLevel> group) {
        CAPSULE_SUSPEND.clear();
        if (group == null || group.isEmpty()) {
            return;
        }
        for (DrivePortBlockEntity be : List.copyOf(SERVER.values())) {
            if (be.isRemoved() || !be.isLinked()) {
                continue;
            }
            if (!onGroup(be, group)) {
                continue;
            }
            DrivePortBlockEntity other = partner(be);
            if (other == null || other.isRemoved() || !onGroup(other, group)) {
                continue;
            }
            CAPSULE_SUSPEND.add(be.portId());
            if (be.partnerId() != null) {
                CAPSULE_SUSPEND.add(be.partnerId());
            }
        }
    }

    public static void endCapsuleRemoval() {
        CAPSULE_SUSPEND.clear();
    }

    /**
     * 枚举与 {@code structureId} 通过万向轴相连的其他结构 UUID。
     * 客户端预览与服务端收纳均可使用。
     */
    public static void visitLinkedStructures(Level level, UUID structureId, Consumer<UUID> consumer) {
        if (level == null || structureId == null || consumer == null) {
            return;
        }
        Map<UUID, DrivePortBlockEntity> map = level.isClientSide ? CLIENT : SERVER;
        Set<UUID> emitted = new HashSet<>();
        for (DrivePortBlockEntity be : List.copyOf(map.values())) {
            if (be.isRemoved() || !be.isLinked()) {
                continue;
            }
            if (!structureId.equals(WorldSpace.structureId(be))) {
                continue;
            }
            DrivePortBlockEntity other = partner(be);
            if (other == null || other.isRemoved()) {
                continue;
            }
            UUID otherStructure = WorldSpace.structureId(other);
            if (otherStructure == null || structureId.equals(otherStructure)) {
                continue;
            }
            if (emitted.add(otherStructure)) {
                consumer.accept(otherStructure);
            }
        }
    }

    /**
     * 断开「一端在本组、一端在组外/世界」的轴，并把物品还给玩家。
     * @return 回收的轴数量
     */
    public static int unlinkExternal(Collection<? extends SubLevel> group, @Nullable Player player) {
        if (group == null || group.isEmpty()) {
            return 0;
        }
        int recovered = 0;
        Set<UUID> processed = new HashSet<>();
        for (DrivePortBlockEntity be : List.copyOf(SERVER.values())) {
            if (be.isRemoved() || !be.isLinked()) {
                continue;
            }
            if (!processed.add(be.portId())) {
                continue;
            }
            DrivePortBlockEntity other = partner(be);
            boolean ownIn = onGroup(be, group);
            boolean otherIn = other != null && !other.isRemoved() && onGroup(other, group);
            // 恰好一端在组内 = 外部连接
            if (ownIn == otherIn) {
                continue;
            }
            if (other != null) {
                processed.add(other.portId());
            }
            unlink(be, player, true);
            recovered++;
        }
        return recovered;
    }

    private static boolean onGroup(DrivePortBlockEntity be, Collection<? extends SubLevel> group) {
        if (WorldSpace.isOn(be, group)) {
            return true;
        }
        // getContaining 解析到了别处（世界/其他结构）→ 不在本组
        if (SableCompanion.INSTANCE.getContaining(be) != null) {
            return false;
        }
        // getContaining 失败：退回 plot 局部坐标，再退回世界 AABB
        BlockPos pos = be.getBlockPos();
        Vec3 at = WorldSpace.worldLinkAnchor(be, be.facing());
        for (SubLevel subLevel : group) {
            if (subLevel == null || subLevel.isRemoved()) {
                continue;
            }
            var plot = subLevel.getPlot();
            if (plot != null) {
                var plotBox = plot.getBoundingBox();
                if (plotBox != null
                        && pos.getX() >= plotBox.minX() && pos.getX() <= plotBox.maxX()
                        && pos.getY() >= plotBox.minY() && pos.getY() <= plotBox.maxY()
                        && pos.getZ() >= plotBox.minZ() && pos.getZ() <= plotBox.maxZ()) {
                    return true;
                }
            }
            var box = subLevel.boundingBox().toMojang().inflate(0.25D);
            if (box.contains(at)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 玩家/爆炸永久破坏端口：立刻掉轴，不等 {@link #ORPHAN_GRACE}
     * （宽限只给组装/拆解的短暂重建用）。
     */
    public static void breakPermanently(DrivePortBlockEntity be) {
        if (be.isLinked()) {
            unlink(be, null, false);
        }
    }

    public static void tickOrphans() {
        Iterator<Map.Entry<UUID, Orphan>> iterator = ORPHANS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Orphan> entry = iterator.next();
            Orphan orphan = entry.getValue();
            if (orphan.ticksLeft > 1) {
                entry.setValue(new Orphan(orphan.partnerId, orphan.level, orphan.ticksLeft - 1));
                continue;
            }
            iterator.remove();
            INSANE_HOLD.remove(entry.getKey());
            DrivePortBlockEntity partner = SERVER.get(orphan.partnerId);
            if (partner != null && !partner.isRemoved() && partner.isLinked()) {
                unlink(partner, null, false);
            }
        }
    }

    public static void unregister(DrivePortBlockEntity be) {
        ports(be).remove(be.portId(), be);
        if (be.getLevel() != null && !be.getLevel().isClientSide && be.isMaster()) {
            DriveShaftPhysics.destroy(be.portId());
        }
    }

    public static Iterable<DrivePortBlockEntity> loaded() {
        return CLIENT.values();
    }

    @Nullable
    public static DrivePortBlockEntity loadedMaster(UUID id) {
        DrivePortBlockEntity be = SERVER.get(id);
        return be == null || be.isRemoved() ? null : be;
    }

    @Nullable
    public static DrivePortBlockEntity partner(DrivePortBlockEntity be) {
        if (be.partnerId() == null) {
            return null;
        }
        DrivePortBlockEntity partner = ports(be).get(be.partnerId());
        return partner == null || partner.isRemoved() ? null : partner;
    }

    private static Map<UUID, DrivePortBlockEntity> ports(DrivePortBlockEntity be) {
        Level level = be.getLevel();
        return level != null && level.isClientSide ? CLIENT : SERVER;
    }

    /** 手持万向轴右键端口：选中 / 连接 / 潜行收回。 */
    public static ItemInteractionResult handleShaft(DrivePortBlockEntity be, Player player, ItemStack stack) {
        Level level = be.getLevel();
        if (level == null) {
            return ItemInteractionResult.FAIL;
        }
        if (player.isShiftKeyDown()) {
            if (be.isLinked()) {
                unlink(be, player, true);
                message(player, "createaerotools.message.drive_unlinked", false);
            } else {
                clearSelection(stack);
                message(player, "createaerotools.message.drive_cleared", false);
            }
            return ItemInteractionResult.SUCCESS;
        }
        if (be.isLinked()) {
            message(player, "createaerotools.message.drive_already_linked", true);
            return ItemInteractionResult.FAIL;
        }
        DrivePortBlockEntity first = selectedPort(stack, level);
        if (first == null) {
            storeSelection(stack, be);
            message(player, "createaerotools.message.drive_first", false);
            return ItemInteractionResult.SUCCESS;
        }
        if (first == be || first.portId().equals(be.portId())) {
            message(player, "createaerotools.message.drive_same", true);
            return ItemInteractionResult.FAIL;
        }
        if (WorldSpace.parentLevel(first) != WorldSpace.parentLevel(be)) {
            clearSelection(stack);
            message(player, "createaerotools.message.drive_wrong_dimension", true);
            return ItemInteractionResult.FAIL;
        }
        if (first.isLinked() || be.isLinked()) {
            clearSelection(stack);
            message(player, "createaerotools.message.drive_already_linked", true);
            return ItemInteractionResult.FAIL;
        }
        double length = WorldSpace.worldLinkAnchor(first, first.facing())
                .distanceTo(WorldSpace.worldLinkAnchor(be, be.facing()));
        String reject = validateLink(first, be, length, DriveShaftKind.from(stack));
        if (reject != null) {
            message(player, reject, true);
            return ItemInteractionResult.FAIL;
        }
        first.bind(be, length, true, DriveShaftKind.from(stack));
        be.bind(first, length, false, DriveShaftKind.from(stack));
        DriveShaftPhysics.sync(first, be);
        first.rebuildKineticLinks();
        be.rebuildKineticLinks();
        clearSelection(stack);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        playAt(be, SoundEvents.WOOD_PLACE, 0.8F, 1.2F);
        message(player, "createaerotools.message.drive_linked", false);
        return ItemInteractionResult.SUCCESS;
    }

    /** 主端每 tick：同步物理、检测拉断；对端缺失时进入 WAITING。 */
    public static void tick(DrivePortBlockEntity be) {
        if (!be.isLinked()) {
            return;
        }
        DrivePortBlockEntity partner = partner(be);
        boolean relocating = ORPHANS.containsKey(be.portId())
                || (be.partnerId() != null && ORPHANS.containsKey(be.partnerId()));
        if (partner == null || relocating || WorldSpace.parentLevel(partner) != WorldSpace.parentLevel(be)) {
            be.setStatus(DrivePortBlockEntity.LinkStatus.WAITING);
            if (be.isMaster()) {
                DriveShaftPhysics.destroy(be.portId());
            }
            return;
        }
        if (!be.isMaster()) {
            return;
        }
        double length = WorldSpace.worldLinkAnchor(be, be.facing())
                .distanceTo(WorldSpace.worldLinkAnchor(partner, partner.facing()));
        double snapAt = be.snapLength();
        boolean insane = Double.isFinite(snapAt) && length > Math.max(snapAt * 2.0D, 32.0D);
        if (insane) {
            int held = INSANE_HOLD.merge(be.portId(), 1, Integer::sum);
            if (held < 10) {
                be.setStatus(DrivePortBlockEntity.LinkStatus.WAITING);
                partner.setStatus(DrivePortBlockEntity.LinkStatus.WAITING);
                DriveShaftPhysics.destroy(be.portId());
                return;
            }
        } else {
            INSANE_HOLD.remove(be.portId());
        }
        if (length > snapAt) {
            INSANE_HOLD.remove(be.portId());
            unlink(be, null, false);
            return;
        }
        DriveShaftPhysics.sync(be, partner);
        be.setStatus(DrivePortBlockEntity.LinkStatus.CONNECTED);
        partner.setStatus(DrivePortBlockEntity.LinkStatus.CONNECTED);
    }

    /**
     * 潜行 + Create 扳手对准已连接的轴体（不一定对准端口方块）。
     * 与潜行扳手端口相同：断开并把轴物品还给玩家。
     *
     * @return 是否瞄准了轴（客户端也返回 true 以取消交互）；实际 unlink 仅在服务端执行
     */
    public static boolean tryWrenchShaft(Player player, InteractionHand hand) {
        if (!player.isShiftKeyDown() || !AllItems.WRENCH.isIn(player.getItemInHand(hand))) {
            return false;
        }
        ShaftHit hit = findShaftAlongLook(player);
        if (hit == null) {
            return false;
        }
        Level level = player.level();
        if (!level.isClientSide) {
            unlink(hit.master(), player, true);
            message(player, "createaerotools.message.drive_unlinked", false);
        }
        return true;
    }

    /**
     * 视线射线是否在 {@code maxDistance} 内先碰到已连接的轴
     * （用于扳手：轴在方块前面时应优先收回轴）。
     */
    public static boolean shaftCloserThan(Player player, double maxDistance) {
        if (!player.isShiftKeyDown()) {
            return false;
        }
        ShaftHit hit = findShaftAlongLook(player);
        return hit != null && hit.distance() < maxDistance;
    }

    @Nullable
    private static ShaftHit findShaftAlongLook(Player player) {
        Level level = player.level();
        Map<UUID, DrivePortBlockEntity> ports = level.isClientSide ? CLIENT : SERVER;
        Vec3 origin = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double reach = player.blockInteractionRange();
        double hitRadius = 0.4D;
        double hitRadiusSq = hitRadius * hitRadius;
        DrivePortBlockEntity best = null;
        double bestDist = reach + 1.0D;
        for (DrivePortBlockEntity be : ports.values()) {
            if (!be.isMaster() || !be.isLinked() || be.isRemoved()) {
                continue;
            }
            if (WorldSpace.parentLevel(be) != level) {
                continue;
            }
            DrivePortBlockEntity partner = partner(be);
            if (partner == null || partner.isRemoved()) {
                continue;
            }
            Vec3 from = WorldSpace.worldLinkAnchor(be, be.facing());
            Vec3 to = WorldSpace.worldLinkAnchor(partner, partner.facing());
            double dist = rayHitsSegment(origin, look, reach, from, to, hitRadiusSq);
            if (dist >= 0.0D && dist < bestDist) {
                bestDist = dist;
                best = be;
            }
        }
        return best == null ? null : new ShaftHit(best, bestDist);
    }

    /**
     * @return distance along the look ray to the closest point on the segment, or {@code -1} if no hit
     */
    private static double rayHitsSegment(Vec3 origin, Vec3 look, double reach, Vec3 a, Vec3 b, double radiusSq) {
        Vec3 ab = b.subtract(a);
        double abLenSq = ab.lengthSqr();
        if (abLenSq < 1.0E-6D) {
            return -1.0D;
        }
        // Closest points between ray origin+t*look (t in [0, reach]) and segment a+s*ab (s in [0,1]).
        double aa = look.dot(look);
        double bb = abLenSq;
        double abDot = look.dot(ab);
        Vec3 w0 = origin.subtract(a);
        double aw = look.dot(w0);
        double bw = ab.dot(w0);
        double denom = aa * bb - abDot * abDot;
        double t;
        double s;
        if (denom < 1.0E-8D) {
            t = Mth.clamp(-aw / Math.max(aa, 1.0E-8D), 0.0D, reach);
            s = Mth.clamp((ab.dot(origin.add(look.scale(t)).subtract(a))) / bb, 0.0D, 1.0D);
        } else {
            t = Mth.clamp((abDot * bw - bb * aw) / denom, 0.0D, reach);
            s = Mth.clamp((aa * bw - abDot * aw) / denom, 0.0D, 1.0D);
        }
        Vec3 onRay = origin.add(look.scale(t));
        Vec3 onSeg = a.add(ab.scale(s));
        if (onRay.distanceToSqr(onSeg) > radiusSq) {
            return -1.0D;
        }
        return t;
    }

    private record ShaftHit(DrivePortBlockEntity master, double distance) {
    }

    public static void unlink(DrivePortBlockEntity be, @Nullable Player player, boolean giveToPlayer) {
        if (!be.isLinked()) {
            return;
        }
        DrivePortBlockEntity partner = partner(be);
        UUID masterId = be.isMaster() ? be.portId() : be.partnerId();
        if (masterId != null) {
            DriveShaftPhysics.destroy(masterId);
        }
        playAt(be, giveToPlayer ? SoundEvents.ITEM_FRAME_REMOVE_ITEM : SoundEvents.CHAIN_BREAK,
                0.8F, giveToPlayer ? 1.1F : 0.65F);
        dropShaft(be, partner, player, giveToPlayer);
        UUID portId = be.portId();
        UUID partnerId = be.partnerId();
        be.clearLink();
        if (partner != null) {
            partner.clearLink();
        }
        INSANE_HOLD.remove(portId);
        if (partnerId != null) {
            INSANE_HOLD.remove(partnerId);
            ORPHANS.remove(partnerId);
        }
        ORPHANS.remove(portId);
    }

    public static void clearLevel(Level level) {
        DriveShaftPhysics.clearLevel(level);
        SERVER.values().removeIf(be -> be.getLevel() == level);
        CLIENT.values().removeIf(be -> be.getLevel() == level);
        ORPHANS.values().removeIf(orphan -> orphan.level == level);
        INSANE_HOLD.clear();
        CAPSULE_SUSPEND.clear();
    }

    public static void physicsTick() {
        DriveShaftPhysics.physicsTick();
    }

    /**
     * Why a link would fail, or {@code null} if it is allowed.
     * Safe to call on the client for placement previews.
     */
    @Nullable
    public static String validateLink(DrivePortBlockEntity a, DrivePortBlockEntity b, double length,
                                      DriveShaftKind kind) {
        boolean pneumaticA = a instanceof PneumaticPortBlockEntity;
        boolean pneumaticB = b instanceof PneumaticPortBlockEntity;
        if (pneumaticA != pneumaticB) {
            return "createaerotools.message.pneumatic_mismatch";
        }
        if (a.isLinked() || b.isLinked()) {
            return "createaerotools.message.drive_already_linked";
        }
        double max = kind.maxLinkLength();
        if (max > 0.0D && length > max) {
            return "createaerotools.message.drive_too_far";
        }
        if (length < 0.35D) {
            return "createaerotools.message.drive_too_close";
        }
        if (!facingAllowsLink(a, b)) {
            return "createaerotools.message.drive_bad_angle";
        }
        return null;
    }

    /**
     * Reject only when a port faces so far away from the shaft that the link would go
     * through the block — mild misalignment is fine (the joint is built along the real shaft).
     */
    private static boolean facingAllowsLink(DrivePortBlockEntity a, DrivePortBlockEntity b) {
        Vec3 from = WorldSpace.worldLinkAnchor(a, a.facing());
        Vec3 to = WorldSpace.worldLinkAnchor(b, b.facing());
        Vec3 delta = to.subtract(from);
        double len = delta.length();
        if (len < 1.0E-6D) {
            return false;
        }
        Vec3 dir = delta.scale(1.0D / len);
        Vec3 normalA = WorldSpace.worldNormal(a, a.facing());
        Vec3 normalB = WorldSpace.worldNormal(b, b.facing());
        // Allow up to sideways + configured swing; only block clearly reverse facings.
        double maxOff = Math.toRadians(90.0D + CATConfig.SERVER.driveMaxAngle.get());
        double offA = Math.acos(Mth.clamp(normalA.dot(dir), -1.0D, 1.0D));
        double offB = Math.acos(Mth.clamp(normalB.dot(new Vec3(-dir.x, -dir.y, -dir.z)), -1.0D, 1.0D));
        return offA <= maxOff && offB <= maxOff;
    }

    @Nullable
    public static DrivePortBlockEntity selectedPort(ItemStack stack, Level level) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        if (!tag.hasUUID(FIRST_PORT)) {
            return null;
        }
        Map<UUID, DrivePortBlockEntity> ports = level.isClientSide ? CLIENT : SERVER;
        DrivePortBlockEntity be = ports.get(tag.getUUID(FIRST_PORT));
        if (be == null || be.isRemoved()) {
            if (!level.isClientSide) {
                clearSelection(stack);
            }
            return null;
        }
        return be;
    }

    private static void dropShaft(DrivePortBlockEntity a, @Nullable DrivePortBlockEntity b, @Nullable Player player,
                                  boolean giveToPlayer) {
        ItemStack shaft = a.shaftKind().asStack();
        if (giveToPlayer && player != null) {
            // Always try inventory first — including creative. Skipping addItem on instabuild
            // made capsule recovery look like the shaft was swallowed.
            if (player.addItem(shaft.copy())) {
                return;
            }
            if (player.getAbilities().instabuild) {
                return;
            }
            Level level = player.level();
            if (level instanceof ServerLevel) {
                Vec3 at = player.position();
                ItemEntity entity = new ItemEntity(level, at.x, at.y + 0.1D, at.z, shaft);
                entity.setDeltaMovement(0.0D, 0.15D, 0.0D);
                entity.setPickUpDelay(10);
                level.addFreshEntity(entity);
                return;
            }
        }
        Level level = WorldSpace.parentLevel(a);
        if (!(level instanceof ServerLevel)) {
            level = b != null ? WorldSpace.parentLevel(b) : null;
        }
        if (!(level instanceof ServerLevel)) {
            return;
        }
        Vec3 at = breakPoint(a, b);
        ItemEntity entity = new ItemEntity(level, at.x, at.y, at.z, shaft);
        entity.setDeltaMovement(0.0D, 0.15D, 0.0D);
        entity.setPickUpDelay(10);
        level.addFreshEntity(entity);
    }

    private static Vec3 breakPoint(DrivePortBlockEntity a, @Nullable DrivePortBlockEntity b) {
        Vec3 at = WorldSpace.worldLinkAnchor(a, a.facing());
        Level level = WorldSpace.parentLevel(a);
        if (b != null && WorldSpace.parentLevel(b) == level) {
            at = at.add(WorldSpace.worldLinkAnchor(b, b.facing())).scale(0.5D);
        }
        return at;
    }

    private static void playAt(DrivePortBlockEntity be, SoundEvent sound, float volume, float pitch) {
        Level level = WorldSpace.parentLevel(be);
        if (level == null) {
            return;
        }
        Vec3 at = breakPoint(be, partner(be));
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.BLOCKS, volume, pitch);
    }

    public static void storeSelection(ItemStack stack, DrivePortBlockEntity be) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putUUID(FIRST_PORT, be.portId());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static void clearSelection(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return;
        }
        CompoundTag tag = data.copyTag();
        if (!tag.hasUUID(FIRST_PORT)) {
            return;
        }
        tag.remove(FIRST_PORT);
        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    private static void message(Player player, String key, boolean warn) {
        player.displayClientMessage(Component.translatable(key).withStyle(warn ? ChatFormatting.RED : ChatFormatting.GREEN), true);
    }

    private record Orphan(UUID partnerId, ServerLevel level, int ticksLeft) {
    }
}
