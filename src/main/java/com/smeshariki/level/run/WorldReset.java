package com.smeshariki.level.run;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.smeshariki.level.LevelMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Откат блоков мира к текущей версии (после проверки — в областях проверяемой миссии, после уровня — в сумме областей его миссий, только внутри контуров:
 * {@link LevelStore#touch}, {@code LevelManager.takeResetAreas}; без отметок отката нет) (BuildMod, V) после конца любого уровня. Отметка «откат ждёт» лежит в {@link LevelStore} и переживает перезапуск;
 * откат начинается, когда не идёт ни один прогон. Версия читается в фоне по областям порциями, блоки ставятся на сервере понемногу каждый тик: по одному чанку
 * за раз, с проверкой времени после каждого, иначе загрузка десятков чанков подряд за один тик вешала сервер на десятки секунд. Версию после разметки и
 * постройки нужно сохранять (V): откат вернёт мир к ней. Тем же движком идёт откат из окна версий V ({@link #beginManual}: весь мир, сущности тоже).
 */
final class WorldReset {
	/** Время на откат за тик: пока откат ждёт, идущих уровней нет, поэтому можно взять до 20 мс из 50; в мире без игроков — 40. */
	private static final long BUDGET_NANOS = 20_000_000, BUDGET_EMPTY_NANOS = 40_000_000;
	private static final int BATCH = 48;
	private static final List<BlockRestore.ChunkPlan> END = new ArrayList<>();

	private static final class Job {
		final BlockingQueue<List<BlockRestore.ChunkPlan>> queue = new LinkedBlockingQueue<>(3);
		/** Чанки уже принятой порции, ещё не возвращённые: сервер ставит их по одному, сколько позволяет бюджет тика. */
		final ArrayDeque<BlockRestore.ChunkPlan> pending = new ArrayDeque<>();
		volatile boolean failed;
		volatile boolean cancelled;
		int restored, chunks;
		/** Чанков в версии всего (по областям) и сколько уже передано серверу: из этого считается процент. */
		volatile int total;
		/** Сущности чанков возвращаются тоже (только когда откат идёт по отмеченным чанкам, не по всему миру). */
		volatile boolean entities;
		/** Сущности чанков, ждущих загрузки с обработкой сущностей. */
		final ArrayDeque<EntityTask> entityTasks = new ArrayDeque<>();
		final java.util.concurrent.atomic.AtomicInteger handed = new java.util.concurrent.atomic.AtomicInteger();
		/** Контуры по измерению: возвращаются только столбцы и сущности внутри них. Нет контуров у измерения — чанки целиком (откат V, после перезапуска). */
		Map<String, Set<com.smeshariki.level.task.TaskArea>> areas = Map.of();

		boolean inside(ServerLevel level, double x, double z) {
			Set<com.smeshariki.level.task.TaskArea> list = areas.get(level.dimension().identifier().toString());
			if (list == null || list.isEmpty()) return true;
			net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(x, 0, z);
			for (com.smeshariki.level.task.TaskArea area : list) if (area.contains(at)) return true;
			return false;
		}
	}

	private static Job job;

	/** Чанк, у которого блоки уже возвращены, а сущности ждут: загрузка принудительная, чтобы старые сущности были видны и убрались до появления новых с тем же UUID. */
	private static final class EntityTask {
		final ServerLevel level;
		final ChunkPos pos;
		final List<net.minecraft.nbt.CompoundTag> tags;
		final java.util.function.BiPredicate<Double, Double> inside;
		long ready;
		int attempts;
		final boolean forced;

		EntityTask(ServerLevel level, ChunkPos pos, List<net.minecraft.nbt.CompoundTag> tags, java.util.function.BiPredicate<Double, Double> inside, long ready) {
			this.level = level;
			this.pos = pos;
			this.tags = tags;
			this.inside = inside;
			this.ready = ready;
			this.forced = level.setChunkForced(pos.x(), pos.z(), true);
		}

		void release() {
			if (forced) level.setChunkForced(pos.x(), pos.z(), false);
		}
	}

	/** Не больше стольких чанков ждут сущностей одновременно: каждый держит принудительную загрузку. */
	private static final int MAX_WAITING = 24;
	private static boolean rerun;
	private static int lastPercent = -1;
	/** Откат по просьбе из окна версий (V, SmesharikiMod): весь мир по версии, с сущностями, без отметки в {@link LevelStore}; идёт, пока {@code manual}. Только в памяти. */
	private static boolean manual;
	private static java.util.function.IntConsumer manualProgress;
	private static java.util.function.Consumer<String> manualDone;
	/** Откат областей перед началом уровня или проверки ({@link #prepare}): свои чанки, отметки после уровней не трогает. Только в памяти. */
	private static boolean prestart;
	private static Runnable prestartDone;

	/** Сколько процентов отката сделано: {@code -1} — отката нет, 0 — ждёт конца прогонов или читает версию, до 99 — идёт, пока не вернулся последний чанк. */
	static int percent(MinecraftServer server) {
		if (!busy(server)) return -1;
		Job j = job;
		if (j == null || j.total <= 0) return 0;
		return Math.min(99, (int) ((long) j.handed.get() * 100 / j.total));
	}

	private static void notifyProgress(MinecraftServer server) {
		int now = percent(server);
		if (now != lastPercent) {
			lastPercent = now;
			LevelManager.requestSync();
			if (manual && manualProgress != null && now >= 0) manualProgress.accept(now);
		}
	}

	private WorldReset() {
	}

	/** Заказать откат: уровень закончился. */
	static void request(MinecraftServer server) {
		LevelStore store = LevelStore.get(server);
		if (store.worldResetPending()) {
			// уровень закончился, пока откат идёт: после него нужен ещё один
			if (job != null) rerun = true;
			return;
		}
		store.setWorldResetPending(true);
	}

	static boolean busy(MinecraftServer server) {
		return manual || prestart || LevelStore.get(server).worldResetPending();
	}

	/**
	 * Откат областей к версии перед началом уровня или проверки (2026-11-20, по просьбе): только внутри контуров, блоки и сущности, сразу, не дожидаясь конца
	 * других прогонов. Отметки отката после уровней ({@link LevelStore#touch}) не трогает. {@code done} зовётся, когда откат кончился.
	 *
	 * @return false — сейчас идёт другой откат, звать позже
	 */
	static boolean prepare(MinecraftServer server, Map<String, Set<com.smeshariki.level.task.TaskArea>> areas, Runnable done) {
		if (manual || prestart || job != null) return false;
		prestart = true;
		prestartDone = done;
		start(server, false, LevelManager.chunksOf(areas), areas);
		if (job == null) {
			// нечего возвращать
			prestart = false;
			prestartDone = null;
			done.run();
		}
		return true;
	}

	/**
	 * Откат всего мира к текущей версии по просьбе из окна версий: то же, что после уровня (по чанку за тик с бюджетом, блоки и сущности), но по всем чанкам версии.
	 * Чанки, созданные после версии (их в ней нет), не трогаются. Нельзя, пока идёт другой откат или уровень.
	 *
	 * @return причина отказа или {@code null}, если начат
	 */
	static String beginManual(MinecraftServer server, java.util.function.IntConsumer progress, java.util.function.Consumer<String> finished) {
		LevelStore store = LevelStore.get(server);
		if (manual || job != null || store.worldResetPending()) return "Уже идёт откат мира: дождитесь его конца";
		if (store.runs().stream().anyMatch(run -> run.state() == LevelRun.State.RUNNING) || LevelManager.testing()) return "Идёт уровень или проверка: завершите их, потом откатывайте мир";
		// диск должен совпадать с памятью: по нему пропускаются не менявшиеся с версии чанки
		server.saveAllChunks(true, true, true);
		manual = true;
		manualProgress = progress;
		manualDone = finished;
		start(server, true, null, null);
		return null;
	}

	static void tick(MinecraftServer server) {
		tickJob(server);
		notifyProgress(server);
	}

	private static void tickJob(MinecraftServer server) {
		LevelStore store = LevelStore.get(server);
		if (!manual && !prestart && !store.worldResetPending()) return;
		if (job == null) {
			// ждёт конца всех идущих прогонов и проверок
			if (store.runs().stream().anyMatch(run -> run.state() == LevelRun.State.RUNNING) || LevelManager.testing()) return;
			start(server, false, null, null);
			if (job == null) {
				store.setWorldResetPending(false);
				return;
			}
		}
		long deadline = System.nanoTime() + (server.getPlayerList().getPlayers().isEmpty() ? BUDGET_EMPTY_NANOS : BUDGET_NANOS);
		// сущности чанков, у которых прошло время на загрузку
		while (!job.entityTasks.isEmpty() && System.nanoTime() < deadline) {
			EntityTask task = job.entityTasks.peek();
			if (server.getTickCount() < task.ready) break;
			job.entityTasks.poll();
			int failed = EntityRestore.apply(task.level, task.pos, task.tags, task.inside);
			if (failed > 0 && ++task.attempts < 4) {
				task.ready = server.getTickCount() + 5;
				job.entityTasks.add(task);
			} else {
				task.release();
			}
		}
		do {
			if (job.entityTasks.size() >= MAX_WAITING) return;
			BlockRestore.ChunkPlan plan = job.pending.poll();
			if (plan == null) {
				List<BlockRestore.ChunkPlan> batch = job.queue.peek();
				if (batch == null) return;
				if (batch == END) {
					if (job.entityTasks.isEmpty()) finish(server, store);
					return;
				}
				job.queue.poll();
				job.pending.addAll(batch);
				continue;
			}
			ServerLevel level = server.getLevel(plan.dimension());
			if (level == null) continue;
			// с версии не менялся и не загружен (в памяти мог измениться): ни блоки, ни сущности трогать не нужно
			if (plan.unchanged() && level.getChunkSource().getChunkNow(plan.pos().x(), plan.pos().z()) == null) continue;
			Job current = job;
			job.restored += BlockRestore.restoreChunk(level, plan.data(), (x, z) -> current.inside(level, x + 0.5, z + 0.5));
			job.chunks++;
			if (job.entities) job.entityTasks.add(new EntityTask(level, plan.pos(), plan.entities(), (x, z) -> current.inside(level, x, z), server.getTickCount() + 3));
		} while (System.nanoTime() < deadline);
	}

	/** @param givenChunks и {@code given} — свои чанки и контуры (подготовка перед стартом); {@code null} — отметки после уровней из {@link LevelStore}. */
	private static void start(MinecraftServer server, boolean full, Map<String, Set<Long>> givenChunks, Map<String, Set<com.smeshariki.level.task.TaskArea>> given) {
		Path root = server.getWorldPath(LevelResource.ROOT);
		List<ResourceKey<Level>> dimensions = new ArrayList<>();
		for (ServerLevel level : server.getAllLevels()) dimensions.add(level.dimension());
		// чанки областей миссий; ничего не отмечено (у миссий нет областей) — откатывать нечего
		Map<String, Set<Long>> touched = full ? Map.of() : givenChunks != null ? givenChunks : LevelStore.get(server).takeTouched();
		Map<String, Set<com.smeshariki.level.task.TaskArea>> areas = full ? Map.of() : given != null ? given : LevelManager.takeResetAreas();
		if (!full && touched.isEmpty()) return;
		Job started = new Job();
		started.entities = true;
		started.areas = areas;
		job = started;
		Util.backgroundExecutor().execute(() -> {
			try {
				Map<String, String> files = BlockRestore.versionFiles(root);
				// полный откат по просьбе: диск уже сброшен (beginManual), чанки, не менявшиеся с версии, сверяются с ней и пропускаются
				BlockRestore.LiveFiles live = full ? new BlockRestore.LiveFiles(root) : null;
				// область (регион) → чанки, которые в ней надо вернуть; без отметок — все 1024 чанка каждой области версии
				Map<ResourceKey<Level>, Map<Long, List<Long>>> work = new HashMap<>();
				int all = 0;
				for (ResourceKey<Level> dimension : files == null ? List.<ResourceKey<Level>>of() : dimensions) {
					Map<Long, List<Long>> byRegion = new HashMap<>();
					Set<Long> marked = touched.get(dimension.identifier().toString());
					if (full) {
						for (long region : BlockRestore.versionRegions(root, dimension)) {
							int rx = ChunkPos.getX(region), rz = ChunkPos.getZ(region);
							List<Long> keys = new ArrayList<>();
							for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) keys.add(ChunkPos.pack((rx << 5) + x, (rz << 5) + z));
							byRegion.put(region, keys);
						}
					} else if (marked != null) {
						for (long chunk : marked) byRegion.computeIfAbsent(ChunkPos.pack(ChunkPos.getX(chunk) >> 5, ChunkPos.getZ(chunk) >> 5), r -> new ArrayList<>()).add(chunk);
					}
					for (List<Long> keys : byRegion.values()) all += keys.size();
					work.put(dimension, byRegion);
				}
				started.total = all;
				for (var entry : work.entrySet()) {
					ResourceKey<Level> dimension = entry.getKey();
					ServerLevel level = server.getLevel(dimension);
					if (level == null) continue;
					for (List<Long> keys : entry.getValue().values()) {
						// область читается с диска один раз на все её порции
						Map<Long, byte[]> cache = new HashMap<>();
						for (int from = 0; from < keys.size(); from += BATCH) {
							if (started.cancelled) return;
							Set<Long> chunks = new HashSet<>(keys.subList(from, Math.min(keys.size(), from + BATCH)));
							List<BlockRestore.ChunkPlan> plans = BlockRestore.scanChunks(root, files, level, dimension, chunks, cache, live);
							if (!plans.isEmpty()) put(started, plans);
							started.handed.addAndGet(chunks.size());
						}
					}
				}
			} catch (Exception e) {
				started.failed = true;
				LevelMod.LOGGER.error("Не прочиталась версия мира для отката", e);
			}
			put(started, END);
		});
	}

	private static void put(Job target, List<BlockRestore.ChunkPlan> plans) {
		try {
			while (!target.cancelled && !target.queue.offer(plans, 100, TimeUnit.MILLISECONDS)) {
				// сервер не успевает ставить — ждём
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void finish(MinecraftServer server, LevelStore store) {
		boolean failed = job.failed;
		java.util.function.Consumer<String> done = manual ? manualDone : null;
		Runnable prepared = prestart ? prestartDone : null;
		job = null;
		if (prestart) {
			prestart = false;
			prestartDone = null;
		} else if (manual) {
			manual = false;
			manualProgress = null;
			manualDone = null;
		} else {
			store.setWorldResetPending(rerun);
			rerun = false;
		}
		LevelManager.requestSync();
		// вернувшийся блок мог встать на игрока
		for (ServerPlayer player : server.getPlayerList().getPlayers()) BlockRestore.unstick(player);
		if (done != null) done.accept(failed ? "версия мира не прочиталась, подробности в логе" : null);
		if (prepared != null) prepared.run();
	}

	static void clear() {
		if (job != null) {
			job.cancelled = true;
			for (EntityTask task : job.entityTasks) task.release();
		}
		job = null;
		manual = false;
		manualProgress = null;
		manualDone = null;
		prestart = false;
		prestartDone = null;
	}
}
