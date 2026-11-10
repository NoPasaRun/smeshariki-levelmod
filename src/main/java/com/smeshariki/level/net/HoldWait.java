package com.smeshariki.level.net;

import com.smeshariki.level.LevelMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Ожидание без отсчёта: игрок закончил миссию раньше других и стоит, пока её не закончат все ({@code on}); {@code false} — отпущен. Движение персонажа клиент держит сам. */
public record HoldWait(boolean on) implements CustomPacketPayload {
	public static final Type<HoldWait> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "hold_wait"));
	public static final StreamCodec<ByteBuf, HoldWait> CODEC = ByteBufCodecs.BOOL.map(HoldWait::new, HoldWait::on);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
