package com.smeshariki.level.task;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.phys.Vec3;

/** Добраться до области: до джунглей, до луга. */
public record ReachAreaTask(TaskArea target) implements Task {
	static final String TYPE = "reach_area";
	static final MapCodec<ReachAreaTask> MAP_CODEC = TaskArea.CODEC.fieldOf("target").xmap(ReachAreaTask::new, ReachAreaTask::target);

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
		return Estimate.fixed(ActivityTimes.walkTicks(from == null ? ActivityTimes.UNKNOWN_DISTANCE : target.contains(from) ? 0 : target.distanceTo(from)));
	}

	@Override
	public Vec3 endPosition() {
		return target.center();
	}

	@Override
	public void tick(TaskTracker tracker) {
		for (UUID player : tracker.players()) {
			if (tracker.isInside(player, target)) tracker.credit(player, 1);
		}
	}
}
