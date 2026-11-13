package com.smeshariki.level.run;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.level.task.TaskArea;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/**
 * Зачистка области миссии после сброса от выпавших предметов и шаров опыта: сразу (пока идёт возврат блоков) и ещё раз, когда блоки и ячейки вернулись и
 * прошло немного времени (то, что выпало при возврате). Только в памяти: это секунды.
 */
final class AreaCleanup {
	/** Через сколько тиков после конца возврата зачистка повторяется. */
	private static final int SECOND_PASS = 40;
	/** Дольше этого зачистка не ждёт конца возврата, тиков. */
	private static final int LIMIT = 1200;

	private static final class Job {
		final TaskArea area;
		int age, doneAt = -1;

		Job(TaskArea area) {
			this.area = area;
		}
	}

	private static final List<Job> JOBS = new ArrayList<>();

	private AreaCleanup() {
	}

	/** Сброс области заказан: убрать предметы сейчас и после возврата. */
	static void request(MinecraftServer server, TaskArea area) {
		purge(server, area);
		for (Job job : JOBS) if (job.area.equals(area)) {
			job.age = 0;
			job.doneAt = -1;
			return;
		}
		JOBS.add(new Job(area));
	}

	static void tick(MinecraftServer server) {
		if (JOBS.isEmpty()) return;
		boolean idle = AreaReset.idle() && ResetCells.idle();
		for (java.util.Iterator<Job> it = JOBS.iterator(); it.hasNext();) {
			Job job = it.next();
			job.age++;
			if (job.doneAt < 0 && (idle || job.age > LIMIT)) {
				purge(server, job.area);
				job.doneAt = job.age;
			} else if (job.doneAt >= 0 && job.age >= job.doneAt + SECOND_PASS) {
				purge(server, job.area);
				it.remove();
			}
		}
	}

	private static void purge(MinecraftServer server, TaskArea area) {
		ServerLevel level = server.getLevel(area.dimension());
		if (level == null) return;
		double[] b = area.boundsXZ();
		AABB box = new AABB(b[0], level.getMinY(), b[1], b[2], level.getMaxY() + 1, b[3]);
		for (Entity entity : level.getEntities((Entity) null, box, e -> (e instanceof ItemEntity || e instanceof ExperienceOrb) && area.contains(e.position()))) entity.discard();
	}

	static void clear() {
		JOBS.clear();
	}
}
