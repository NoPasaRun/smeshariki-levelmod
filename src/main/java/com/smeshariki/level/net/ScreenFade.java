package com.smeshariki.level.net;

import com.smeshariki.level.LevelMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Затемнить экран: {@code hold} тиков чёрный, затем за {@code fade} тиков светлеет. Шлётся погибшему, когда его возвращают к началу задачи. */
public record ScreenFade(int hold, int fade) implements CustomPacketPayload {
	public static final Type<ScreenFade> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "screen_fade"));
	public static final StreamCodec<ByteBuf, ScreenFade> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, ScreenFade::hold, ByteBufCodecs.VAR_INT, ScreenFade::fade, ScreenFade::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
