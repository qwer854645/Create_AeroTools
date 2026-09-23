package com.createaerotools.index;

import com.createaerotools.CAT;
import com.createaerotools.block.DriveShaftKind;
import com.createaerotools.item.AdvancedStructureCapsuleItem;
import com.createaerotools.item.DriveShaftItem;
import com.createaerotools.item.PhysicsToolGunItem;
import com.createaerotools.item.StructureCapsuleItem;
import com.tterrag.registrate.util.entry.ItemEntry;

/** 物品注册表。 */
public final class CATItems {
    public static final ItemEntry<DriveShaftItem> DRIVE_SHAFT = CAT.REGISTRATE
            .item("drive_shaft", properties -> new DriveShaftItem(properties, DriveShaftKind.BRASS))
            .register();

    public static final ItemEntry<DriveShaftItem> ANDESITE_DRIVE_SHAFT = CAT.REGISTRATE
            .item("andesite_drive_shaft", properties -> new DriveShaftItem(properties, DriveShaftKind.ANDESITE))
            .register();

    public static final ItemEntry<StructureCapsuleItem> STRUCTURE_CAPSULE = CAT.REGISTRATE
            .item("structure_capsule", StructureCapsuleItem::new)
            .register();

    public static final ItemEntry<AdvancedStructureCapsuleItem> ADVANCED_STRUCTURE_CAPSULE = CAT.REGISTRATE
            .item("advanced_structure_capsule", AdvancedStructureCapsuleItem::new)
            .register();

    public static final ItemEntry<PhysicsToolGunItem> PHYSICS_TOOL_GUN = CAT.REGISTRATE
            .item("physics_tool_gun", PhysicsToolGunItem::new)
            .register();

    private CATItems() {
    }

    public static void register() {
    }
}
