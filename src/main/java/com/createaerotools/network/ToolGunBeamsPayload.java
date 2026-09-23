package com.createaerotools.network;

import com.createaerotools.CreateAeroTools;
import com.createaerotools.CAT;
import dev.ryanhcode.sable.util.SableBufferUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 服务端 → 客户端：同步其他玩家的工具枪光束。 */
public record ToolGunBeamsPayload(List<Session> sessions) implements CustomPacketPayload {
    public static final Type<ToolGunBeamsPayload> TYPE = new Type<>(CAT.id("tool_gun_beams"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToolGunBeamsPayload> STREAM_CODEC =
            StreamCodec.of(ToolGunBeamsPayload::write, ToolGunBeamsPayload::read);

    private static ToolGunBeamsPayload read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Session> sessions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            sessions.add(new Session(buf.readUUID(), buf.readUUID(), SableBufferUtils.read(buf, new Vector3d())));
        }
        return new ToolGunBeamsPayload(List.copyOf(sessions));
    }

    private static void write(RegistryFriendlyByteBuf buf, ToolGunBeamsPayload payload) {
        buf.writeVarInt(payload.sessions.size());
        for (Session session : payload.sessions) {
            buf.writeUUID(session.player);
            buf.writeUUID(session.structure);
            SableBufferUtils.write(buf, session.anchor);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ToolGunBeamsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> CreateAeroTools.TOOL_GUN_BEAMS.accept(payload.sessions()));
    }

    public record Session(UUID player, UUID structure, Vector3dc anchor) {
    }
}
