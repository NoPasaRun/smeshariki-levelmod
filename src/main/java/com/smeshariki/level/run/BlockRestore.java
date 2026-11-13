package com.smeshariki.level.run;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.mod.versions.WorldVersions;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

/**
 * Блоки из текущей версии мира (SmesharikiMod, клавиша V) для откатов ({@link AreaReset}, {@link WorldReset}): чтение чанков версии и возврат блоков
 * в живом мире, без сущностей. Версия читается через {@link WorldVersions}: формата файлов истории этот мод не знает.
 */
public final class BlockRestore {
	/** Без соседних обновлений, выпадения и побочных действий: падающий песок и текущая вода не должны портить восстановленное. */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
	private static final int SECTOR = 4096;

	/**
	 * Снимок чанка из версии. {@code unchanged} — блоки (состояния, биомы, содержимое блоков-сущностей) и сущности чанка на диске те же, что в версии: такой чанк
	 * можно не трогать, если он сейчас не загружен (загруженный мог измениться в памяти). Сравнивается содержимое, а не байты записи: после загрузки игра пересохраняет
	 * чанк с новым временем обновления и картами высот, и побайтно не совпадал ни один. Известно только при сравнении с диском ({@link LiveFiles}).
	 */
	public static final class ChunkPlan {
		private final ResourceKey<Level> dimension;
		private final Level level;
		private final net.minecraft.world.level.ChunkPos pos;
		private final CompoundTag tag;
		private final List<CompoundTag> entities;
		private final boolean unchanged;
		private SerializableChunkData data;

		/** Неизменившийся чанк разбирается лениво (только если он понадобится), остальные сразу: фон не тратит время на то, что пропустят. */
		ChunkPlan(ResourceKey<Level> dimension, Level level, int chunkX, int chunkZ, CompoundTag tag, List<CompoundTag> entities, boolean unchanged) {
			this.dimension = dimension;
			this.level = level;
			this.pos = new net.minecraft.world.level.ChunkPos(chunkX, chunkZ);
			this.tag = tag;
			this.entities = entities;
			this.unchanged = unchanged;
			if (!unchanged) data();
		}

		public ResourceKey<Level> dimension() {
			return dimension;
		}

		public net.minecraft.world.level.ChunkPos pos() {
			return pos;
		}

		public List<CompoundTag> entities() {
			return entities;
		}

		public boolean unchanged() {
			return unchanged;
		}

		public synchronized SerializableChunkData data() {
			if (data == null) data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
			return data;
		}
	}

	/**
	 * Файлы областей живого мира для сравнения с версией: читаются с диска и кэшируются, пока у файла то же время изменения и размер. Годится только после сброса
	 * чанков на диск ({@code saveAllChunks} с flush): иначе диск отстаёт от памяти.
	 */
	static final class LiveFiles {
		private record Entry(byte[] bytes, long mtime, long size) {
		}

		private final Path root;
		private final Map<String, Entry> cache = new HashMap<>();

		LiveFiles(Path root) {
			this.root = root;
		}

		/** Запись чанка живого мира; {@code null} — файла области или записи нет. */
		byte @Nullable [] payload(String rel, int index) throws IOException {
			Path file = root.resolve(rel);
			if (!java.nio.file.Files.isRegularFile(file)) {
				cache.remove(rel);
				return null;
			}
			long mtime = java.nio.file.Files.getLastModifiedTime(file).toMillis(), size = java.nio.file.Files.size(file);
			Entry entry = cache.get(rel);
			if (entry == null || entry.mtime != mtime || entry.size != size) {
				entry = new Entry(java.nio.file.Files.readAllBytes(file), mtime, size);
				cache.put(rel, entry);
			}
			return BlockRestore.payload(entry.bytes, index);
		}

		/** Содержимое чанка живого мира; {@code null} — файла области или записи нет, либо чанк слишком велик для сравнения (отдельный .mcc). */
		@Nullable CompoundTag tag(String rel, int index) throws IOException {
			byte[] payload = payload(rel, index);
			if (payload == null || (payload[0] & 0x80) != 0) return null;
			return inline(payload);
		}
	}

	private BlockRestore() {
	}

	/** Столбцы, которые можно трогать (мировые x, z). */
	@FunctionalInterface
	interface Columns {
		boolean allows(int x, int z);
	}

	/** Ставит блоки снимка в столбцах {@code columns} (область миссии) там, где живой чанк от него отличается; возвращает, сколько поставил. */
	static int restoreChunk(ServerLevel level, SerializableChunkData data, Columns columns) {
		LevelChunk chunk = level.getChunk(data.chunkPos().x(), data.chunkPos().z());
		Map<Long, CompoundTag> blockEntities = new HashMap<>();
		for (CompoundTag tag : data.blockEntities()) blockEntities.put(BlockPos.asLong(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0)), tag);
		int count = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (SerializableChunkData.SectionData section : data.sectionData()) {
			LevelChunkSection saved = section.chunkSection();
			int index = chunk.getSectionIndexFromSectionY(section.y());
			// записи вне высоты мира содержат только свет
			if (saved == null || index < 0 || index >= chunk.getSectionsCount()) continue;
			LevelChunkSection live = chunk.getSection(index);
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = saved.getBlockState(x, y, z);
						if (state == live.getBlockState(x, y, z) || !columns.allows(chunk.getPos().getBlockX(x), chunk.getPos().getBlockZ(z))) continue;
						pos.set(chunk.getPos().getBlockX(x), section.y() * 16 + y, chunk.getPos().getBlockZ(z));
						level.setBlock(pos, state, FLAGS);
						count++;
						CompoundTag tag = state.hasBlockEntity() ? blockEntities.get(pos.asLong()) : null;
						if (tag == null) continue;
						BlockEntity blockEntity = BlockEntity.loadStatic(pos.immutable(), state, tag, level.registryAccess());
						if (blockEntity != null) {
							level.setBlockEntity(blockEntity);
							level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
						}
					}
				}
			}
		}
		// блок на месте, а содержимое другое: иначе взятое из сундука при повторе не найти
		for (var entry : blockEntities.entrySet()) {
			pos.set(entry.getKey());
			if (!columns.allows(pos.getX(), pos.getZ())) continue;
			BlockEntity live = level.getBlockEntity(pos);
			if (live == null) continue;
			CompoundTag saved = entry.getValue().copy();
			saved.remove("keepPacked");
			if (saved.equals(live.saveWithFullMetadata(level.registryAccess()))) continue;
			BlockState state = level.getBlockState(pos);
			BlockEntity restoredEntity = BlockEntity.loadStatic(pos.immutable(), state, saved, level.registryAccess());
			if (restoredEntity == null) continue;
			level.setBlockEntity(restoredEntity);
			level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
			count++;
		}
		return count;
	}

	/** Вернувшийся блок мог встать на игрока: поднимаем его до свободного места. */
	static void unstick(ServerPlayer player) {
		Entity root = player.getRootVehicle();
		ServerLevel level = player.level();
		double y = root.getY();
		for (int up = 0; up < level.getHeight() && !level.noCollision(root, root.getBoundingBox().move(0, y - root.getY(), 0)); up++) y = Math.floor(y) + 1;
		if (y != root.getY()) LevelManager.moveTo(player, level, new net.minecraft.world.phys.Vec3(root.getX(), y, root.getZ()));
	}

	// ---------------------------------------------------------------- чтение версии

	/** Файлы текущей версии мира (путь → хеш); {@code null} — версии нет. Формат хранения знает SmesharikiMod, не этот мод. */
	static @Nullable Map<String, String> versionFiles(Path root) throws IOException {
		return WorldVersions.currentFiles(root);
	}

	/** Области (region-файлы) измерения в текущей версии: ключи {@link net.minecraft.world.level.ChunkPos#pack} с координатами области. */
	static List<Long> versionRegions(Path root, ResourceKey<Level> dimension) throws IOException {
		Map<String, String> files = versionFiles(root);
		List<Long> out = new ArrayList<>();
		if (files == null) return out;
		Identifier dim = dimension.identifier();
		String prefix = "dimensions/" + dim.getNamespace() + "/" + dim.getPath() + "/region/r.";
		for (String name : files.keySet()) {
			if (!name.startsWith(prefix) || !name.endsWith(".mca")) continue;
			String[] parts = name.substring(prefix.length(), name.length() - 4).split("\\.");
			if (parts.length == 2) out.add(net.minecraft.world.level.ChunkPos.pack(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
		}
		return out;
	}

	/**
	 * Чанки {@code chunks} измерения {@code dimension} из текущей версии — все, без отсева по файлам на диске: посреди уровня мир не сохранён,
	 * и решает сравнение с живым чанком при возврате. Пусто — версии нет; чанков, которых в версии нет, в списке нет.
	 */
	static List<ChunkPlan> scanChunks(Path root, Level level, ResourceKey<Level> dimension, Set<Long> chunks) throws IOException {
		Map<String, String> files = versionFiles(root);
		if (files == null) return List.of();
		return scanChunks(root, files, level, dimension, chunks, new HashMap<>());
	}

	/**
	 * То же с уже прочитанным списком файлов версии и общим кэшем областей: при чтении всего мира порциями каждая область читается с диска один раз,
	 * а не заново для каждой порции.
	 */
	static List<ChunkPlan> scanChunks(Path root, Map<String, String> files, Level level, ResourceKey<Level> dimension, Set<Long> chunks, Map<Long, byte[]> regions) throws IOException {
		return scanChunks(root, files, level, dimension, chunks, regions, null);
	}

	/** То же; с {@code live} ещё и сравнивает запись каждого чанка с диском (поле {@link ChunkPlan#unchanged}). */
	static List<ChunkPlan> scanChunks(Path root, Map<String, String> files, Level level, ResourceKey<Level> dimension, Set<Long> chunks, Map<Long, byte[]> regions, @Nullable LiveFiles live) throws IOException {
		Identifier dim = dimension.identifier();
		List<ChunkPlan> out = new ArrayList<>();
		for (long key : chunks) {
			int chunkX = net.minecraft.world.level.ChunkPos.getX(key), chunkZ = net.minecraft.world.level.ChunkPos.getZ(key);
			int regionX = chunkX >> 5, regionZ = chunkZ >> 5;
			String rel = "dimensions/" + dim.getNamespace() + "/" + dim.getPath() + "/region/r." + regionX + "." + regionZ + ".mca";
			if (!files.containsKey(rel)) continue;
			byte[] region = regions.get(net.minecraft.world.level.ChunkPos.pack(regionX, regionZ));
			if (region == null) {
				region = WorldVersions.read(root, files.get(rel));
				regions.put(net.minecraft.world.level.ChunkPos.pack(regionX, regionZ), region);
			}
			int index = (chunkX & 31) + (chunkZ & 31) * 32;
			byte[] payload = payload(region, index);
			if (payload == null) continue;
			// большой чанк (отдельный .mcc) сравнивать не берёмся
			CompoundTag tag = decode(payload, chunkX, chunkZ, rel, files, root);
			boolean same = live != null && sameBlocks(tag, live.tag(rel, index));
			if (SerializableChunkData.getChunkStatusFromTag(tag) != ChunkStatus.FULL) continue;
			if (NbtUtils.getDataVersion(tag, 0) != SharedConstants.getCurrentVersion().dataVersion().version()) throw new IOException("Версия сохранена в другой версии игры");
			boolean[] entitiesSame = {same};
			List<CompoundTag> entities = entitiesOf(root, files, regions, dim, chunkX, chunkZ, same ? live : null, entitiesSame);
			out.add(new ChunkPlan(dimension, level, chunkX, chunkZ, tag, entities, entitiesSame[0]));
		}
		return out;
	}

	/** Сущности чанка из версии (файл {@code entities/r.X.Z.mca}); пусто — в версии их в этом чанке нет. */
	private static List<CompoundTag> entitiesOf(Path root, Map<String, String> files, Map<Long, byte[]> regions, Identifier dim, int chunkX, int chunkZ, @Nullable LiveFiles live, boolean[] same) throws IOException {
		int regionX = chunkX >> 5, regionZ = chunkZ >> 5;
		int index = (chunkX & 31) + (chunkZ & 31) * 32;
		String rel = "dimensions/" + dim.getNamespace() + "/" + dim.getPath() + "/entities/r." + regionX + "." + regionZ + ".mca";
		if (!files.containsKey(rel)) {
			// в версии сущностей нет: чанк не менялся, только если их нет и на диске
			if (live != null) same[0] = live.tag(rel, index) == null;
			return List.of();
		}
		// отдельный ключ кэша: области блоков и сущностей лежат в одном кэше
		long key = net.minecraft.world.level.ChunkPos.pack(regionX, regionZ) ^ 0x9E3779B97F4A7C15L;
		byte[] region = regions.get(key);
		if (region == null) {
			region = WorldVersions.read(root, files.get(rel));
			regions.put(key, region);
		}
		byte[] payload = payload(region, index);
		if (live != null) {
			CompoundTag now = live.tag(rel, index);
			same[0] = payload == null ? now == null : sameEntities(decode(payload, chunkX, chunkZ, rel, files, root), now);
		}
		if (payload == null) return List.of();
		CompoundTag tag = decode(payload, chunkX, chunkZ, rel, files, root);
		if (NbtUtils.getDataVersion(tag, 0) != SharedConstants.getCurrentVersion().dataVersion().version()) throw new IOException("Версия сохранена в другой версии игры");
		List<CompoundTag> out = new ArrayList<>();
		for (net.minecraft.nbt.Tag entity : tag.getListOrEmpty("Entities")) if (entity instanceof CompoundTag c) out.add(c);
		return out;
	}

	/** Запись чанка в region-файле: байт сжатия и данные; {@code null} — чанка нет. */
	static byte @Nullable [] payload(byte[] region, int index) {
		int header = index * 4;
		if (header + 4 > region.length) return null;
		int sector = (region[header] & 0xFF) << 16 | (region[header + 1] & 0xFF) << 8 | region[header + 2] & 0xFF;
		if (sector == 0) return null;
		int start = sector * SECTOR;
		if (start + 5 > region.length) return null;
		int length = (region[start] & 0xFF) << 24 | (region[start + 1] & 0xFF) << 16 | (region[start + 2] & 0xFF) << 8 | region[start + 3] & 0xFF;
		if (length <= 0 || start + 4 + length > region.length) return null;
		return Arrays.copyOfRange(region, start + 4, start + 4 + length);
	}

	/** Содержимое записи чанка, лежащей прямо в файле области. */
	private static CompoundTag inline(byte[] payload) throws IOException {
		RegionFileVersion version = RegionFileVersion.fromId(payload[0] & 0x7F);
		if (version == null) throw new IOException("Неизвестное сжатие чанка: " + (payload[0] & 0x7F));
		try (InputStream in = version.wrap(new ByteArrayInputStream(payload, 1, payload.length - 1)); DataInputStream stream = new DataInputStream(in)) {
			return NbtIo.read(stream);
		}
	}

	/** Блоки двух записей одного чанка одинаковы: секции (высота, состояния блоков, биомы) и блоки-сущности; свет, карты высот и время обновления не в счёт. */
	private static boolean sameBlocks(CompoundTag version, @Nullable CompoundTag live) {
		if (live == null) return false;
		var a = version.getListOrEmpty("sections");
		var b = live.getListOrEmpty("sections");
		if (a.size() != b.size() || !version.getListOrEmpty("block_entities").equals(live.getListOrEmpty("block_entities"))) return false;
		for (int i = 0; i < a.size(); i++) {
			CompoundTag x = a.getCompoundOrEmpty(i), y = b.getCompoundOrEmpty(i);
			if (x.getByteOr("Y", (byte) 0) != y.getByteOr("Y", (byte) 0) || !java.util.Objects.equals(x.get("block_states"), y.get("block_states")) || !java.util.Objects.equals(x.get("biomes"), y.get("biomes"))) return false;
		}
		return true;
	}

	private static boolean sameEntities(CompoundTag version, @Nullable CompoundTag live) {
		return live != null && version.getListOrEmpty("Entities").equals(live.getListOrEmpty("Entities"));
	}

	private static CompoundTag decode(byte[] payload, int chunkX, int chunkZ, String regionRel, Map<String, String> files, Path root) throws IOException {
		int compression = payload[0] & 0x7F;
		byte[] data;
		int offset = 1;
		if ((payload[0] & 0x80) != 0) {
			// большой чанк лежит рядом отдельным файлом, который тоже есть в версии
			String external = regionRel.substring(0, regionRel.lastIndexOf('/') + 1) + "c." + chunkX + "." + chunkZ + ".mcc";
			if (!files.containsKey(external)) throw new IOException("В версии нет файла " + external);
			data = WorldVersions.read(root, files.get(external));
			offset = 0;
		} else {
			data = payload;
		}
		RegionFileVersion version = RegionFileVersion.fromId(compression);
		if (version == null) throw new IOException("Неизвестное сжатие чанка: " + compression);
		try (InputStream in = version.wrap(new ByteArrayInputStream(data, offset, data.length - offset)); DataInputStream stream = new DataInputStream(in)) {
			return NbtIo.read(stream);
		}
	}
}
