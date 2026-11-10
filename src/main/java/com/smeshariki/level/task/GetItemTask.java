package com.smeshariki.level.task;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.component.DataComponentExactPredicate;
import com.mojang.serialization.DataResult;
import com.smeshariki.gameplay.api.Mechanics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Получить предмет: считается прибавка в инвентаре и одежде персонажа с начала задачи, как бы он туда ни попал. Предметы не забираются.
 * Сюжетный предмет без кода — обычный предмет с компонентами ({@code components}: модель, название), например
 * {@code "item": "minecraft:paper", "components": {"minecraft:item_model": "levelmod:shovel_box"}}: засчитываются только такие. Любой из
 * группы — тег: {@code "item": "#minecraft:shovels"}.
 */
public record GetItemTask(String item, DataComponentExactPredicate components, int count) implements Task {
	static final String TYPE = "get_item";
	/** Предмет или тег ({@code #}): проверяется через мост, как в проверках механик. */
	static final Codec<String> ITEM_CODEC = Codec.STRING.validate(text -> Mechanics.item(text) != null ? DataResult.success(text) : DataResult.error(() -> "Нет такого предмета: " + text));
	static final MapCodec<GetItemTask> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			ITEM_CODEC.fieldOf("item").forGetter(GetItemTask::item),
			DataComponentExactPredicate.CODEC.optionalFieldOf("components", DataComponentExactPredicate.EMPTY).forGetter(GetItemTask::components),
			Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1).forGetter(GetItemTask::count)
	).apply(i, GetItemTask::new));

	public boolean matches(ItemStack stack) {
		var match = Mechanics.item(item);
		return match != null && match.test(stack) && components.test(stack);
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public int goal() {
		return count;
	}

	@Override
	public Estimate estimate(@Nullable Vec3 from) {
		return Estimate.shared(count * ActivityTimes.GET_ITEM_TICKS);
	}

	@Override
	public void tick(TaskTracker tracker) {
		for (UUID player : tracker.players()) tracker.setCredit(player, tracker.gained(player, this::matches));
	}
}
