package com.smeshariki.level.task;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.phys.Vec3;

/**
 * Состояние: проверки ({@link ConditionEntry}) вроде «в воде», «надеты очки и есть 3 яблока», «на шаре», «держит стропу высоты». Какие проверки бывают,
 * знают механики, а не уровень. {@code together} (по умолчанию да) — нужно, чтобы все были верны разом; иначе каждая засчитывается, когда стала верной
 * хотя бы раз, в любом порядке (стропа висит под корзиной: взять её и залезть в шар разом нельзя), а продвижение — сколько уже было. Однажды выполненная
 * задача засчитана, даже если состояние потом прошло.
 */
public record ConditionTask(List<ConditionEntry> conditions, boolean together) implements Task {
	static final String TYPE = "condition";
	static final MapCodec<ConditionTask> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			ExtraCodecs.nonEmptyList(ConditionEntry.CODEC.listOf()).fieldOf("conditions").forGetter(ConditionTask::conditions),
			Codec.BOOL.optionalFieldOf("together", true).forGetter(ConditionTask::together)
	).apply(i, ConditionTask::new));

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public int goal() {
		return together ? 1 : conditions.size();
	}

	@Override
	public Estimate estimate(@Nullable Vec3 from) {
		return Estimate.fixed(ActivityTimes.CONDITION_TICKS);
	}

	@Override
	public void tick(TaskTracker tracker) {
		for (UUID player : tracker.players()) {
			if (together) {
				if (conditions.stream().allMatch(c -> tracker.check(player, c))) tracker.credit(player, 1);
				continue;
			}
			// какие проверки уже были верны: по биту на проверку, помнит прогон
			int seen = tracker.marks(player);
			for (int i = 0; i < conditions.size() && i < 31; i++) if ((seen & (1 << i)) == 0 && tracker.check(player, conditions.get(i))) seen |= 1 << i;
			tracker.setMarks(player, seen);
			tracker.setCredit(player, Integer.bitCount(seen));
		}
	}
}
