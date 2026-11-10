package com.smeshariki.level.net;

import com.smeshariki.level.LevelMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Ожидание после сброса задачи: {@code ticks} тиков игрок не может двигаться (камеру крутит), на экране отсчёт секунд. */
public record ResetWait(int ticks) implements CustomPacketPayload {
	public static final Type<ResetWait> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "reset_wait"));
	public static final StreamCodec<ByteBuf, ResetWait> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, ResetWait::ticks, ResetWait::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
