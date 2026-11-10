package com.smeshariki.level.task;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Сломать блоки одного вида. */
public record MineBlockTask(Block block, int count) implements Task {
	static final String TYPE = "mine_block";
	static final MapCodec<MineBlockTask> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			BuiltInRegistries.BLOCK.byNameCodec().fieldOf("block").forGetter(MineBlockTask::block),
			Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1).forGetter(MineBlockTask::count)
	).apply(i, MineBlockTask::new));

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
		return Estimate.shared(count * (ActivityTimes.breakTicks(block) + ActivityTimes.NEXT_BLOCK_TICKS));
	}

	@Override
	public void onSignal(TaskSignal signal, TaskTracker tracker) {
		if (signal instanceof TaskSignal.BlockMined(var player, Block mined) && mined == block) tracker.credit(player, 1);
	}
}
