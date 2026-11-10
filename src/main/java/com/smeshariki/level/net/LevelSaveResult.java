package com.smeshariki.level.net;

import java.util.List;

import com.smeshariki.level.LevelMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Ответ на {@link LevelSave}: что не так с уровнем; пустой список — сохранён. */
public record LevelSaveResult(List<String> problems) implements CustomPacketPayload {
	public static final Type<LevelSaveResult> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "level_save_result"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LevelSaveResult> CODEC = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).<RegistryFriendlyByteBuf>cast().map(LevelSaveResult::new, LevelSaveResult::problems);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
