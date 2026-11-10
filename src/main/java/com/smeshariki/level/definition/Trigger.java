package com.smeshariki.level.definition;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.ExtraCodecs;

/**
 * Связь событий: когда случились все события {@code when}, через {@code delay} тиков выполняются действия {@code then}, а они
 * порождают новые события. Срабатывает один раз за прогон. «Любое из» записывается двумя триггерами на одно действие: задача и
 * так стартует только раз.
 * <p>
 * {@code repeat}: срабатывает каждый раз, когда снова случается одно из событий {@code when} (остальные уже были). Нужен событиям, которые повторяются,
 * например {@code gameplaymod:lava_wave_finished:a}: волна с одного берега кончилась — пустить с другого, и так по кругу.
 * <p>
 * {@code unless}: если за время {@code delay} случилось одно из этих событий, отложенные действия отменяются. Так пишется «через 10 с, если
 * шар не взлетел снова».
 */
public record Trigger(List<LevelEvent> when, int delay, List<LevelAction> then, boolean repeat, List<LevelEvent> unless) {
	public static final Codec<Trigger> CODEC = RecordCodecBuilder.create(i -> i.group(
			ExtraCodecs.nonEmptyList(ExtraCodecs.compactListCodec(LevelEvent.CODEC)).fieldOf("when").forGetter(Trigger::when),
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("delay", 0).forGetter(Trigger::delay),
			ExtraCodecs.compactListCodec(LevelAction.CODEC).fieldOf("then").forGetter(Trigger::then),
			Codec.BOOL.optionalFieldOf("repeat", false).forGetter(Trigger::repeat),
			ExtraCodecs.compactListCodec(LevelEvent.CODEC).optionalFieldOf("unless", List.of()).forGetter(Trigger::unless)
	).apply(i, Trigger::new));

	public Trigger(List<LevelEvent> when, int delay, List<LevelAction> then) {
		this(when, delay, then, false, List.of());
	}
}
