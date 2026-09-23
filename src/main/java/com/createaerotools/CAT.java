package com.createaerotools;

import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.item.ItemDescription;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;

/**
 * Create Registrate 入口与资源定位辅助。
 * <p>
 * 创造模式标签页在 {@link com.createaerotools.index.CATCreativeTab} 单独注册，
 * 这里先把默认 tab 置空，避免物品在注册瞬间挂到错误分组。
 */
public final class CAT {
    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(CreateAeroTools.MOD_ID)
            .setTooltipModifierFactory(item ->
                    new ItemDescription.Modifier(item, FontHelper.Palette.STANDARD_CREATE));

    private CAT() {
    }

    /** 本模组命名空间下的 {@link ResourceLocation}。 */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CreateAeroTools.MOD_ID, path);
    }

    static {
        REGISTRATE.defaultCreativeTab((ResourceKey<CreativeModeTab>) null);
    }
}
