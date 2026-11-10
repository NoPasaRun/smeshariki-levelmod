package com.smeshariki.level.task;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.phys.Vec3;

/** Дойти до точки. */
public record ReachTask(Vec3 pos, double radius) implements Task {
	static final String TYPE = "reach";
	static final MapCodec<ReachTask> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Vec3.CODEC.fieldOf("pos").forGetter(ReachTask::pos),
			Codec.doubleRange(0, 4096).optionalFieldOf("radius", 2.0).forGetter(ReachTask::radius)
	).apply(i, ReachTask::new));

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public int goal() {
		return 1;
	}

	@Override
	public Estimate estimate(@Nullable Vec3 from) {
		double distance = from == null ? ActivityTimes.UNKNOWN_DISTANCE : Math.max(0, from.distanceTo(pos) - radius);
		return Estimate.fixed(ActivityTimes.walkTicks(distance));
	}

	@Override
	public Vec3 endPosition() {
		return pos;
	}

	@Override
	public void tick(TaskTracker tracker) {
		for (UUID player : tracker.players()) {
			if (tracker.position(player).distanceToSqr(pos) <= radius * radius) tracker.credit(player, 1);
		}
	}
}
