package com.createaerotools.network;

import com.createaerotools.CreateAeroTools;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** 物理工具枪网络包注册（C↔S）。 */
public final class CATNetwork {
    private CATNetwork() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(CATNetwork::onRegister);
    }

    private static void onRegister(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(CreateAeroTools.MOD_ID).versioned("1");
        registrar.playToServer(ToolGunDragPayload.TYPE, ToolGunDragPayload.STREAM_CODEC, ToolGunDragPayload::handle);
        registrar.playToServer(ToolGunActionPayload.TYPE, ToolGunActionPayload.STREAM_CODEC, ToolGunActionPayload::handle);
        registrar.playToClient(ToolGunBeamsPayload.TYPE, ToolGunBeamsPayload.STREAM_CODEC, ToolGunBeamsPayload::handle);
    }
}
