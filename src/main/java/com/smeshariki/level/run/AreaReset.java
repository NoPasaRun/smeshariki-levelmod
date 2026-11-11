package com.smeshariki.level.run;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.smeshariki.level.LevelMod;
import com.smeshariki.level.task.TaskArea;

import net.minecraft.util.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * Блоки области миссии — к текущей версии мира (BuildMod, V): при сбросе задачи миссии и в конце уровня. Версия области читается в фоне в
 * начале миссии ({@link #prepare}); возврат ставит отличающиеся блоки только в столбцах внутри области понемногу каждый тик. Только в памяти:
 * это секунды, а после перезапуска версия прочтётся заново при первом сбросе.
 */
final class AreaReset {
	private static final long BUDGET_NANOS = 6_000_000;

	private record Job(ServerLevel level, BlockRestore.ChunkPlan plan, TaskArea area) {
	}

	private static final Map<TaskArea, CompletableFuture<List<BlockRestore.ChunkPlan>>> VERSIONS = new HashMap<>();
	/** Области, сброс которых заказан, а версия ещё читается. */
	private static final Set<TaskArea> WAITING = new HashSet<>();
	private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
	private static boolean placed;

	private AreaReset() {
	}

	/** Прочитать версию области заново (в начале миссии: версию могли сохранить после прошлой). */
	static void prepare(MinecraftServer server, TaskArea area) {
		ServerLevel level = server.getLevel(area.dimension());
		if (level == null) return;
		Set<Long> chunks = new HashSet<>();
		double[] b = area.boundsXZ();
		for (int cx = (int) Math.floor(b[0]) >> 4; cx <= (int) Math.floor(b[2]) >> 4; cx++)
			for (int cz = (int) Math.floor(b[1]) >> 4; cz <= (int) Math.floor(b[3]) >> 4; cz++) chunks.add(ChunkPos.pack(cx, cz));
		Path root = server.getWorldPath(LevelResource.ROOT);
		VERSIONS.put(area, CompletableFuture.supplyAsync(() -> {
			try {
				return BlockRestore.scanChunks(root, level, area.dimension(), chunks);
			} catch (java.io.IOException e) {
				throw new RuntimeException(e);
			}
		}, Util.backgroundExecutor()));
	}

	/** Вернуть блоки области к версии. */
	static void reset(MinecraftServer server, TaskArea area) {
		if (!VERSIONS.containsKey(area)) prepare(server, area);
		WAITING.add(area);
	}

	static void tick(MinecraftServer server) {
		for (TaskArea area : List.copyOf(WAITING)) {
			CompletableFuture<List<BlockRestore.ChunkPlan>> version = VERSIONS.get(area);
			if (version == null || !version.isDone()) continue;
			WAITING.remove(area);
			List<BlockRestore.ChunkPlan> plans;
			try {
				plans = version.join();
			} catch (RuntimeException e) {
				LevelMod.LOGGER.error("Не прочиталась версия области миссии", e);
				continue;
			}
			ServerLevel level = server.getLevel(area.dimension());
			if (level != null) for (BlockRestore.ChunkPlan plan : plans) QUEUE.add(new Job(level, plan, area));
		}
		if (QUEUE.isEmpty()) return;
		long deadline = System.nanoTime() + BUDGET_NANOS;
		while (!QUEUE.isEmpty() && System.nanoTime() < deadline) {
			Job job = QUEUE.poll();
			placed |= BlockRestore.restoreChunk(job.level(), job.plan().data(), (x, z) -> job.area().contains(new Vec3(x + 0.5, 0, z + 0.5))) > 0;
		}
		// вернувшийся блок мог встать на игрока
		if (QUEUE.isEmpty() && placed) {
			placed = false;
			for (ServerPlayer player : server.getPlayerList().getPlayers()) BlockRestore.unstick(player);
		}
	}

	static void clear() {
		VERSIONS.clear();
		WAITING.clear();
		QUEUE.clear();
		placed = false;
	}
}
