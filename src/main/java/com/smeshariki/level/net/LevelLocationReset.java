package com.smeshariki.level.net;

import com.smeshariki.level.LevelMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Откатить область-локацию к сохранённой версии мира (кнопка в окне локаций). */
public record LevelLocationReset(String name) implements CustomPacketPayload {
	public static final Type<LevelLocationReset> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "level_location_reset"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LevelLocationReset> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, LevelLocationReset::name, LevelLocationReset::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
