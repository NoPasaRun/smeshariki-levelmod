package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.level.net.LevelList;
import com.smeshariki.level.run.LevelRun;
import com.smeshariki.level.task.TaskArea;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Показывает искрами границу рядом с игроком: идущих задач, проверяемой задачи и той, что открыта в редакторе. За выход из области отвечает
 * {@code LevelRun}. Граница по высоте бесконечна, поэтому искры появляются на высоте игрока.
 */
final class AreaClient {
	private static final double SHOW_DISTANCE = 6;

	private AreaClient() {
	}

	static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || mc.level.getGameTime() % 2 != 0) return;
		List<TaskArea> shown = activeAreas(mc);
		shown.addAll(ClientLevels.testAreas);
		if (ClientLevels.shownLocation != null) shown.add(ClientLevels.shownLocation);
		TaskDraft task = ClientLevels.draftTask;
		if (ClientLevels.draft != null && task != null) {
			for (String name : List.of(task.area, task.target)) {
				for (LevelList.LocationInfo info : ClientLevels.locations.marked()) if (info.name().equals(name)) info.area().ifPresent(shown::add);
			}
		}
		for (TaskArea area : shown) if (area.dimension().equals(mc.level.dimension())) sparks(mc, area);
	}

	private static List<TaskArea> activeAreas(Minecraft mc) {
		List<TaskArea> areas = new ArrayList<>();
		for (LevelList.LevelInfo level : ClientLevels.levels) {
			var run = level.run().orElse(null);
			if (run == null || run.state() != LevelRun.State.RUNNING || run.participants().stream().noneMatch(p -> p.id().equals(mc.player.getUUID()))) continue;
			for (LevelList.TaskInfo task : level.tasks()) if (task.started() && !task.completed()) task.area().ifPresent(areas::add);
		}
		return areas;
	}

	private static void sparks(Minecraft mc, TaskArea area) {
		Vec3 p = mc.player.position();
		var random = mc.level.getRandom();
		for (List<TaskArea.Point> loop : area.loops()) {
			for (int i = 0; i < loop.size(); i++) {
				TaskArea.Point a = loop.get(i), b = loop.get((i + 1) % loop.size());
				double dx = b.x() - a.x(), dz = b.z() - a.z(), lengthSqr = dx * dx + dz * dz;
				if (lengthSqr < 1e-6) continue;
				// ближайшая к игроку точка отрезка; искра ложится рядом с ней
				double t = Math.max(0, Math.min(1, ((p.x - a.x()) * dx + (p.z - a.z()) * dz) / lengthSqr));
				double x = a.x() + t * dx, z = a.z() + t * dz;
				if (Math.hypot(x - p.x, z - p.z) > SHOW_DISTANCE) continue;
				double along = (random.nextDouble() - 0.5) * 2 * SHOW_DISTANCE / Math.sqrt(lengthSqr);
				double s = Math.max(0, Math.min(1, t + along));
				mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, a.x() + s * dx, p.y + (random.nextDouble() - 0.5) * 2 * SHOW_DISTANCE, a.z() + s * dz, 0, 0, 0);
			}
		}
	}
}
