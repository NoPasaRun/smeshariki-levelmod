package com.smeshariki.level.net;

import com.smeshariki.level.LevelMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Игрок, который ждёт (отсчёт после сброса или конец миссии у других), просит перейти в режим наблюдателя или, если уже наблюдает, вернуться. */
public record SpectateRequest() implements CustomPacketPayload {
	public static final Type<SpectateRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "spectate_request"));
	public static final StreamCodec<ByteBuf, SpectateRequest> CODEC = StreamCodec.unit(new SpectateRequest());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
