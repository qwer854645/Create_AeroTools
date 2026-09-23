package com.createaerotools.network;

import com.createaerotools.CAT;
import com.createaerotools.item.PhysicsToolGun;
import com.createaerotools.item.PhysicsToolGunItem;
import dev.ryanhcode.sable.util.SableBufferUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

/** 客户端 → 服务端：更新抓取目标位姿。 */
public record ToolGunDragPayload(UUID structure, Vector3dc relativeGoal, Vector3dc localAnchor, Quaterniondc orientation)
        implements CustomPacketPayload {
    public static final Type<ToolGunDragPayload> TYPE = new Type<>(CAT.id("tool_gun_drag"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToolGunDragPayload> STREAM_CODEC =
            StreamCodec.of(ToolGunDragPayload::write, ToolGunDragPayload::read);

    private static ToolGunDragPayload read(RegistryFriendlyByteBuf buf) {
        return new ToolGunDragPayload(buf.readUUID(), SableBufferUtils.read(buf, new Vector3d()),
                SableBufferUtils.read(buf, new Vector3d()), SableBufferUtils.read(buf, new Quaterniond()));
    }

    private static void write(RegistryFriendlyByteBuf buf, ToolGunDragPayload payload) {
        buf.writeUUID(payload.structure);
        SableBufferUtils.write(buf, payload.relativeGoal);
        SableBufferUtils.write(buf, payload.localAnchor);
        SableBufferUtils.write(buf, payload.orientation);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ToolGunDragPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && PhysicsToolGunItem.held(player) != null) {
                PhysicsToolGun.get((ServerLevel) player.level())
                        .drag(player, payload.structure, payload.relativeGoal, payload.localAnchor, payload.orientation);
            }
        });
    }
}
