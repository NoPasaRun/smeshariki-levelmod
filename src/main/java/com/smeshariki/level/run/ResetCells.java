package com.smeshariki.level.run;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.smeshariki.gameplay.raft.BalloonSnapshots;
import com.smeshariki.level.LevelMod;
import com.smeshariki.level.task.ResetCell;
import com.smeshariki.level.task.TaskArea;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Ячейки отката чекпоинта ({@link ResetCell}): блоки и сущности нужного вида в области миссии. {@link #take} запоминает их в NBT, {@link #restore} возвращает:
 * блоки встают на свои места (с содержимым хранилищ), лишние блоки этого вида исчезают, сущности пересоздаются с теми же UUID, а появившиеся позже удаляются.
 * Блоки ставятся по времени (понемногу каждый тик), и только когда блоки области уже вернулись к версии мира ({@link AreaReset#idle}): иначе версия затёрла бы их.
 */
final class ResetCells {
	/** Больше позиций одного вида блока не запоминаем (проход по области слишком тяжёл для одного чекпоинта). */
	private static final int MAX_BLOCKS = 300_000;
	private static final long BUDGET_NANOS = 6_000_000;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	private record Op(ServerLevel level, long pos, BlockState state, CompoundTag blockEntity) {
	}

	private static final ArrayDeque<Op> QUEUE = new ArrayDeque<>();
	/** Заказанные возвраты, ждущие, пока блоки области вернутся к версии: (измерение, снимок). */
	private static final List<Pending> WAITING = new ArrayList<>();
	private static boolean placed;

	private record Pending(ServerLevel level, TaskArea area, CompoundTag tag) {
	}

	private ResetCells() {
	}

	private static AABB box(ServerLevel level, TaskArea area) {
		double[] b = area.boundsXZ();
		return new AABB(b[0], level.getMinY(), b[1], b[2], level.getMaxY() + 1, b[3]);
	}

	private static BlockState state(CompoundTag tag) {
		return BlockState.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(Blocks.AIR.defaultBlockState());
	}

	/** Что запомнить из области: по каждой ячейке свой список. {@code null} нельзя: пустой набор тоже снимок (ничего не возвращать). */
	static CompoundTag take(ServerLevel level, TaskArea area, List<ResetCell> cells) {
		CompoundTag out = new CompoundTag();
		ListTag blocks = new ListTag(), entities = new ListTag();
		for (ResetCell cell : cells) {
			if (cell.kind() == ResetCell.Kind.BLOCK) {
				Block block = BuiltInRegistries.BLOCK.getOptional(cell.type()).orElse(null);
				if (block == null || block == Blocks.AIR) continue;
				CompoundTag one = takeBlocks(level, area, block);
				if (one != null) blocks.add(one);
			} else {
				EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(cell.type()).orElse(null);
				if (type == null) continue;
				CompoundTag one = new CompoundTag();
				one.putString("type", cell.type().toString());
				ListTag list = new ListTag();
				for (Entity entity : level.getEntities((Entity) null, box(level, area), e -> e.getType() == type && accepts(e) && area.contains(e.position()))) {
					CompoundTag tag = BalloonSnapshots.saveEntity(level, entity);
					if (tag != null) list.add(tag);
				}
				one.put("list", list);
				entities.add(one);
			}
		}
		out.put("blocks", blocks);
		out.put("entities", entities);
		return out;
	}

	/** Игроков и смешариков (вселённых, чужих) не трогаем: их место определяет прогон. */
	private static boolean accepts(Entity e) {
		return !e.isRemoved() && !(e instanceof net.minecraft.world.entity.player.Player) && !(e instanceof com.smeshariki.gameplay.character.CharacterEntity);
	}

	private static CompoundTag takeBlocks(ServerLevel level, TaskArea area, Block block) {
		List<BlockState> palette = new ArrayList<>();
		java.util.Map<BlockState, Integer> index = new java.util.HashMap<>();
		List<Long> positions = new ArrayList<>();
		List<Integer> states = new ArrayList<>();
		ListTag blockEntities = new ListTag();
		double[] b = area.boundsXZ();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int cx = (int) Math.floor(b[0]) >> 4; cx <= (int) Math.floor(b[2]) >> 4; cx++) {
			for (int cz = (int) Math.floor(b[1]) >> 4; cz <= (int) Math.floor(b[3]) >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				for (int s = 0; s < chunk.getSectionsCount(); s++) {
					LevelChunkSection section = chunk.getSection(s);
					if (!section.maybeHas(st -> st.is(block))) continue;
					int baseY = chunk.getSectionYFromSectionIndex(s) * 16;
					for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
						BlockState st = section.getBlockState(x, y, z);
						if (!st.is(block)) continue;
						pos.set(cx * 16 + x, baseY + y, cz * 16 + z);
						if (!area.contains(new Vec3(pos.getX() + 0.5, 0, pos.getZ() + 0.5))) continue;
						if (positions.size() >= MAX_BLOCKS) {
							LevelMod.LOGGER.warn("Ячейка отката «{}»: больше {} блоков в области, ячейка пропущена", BuiltInRegistries.BLOCK.getKey(block), MAX_BLOCKS);
							return null;
						}
						Integer i = index.get(st);
						if (i == null) {
							i = palette.size();
							palette.add(st);
							index.put(st, i);
						}
						positions.add(pos.asLong());
						states.add(i);
						if (st.hasBlockEntity()) {
							BlockEntity entity = chunk.getBlockEntity(pos);
							if (entity != null) {
								CompoundTag tag = entity.saveWithFullMetadata(level.registryAccess());
								tag.putLong("_pos", pos.asLong());
								blockEntities.add(tag);
							}
						}
					}
				}
			}
		}
		CompoundTag one = new CompoundTag();
		one.putString("type", BuiltInRegistries.BLOCK.getKey(block).toString());
		ListTag paletteTag = new ListTag();
		for (BlockState st : palette) paletteTag.add(BlockState.CODEC.encodeStart(NbtOps.INSTANCE, st).getOrThrow());
		one.put("palette", paletteTag);
		one.put("pos", new LongArrayTag(positions.stream().mapToLong(Long::longValue).toArray()));
		one.put("state", new IntArrayTag(states.stream().mapToInt(Integer::intValue).toArray()));
		one.put("be", blockEntities);
		return one;
	}

	/** Вернуть ячейки к снимку: сущности сразу, блоки — когда область вернётся к версии, и по времени. */
	static void restore(ServerLevel level, TaskArea area, CompoundTag tag) {
		for (Tag t : tag.getListOrEmpty("entities")) if (t instanceof CompoundTag one) restoreEntities(level, area, one);
		if (!tag.getListOrEmpty("blocks").isEmpty()) WAITING.add(new Pending(level, area, tag));
	}

	private static void restoreEntities(ServerLevel level, TaskArea area, CompoundTag one) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(one.getStringOr("type", ""))).orElse(null);
		if (type == null) return;
		ListTag list = one.getListOrEmpty("list");
		Set<UUID> kept = new HashSet<>();
		for (Tag t : list) if (t instanceof CompoundTag c) c.read("UUID", net.minecraft.core.UUIDUtil.CODEC).ifPresent(kept::add);
		// появившееся после снимка этого вида — лишнее
		for (Entity entity : level.getEntities((Entity) null, box(level, area), e -> e.getType() == type && accepts(e) && area.contains(e.position()))) {
			if (!kept.contains(entity.getUUID())) entity.discard();
		}
		if (list.isEmpty()) return;
		CompoundTag snapshot = new CompoundTag();
		snapshot.put("Entities", list);
		BalloonSnapshots.restore(level, snapshot);
	}

	/** Составить операции возврата блоков одного вида: сохранённое — на места, найденное сейчас, но не сохранённое — воздух. */
	private static void plan(ServerLevel level, TaskArea area, CompoundTag one) {
		Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(one.getStringOr("type", ""))).orElse(null);
		if (block == null) return;
		List<BlockState> palette = new ArrayList<>();
		for (Tag t : one.getListOrEmpty("palette")) palette.add(t instanceof CompoundTag c ? state(c) : Blocks.AIR.defaultBlockState());
		long[] positions = one.getLongArray("pos").orElse(new long[0]);
		int[] states = one.getIntArray("state").orElse(new int[0]);
		java.util.Map<Long, CompoundTag> blockEntities = new java.util.HashMap<>();
		for (Tag t : one.getListOrEmpty("be")) if (t instanceof CompoundTag c) blockEntities.put(c.getLongOr("_pos", 0), c);
		LongSet saved = new LongSet(positions);
		// найденное сейчас, чего не было в снимке
		double[] b = area.boundsXZ();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int cx = (int) Math.floor(b[0]) >> 4; cx <= (int) Math.floor(b[2]) >> 4; cx++) {
			for (int cz = (int) Math.floor(b[1]) >> 4; cz <= (int) Math.floor(b[3]) >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				for (int s = 0; s < chunk.getSectionsCount(); s++) {
					LevelChunkSection section = chunk.getSection(s);
					if (!section.maybeHas(st -> st.is(block))) continue;
					int baseY = chunk.getSectionYFromSectionIndex(s) * 16;
					for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
						if (!section.getBlockState(x, y, z).is(block)) continue;
						pos.set(cx * 16 + x, baseY + y, cz * 16 + z);
						if (!saved.contains(pos.asLong()) && area.contains(new Vec3(pos.getX() + 0.5, 0, pos.getZ() + 0.5))) QUEUE.add(new Op(level, pos.asLong(), Blocks.AIR.defaultBlockState(), null));
					}
				}
			}
		}
		for (int i = 0; i < positions.length && i < states.length; i++) {
			if (states[i] < 0 || states[i] >= palette.size()) continue;
			BlockState want = palette.get(states[i]);
			BlockState live = level.getBlockState(BlockPos.of(positions[i]));
			CompoundTag be = want.hasBlockEntity() ? blockEntities.get(positions[i]) : null;
			// то же блок и тот же вид — возвращаем только если изменилось состояние или нужно содержимое хранилища
			if (live == want && be == null) continue;
			QUEUE.add(new Op(level, positions[i], want, be));
		}
	}

	/** Набор позиций для быстрой проверки. */
	private static final class LongSet {
		private final it.unimi.dsi.fastutil.longs.LongOpenHashSet set = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

		LongSet(long[] values) {
			for (long v : values) set.add(v);
		}

		boolean contains(long v) {
			return set.contains(v);
		}
	}

	static void tick(MinecraftServer server) {
		if (!WAITING.isEmpty() && AreaReset.idle()) {
			for (Pending p : List.copyOf(WAITING)) for (Tag t : p.tag().getListOrEmpty("blocks")) if (t instanceof CompoundTag one) plan(p.level(), p.area(), one);
			WAITING.clear();
		}
		if (QUEUE.isEmpty()) return;
		long deadline = System.nanoTime() + BUDGET_NANOS;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		while (!QUEUE.isEmpty() && System.nanoTime() < deadline) {
			Op op = QUEUE.poll();
			pos.set(op.pos());
			if (op.level().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) continue;
			op.level().setBlock(pos, op.state(), FLAGS);
			placed = true;
			if (op.blockEntity() == null) continue;
			BlockEntity entity = BlockEntity.loadStatic(pos.immutable(), op.state(), op.blockEntity(), op.level().registryAccess());
			if (entity == null) continue;
			op.level().setBlockEntity(entity);
			op.level().sendBlockUpdated(pos, op.state(), op.state(), Block.UPDATE_CLIENTS);
		}
		if (QUEUE.isEmpty() && placed) {
			placed = false;
			for (ServerPlayer player : server.getPlayerList().getPlayers()) BlockRestore.unstick(player);
		}
	}

	/** Нет ни ждущих, ни идущих возвратов ячеек. */
	static boolean idle() {
		return WAITING.isEmpty() && QUEUE.isEmpty();
	}

	static void clear() {
		QUEUE.clear();
		WAITING.clear();
		placed = false;
	}
}
