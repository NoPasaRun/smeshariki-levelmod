package com.smeshariki.level.task;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.smeshariki.level.run.LevelStats;

import net.minecraft.world.phys.Vec3;

/** Продержаться заданное время. */
public record WaitTask(int ticks) implements Task {
	static final String TYPE = "wait";
	static final MapCodec<WaitTask> MAP_CODEC = Codec.intRange(1, Integer.MAX_VALUE).fieldOf("ticks").xmap(WaitTask::new, WaitTask::ticks);

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public int goal() {
		return ticks;
	}

	@Override
	public Estimate estimate(@Nullable Vec3 from) {
		return Estimate.fixed(ticks);
	}

	@Override
	public void tick(TaskTracker tracker) {
		tracker.advance(1);
	}

	@Override
	public String counter(int progress) {
		return LevelStats.format(ticks - progress);
	}
}
