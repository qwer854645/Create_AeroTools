package com.createaerotools;

import com.createaerotools.block.DriveLinkTracker;
import com.createaerotools.client.CapsulePreviewRenderer;
import com.createaerotools.client.DriveShaftLinkPreview;
import com.createaerotools.client.DriveShaftRenderer;
import com.createaerotools.client.PhysicsToolGunClient;
import com.createaerotools.config.CATConfig;
import com.createaerotools.index.CATBlockEntities;
import com.createaerotools.index.CATBlocks;
import com.createaerotools.index.CATCreativeTab;
import com.createaerotools.index.CATItems;
import com.createaerotools.item.PhysicsToolGun;
import com.createaerotools.item.PhysicsToolGunItem;
import com.createaerotools.network.CATNetwork;
import com.createaerotools.network.ToolGunBeamsPayload;
import com.simibubi.create.api.stress.BlockStressValues;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Create: AeroTools（机械动力：航空学工具）模组入口。
 * <p>
 * 负责注册内容、服务端配置与全局事件；客户端渲染/工具枪钩子通过静态字段注入，
 * 避免 common 代码直接依赖 client 类。
 */
@Mod(CreateAeroTools.MOD_ID)
public class CreateAeroTools {
    public static final String MOD_ID = "createaerotools";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 客户端：右键使用物理工具枪（服务端物品逻辑回调）。 */
    public static Runnable TOOL_GUN_USE = () -> {};
    /** 客户端：左键冻结/解冻结构。 */
    public static Runnable TOOL_GUN_LOCK = () -> {};
    /** 客户端：同步其他玩家的工具枪光束。 */
    public static Consumer<List<ToolGunBeamsPayload.Session>> TOOL_GUN_BEAMS = sessions -> {};

    public CreateAeroTools(IEventBus modEventBus, ModContainer modContainer) {
        CAT.REGISTRATE.registerEventListeners(modEventBus);
        CATCreativeTab.TABS.register(modEventBus);

        CATBlocks.register();
        CATItems.register();
        CATBlockEntities.register();
        CATNetwork.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.SERVER, CATConfig.SERVER_SPEC);
        modEventBus.addListener(CreateAeroTools::onCommonSetup);

        NeoForge.EVENT_BUS.addListener(CreateAeroTools::onServerTick);
        NeoForge.EVENT_BUS.addListener(CreateAeroTools::onPhysicsTick);
        NeoForge.EVENT_BUS.addListener(CreateAeroTools::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(CreateAeroTools::onLeftClickBlock);
        // HIGH：扳手对准轴体时优先于方块交互
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, CreateAeroTools::onWrenchShaftItem);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, CreateAeroTools::onWrenchShaftBlock);
        NeoForge.EVENT_BUS.addListener(CreateAeroTools::onPlayerLogin);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            DriveShaftRenderer.register();
            DriveShaftLinkPreview.register();
            CapsulePreviewRenderer.register();
            PhysicsToolGunClient.register();
            TOOL_GUN_USE = PhysicsToolGunClient::onRightClick;
            TOOL_GUN_LOCK = PhysicsToolGunClient::onLock;
            TOOL_GUN_BEAMS = PhysicsToolGunClient::syncRemoteBeams;
        }

        LOGGER.info("Create: AeroTools loading");
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        // 收纳底座应力冲击值走配置，需在 common setup 后再注册
        event.enqueueWork(() -> BlockStressValues.IMPACTS.register(
                CATBlocks.CAPSULE_STATION.get(),
                () -> CATConfig.SERVER.capsuleStationStress.get()
        ));
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            PhysicsToolGun.tickLevel(level);
        }
        DriveLinkTracker.tickOrphans();
    }

    /** Sable 物理步之后：同步万向轴约束与工具枪关节。 */
    private static void onPhysicsTick(ForgeSablePostPhysicsTickEvent event) {
        DriveLinkTracker.physicsTick();
        PhysicsToolGun.physicsTick(event.getPhysicsSystem());
    }

    /** 手持工具枪时禁止破坏方块，改为冻结逻辑。 */
    private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getItemStack().getItem() instanceof PhysicsToolGunItem)) {
            return;
        }
        event.setCanceled(true);
        if (event.getLevel().isClientSide) {
            TOOL_GUN_LOCK.run();
        }
    }

    /** 潜行 + 扳手对准空中的万向轴：收回轴。 */
    private static void onWrenchShaftItem(PlayerInteractEvent.RightClickItem event) {
        if (!DriveLinkTracker.tryWrenchShaft(event.getEntity(), event.getHand())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * 潜行 + 扳手：若视线先碰到轴再碰到方块，优先收回轴，避免误开端口界面。
     */
    private static void onWrenchShaftBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getEntity().isShiftKeyDown()) {
            return;
        }
        Vec3 eye = event.getEntity().getEyePosition();
        double blockDist = eye.distanceTo(event.getHitVec().getLocation());
        if (!DriveLinkTracker.shaftCloserThan(event.getEntity(), blockDist)) {
            return;
        }
        if (!DriveLinkTracker.tryWrenchShaft(event.getEntity(), event.getHand())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
            PhysicsToolGun.get(level).sendBeamsTo(player);
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        LevelAccessor level = event.getLevel();
        if (level instanceof ServerLevel serverLevel) {
            DriveLinkTracker.clearLevel(serverLevel);
            PhysicsToolGun.clearLevel(serverLevel);
        }
    }
}
