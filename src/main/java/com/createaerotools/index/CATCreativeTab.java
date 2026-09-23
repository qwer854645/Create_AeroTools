package com.createaerotools.index;

import com.createaerotools.CAT;
import com.createaerotools.CreateAeroTools;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashSet;
import java.util.Set;

/** 创造模式物品栏「Create: AeroTools」。 */
public final class CATCreativeTab {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateAeroTools.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.createaerotools"))
            .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
            .icon(() -> CATItems.STRUCTURE_CAPSULE.asStack())
            .displayItems((parameters, output) -> {
                Set<Item> displayedItems = new LinkedHashSet<>();
                for (var item : CAT.REGISTRATE.getAll(Registries.ITEM)) {
                    Item registeredItem = item.get();
                    if (displayedItems.add(registeredItem)) {
                        output.accept(new ItemStack(registeredItem));
                    }
                }
            })
            .build());

    private CATCreativeTab() {
    }
}
