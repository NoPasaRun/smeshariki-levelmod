package com.smeshariki.level.definition;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.smeshariki.level.task.ConditionEntry;

import net.minecraft.util.ExtraCodecs;

/**
 * Наблюдатель: когда у любого участника в сети разом верны все проверки, случается {@code custom:<id>} (один раз за прогон). Так триггеры
 * узнают о том, что не задача: игроки дошли до пещеры — пустить первую волну лавы.
 *
 * @param after с какого события наблюдать; пусто — с начала уровня
 * @param delay через сколько тиков после того, как проверки сошлись, случится событие (проверки к тому времени могут и разойтись)
 */
public record Watch(String id, List<ConditionEntry> conditions, Optional<LevelEvent> after, int delay) {
	public static final Codec<Watch> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("id").forGetter(Watch::id),
			ExtraCodecs.nonEmptyList(ConditionEntry.CODEC.listOf()).fieldOf("conditions").forGetter(Watch::conditions),
			LevelEvent.CODEC.optionalFieldOf("after").forGetter(Watch::after),
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("delay", 0).forGetter(Watch::delay)
	).apply(i, Watch::new));

	public LevelEvent event() {
		return LevelEvent.custom(id);
	}
}
