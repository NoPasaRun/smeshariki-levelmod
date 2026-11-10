package com.smeshariki.level.net;

import java.util.Optional;

import com.smeshariki.level.LevelMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Проверить из редактора задачу или миссию: JSON миссии, локации по имени. Пусто — остановить проверку. {@code level} — уровень, из которого проверка
 * (его персонажи, триггеры и наблюдатели; за персонажей, которыми никто не играет, идут напарники; пусто у несохранённого).
 */
public record LevelTestTask(Optional<String> missionJson, Optional<Identifier> level) implements CustomPacketPayload {
	public LevelTestTask(Optional<String> missionJson) {
		this(missionJson, Optional.empty());
	}

	public static final Type<LevelTestTask> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "level_test_task"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LevelTestTask> CODEC = StreamCodec.composite(
			ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(LevelList.MAX_JSON)), LevelTestTask::missionJson,
			ByteBufCodecs.optional(Identifier.STREAM_CODEC), LevelTestTask::level,
			LevelTestTask::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
