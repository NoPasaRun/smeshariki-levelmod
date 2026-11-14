package com.smeshariki.level.task;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Чекпоинт миссии: место (точка или область), куда возвращает сброс. Порядок в списке миссии — приоритет: достигнутый чекпоинт важнее всех до него, поэтому
 * игрок, добравшийся до второго, после гибели вернётся на второй, а не на первый. Первый — начало миссии (в начале миссии всех переносят туда), последний — её конец;
 * последний чекпоинт миссии может быть первым у следующей.
 * <p>
 * В файле — просто место ({@code "@имя"}) или объект {@code {"at": место, "character": тип, "min_y": …, "max_y": …}}: {@code character} — чекпоинт только для этого
 * персонажа (у каждого свой дом), {@code reset} — ячейки отката ({@link ResetCell}, до 8), {@code on_enter} — действие при входе в чекпоинт (не в первый), {@code reset_wait} — сколько секунд игрок стоит после отката на этот чекпоинт (нет — берётся {@code reset_wait} миссии), {@code min_y} и {@code max_y} ограничивают высоту, где он засчитывается (область по высоте не ограничена, а чекпоинт может быть под землёй).
 */
public record Checkpoint(Either<Vec3, TaskArea> place, Optional<Identifier> character, Optional<Double> minY, Optional<Double> maxY, java.util.List<ResetCell> resets, Optional<com.smeshariki.level.definition.LevelAction> onEnter, Optional<Integer> resetWait) {
	public static final Codec<Either<Vec3, TaskArea>> PLACE = Codec.either(Vec3.CODEC, TaskArea.CODEC);
	private static final Codec<Checkpoint> OBJECT = RecordCodecBuilder.create(i -> i.group(
			PLACE.fieldOf("at").forGetter(Checkpoint::place),
			Identifier.CODEC.optionalFieldOf("character").forGetter(Checkpoint::character),
			Codec.DOUBLE.optionalFieldOf("min_y").forGetter(Checkpoint::minY),
			Codec.DOUBLE.optionalFieldOf("max_y").forGetter(Checkpoint::maxY),
			ResetCell.CODEC.listOf().validate(list -> list.size() <= ResetCell.MAX ? com.mojang.serialization.DataResult.success(list) : com.mojang.serialization.DataResult.error(() -> "у чекпоинта не больше " + ResetCell.MAX + " ячеек отката")).optionalFieldOf("reset", java.util.List.of()).forGetter(Checkpoint::resets),
			com.smeshariki.level.definition.LevelAction.CODEC.optionalFieldOf("on_enter").forGetter(Checkpoint::onEnter),
			Codec.intRange(0, 60).optionalFieldOf("reset_wait").forGetter(Checkpoint::resetWait)
	).apply(i, Checkpoint::new));
	public static final Codec<Checkpoint> CODEC = Codec.withAlternative(OBJECT, PLACE.xmap(place -> new Checkpoint(place, Optional.empty(), Optional.empty(), Optional.empty(), java.util.List.of(), Optional.empty(), Optional.empty()), Checkpoint::place));

	/** Касается ли чекпоинт персонажа: общий — всех, свой — только этого. */
	public boolean appliesTo(@Nullable Identifier role) {
		return character.isEmpty() || character.get().equals(role);
	}

	/** Подходит ли высота. */
	public boolean heightFits(double y) {
		return (minY.isEmpty() || y >= minY.get()) && (maxY.isEmpty() || y <= maxY.get());
	}
}
