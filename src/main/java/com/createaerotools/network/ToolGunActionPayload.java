package com.createaerotools.network;

import com.createaerotools.CAT;
import com.createaerotools.item.PhysicsToolGun;
import com.createaerotools.item.PhysicsToolGunItem;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.codecs.stream.CatnipStreamCodecBuilders;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** 客户端 → 服务端：释放抓取 / 冻结等动作。 */
public record ToolGunActionPayload(Action action, UUID structure) implements CustomPacketPayload {
    public static final Type<ToolGunActionPayload> TYPE = new Type<>(CAT.id("tool_gun_action"));
    public static final StreamCodec<ByteBuf, ToolGunActionPayload> STREAM_CODEC = StreamCodec.composite(
            CatnipStreamCodecBuilders.ofEnum(Action.class), ToolGunActionPayload::action,
            UUIDUtil.STREAM_CODEC, ToolGunActionPayload::structure,
            ToolGunActionPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ToolGunActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || PhysicsToolGunItem.held(player) == null) {
                return;
            }
            PhysicsToolGun gun = PhysicsToolGun.get((ServerLevel) player.level());
            if (payload.action == Action.LOCK) {
                gun.toggleLock(player, payload.structure);
            } else if (payload.action == Action.STOP_DRAG) {
                gun.stopDragging(player.getUUID());
            }
        });
    }

    public enum Action {
        LOCK,
        STOP_DRAG
    }
}
