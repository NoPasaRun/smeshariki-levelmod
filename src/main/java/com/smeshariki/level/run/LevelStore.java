package com.smeshariki.level.run;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.smeshariki.level.LevelMod;
import com.smeshariki.level.task.TaskArea;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

/**
 * Состояние уровней в мире, файл {@code data/levelmod/levels.dat}: идущие прогоны, статистика завершённых и уровни из редактора. Лежит среди файлов мира,
 * поэтому попадает в версии мира и откатывается вместе с ним.
 */
public final class LevelStore extends SavedData {
	private static final Codec<LevelStore> CODEC = RecordCodecBuilder.create(i -> i.group(
			LevelRun.CODEC.listOf().fieldOf("runs").forGetter(s -> s.runs),
			Codec.unboundedMap(Identifier.CODEC, LevelStats.CODEC).fieldOf("stats").forGetter(s -> s.stats),
			Codec.unboundedMap(Identifier.CODEC, Codec.STRING).optionalFieldOf("custom_levels", Map.of()).forGetter(s -> Map.of()),
			Identifier.CODEC.listOf().optionalFieldOf("hidden_levels", List.of()).forGetter(s -> List.of()),
			Codec.unboundedMap(Codec.STRING, Vec3.CODEC).optionalFieldOf("location_points", Map.of()).forGetter(s -> s.placesInFile ? Map.of() : s.locationPoints),
			Codec.unboundedMap(Codec.STRING, TaskArea.CODEC).optionalFieldOf("location_areas", Map.of()).forGetter(s -> s.placesInFile ? Map.of() : s.locationAreas),
			Codec.BOOL.optionalFieldOf("world_reset", false).forGetter(s -> s.worldReset),
			Codec.unboundedMap(Codec.STRING, Codec.LONG.listOf()).optionalFieldOf("touched_chunks", Map.of()).forGetter(s -> s.touchedForSave()),
			Codec.STRING.listOf().optionalFieldOf("groups", List.of()).forGetter(s -> s.placesInFile ? List.of() : List.copyOf(s.groups))
	).apply(i, LevelStore::new));
	// без типа датафикса ваниль падает при чтении файла; у этого типа в наших данных исправлять нечего
	private static final SavedDataType<LevelStore> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "levels"), LevelStore::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final List<LevelRun> runs;
	private final Map<Identifier, LevelStats> stats;
	/**
	 * Прежнее хранение уровней в мире: только чтение старых файлов, на диск не пишется. Уровни теперь в JSON-файлах ({@link com.smeshariki.level.definition.LevelFiles}),
	 * а эти поля нужны один раз — перенести старое содержимое.
	 */
	private final Map<Identifier, String> customLevels;
	private final Set<Identifier> hiddenLevels;
	private final Map<String, Vec3> locationPoints;
	private final Map<String, TaskArea> locationAreas;
	/** Уровень закончился, блоки мира ещё не возвращены к версии ({@link WorldReset}); переживает перезапуск. */
	private boolean worldReset;
	/** Чанки, где во время уровней были игроки (и области начатых миссий): откат мира возвращает только их. Измерение (строка) → упакованные {@link net.minecraft.world.level.ChunkPos}. */
	private final Map<String, Set<Long>> touched = new HashMap<>();
	/** Реестр групп объектов (имена): сами объекты хранят группу в своих данных, а здесь список, чтобы группа была видна и пустой. */
	private final Set<String> groups = new java.util.TreeSet<>();

	private LevelStore() {
		this(List.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(), false, Map.of(), List.of());
	}

	private LevelStore(List<LevelRun> runs, Map<Identifier, LevelStats> stats, Map<Identifier, String> customLevels, List<Identifier> hiddenLevels, Map<String, Vec3> locationPoints, Map<String, TaskArea> locationAreas, boolean worldReset, Map<String, List<Long>> touched, List<String> groups) {
		this.runs = new ArrayList<>(runs);
		this.stats = new HashMap<>(stats);
		this.customLevels = new HashMap<>(customLevels);
		this.hiddenLevels = new HashSet<>(hiddenLevels);
		this.locationPoints = new HashMap<>(locationPoints);
		this.locationAreas = new HashMap<>(locationAreas);
		this.worldReset = worldReset;
		touched.forEach((dimension, chunks) -> this.touched.put(dimension, new HashSet<>(chunks)));
		this.groups.addAll(groups);
	}

	/** Реестр групп: изменяется на месте, после правки вызвать {@link #setDirty()}. */
	public Set<String> groups() {
		return groups;
	}

	private Map<String, List<Long>> touchedForSave() {
		Map<String, List<Long>> out = new HashMap<>();
		touched.forEach((dimension, chunks) -> out.put(dimension, new ArrayList<>(chunks)));
		return out;
	}

	/** Отметить чанк как тронутый уровнем. */
	public void touch(String dimension, long chunk) {
		if (touched.computeIfAbsent(dimension, d -> new HashSet<>()).add(chunk)) setDirty();
	}

	/** Отдать тронутые чанки откату и забыть их; пусто — неизвестно, откат идёт по всему миру. */
	public Map<String, Set<Long>> takeTouched() {
		Map<String, Set<Long>> out = new HashMap<>(touched);
		if (!out.isEmpty()) {
			touched.clear();
			setDirty();
		}
		return out;
	}

	public boolean worldResetPending() {
		return worldReset;
	}

	public void setWorldResetPending(boolean pending) {
		if (worldReset == pending) return;
		worldReset = pending;
		setDirty();
	}

	/** Старые уровни из редактора, лежавшие в файле мира: отдаются один раз и забываются. */
	public Map<Identifier, String> takeLegacyCustom() {
		Map<Identifier, String> out = new HashMap<>(customLevels);
		if (!out.isEmpty()) {
			customLevels.clear();
			setDirty();
		}
		return out;
	}

	/** Старый список скрытых заготовок: отдаётся один раз и забывается. */
	public List<Identifier> takeLegacyHidden() {
		List<Identifier> out = List.copyOf(hiddenLevels);
		if (!out.isEmpty()) {
			hiddenLevels.clear();
			setDirty();
		}
		return out;
	}

	/** Локации мира: файлы уровней ссылаются на них по имени ({@code "@имя"}). */
	public Map<String, Vec3> locationPoints() {
		return locationPoints;
	}

	public Map<String, TaskArea> locationAreas() {
		return locationAreas;
	}

	public void resetStats(Identifier level) {
		stats.remove(level);
	}

	public static LevelStore get(MinecraftServer server) {
		LevelStore store = server.getDataStorage().computeIfAbsent(TYPE);
		if (!store.bound) store.bind(server);
		return store;
	}

	// ---------------------------------------------------------------- локации и группы: файл вне мира

	/** Файл локаций и реестра групп этого мира; пока он не подключён (или не вышло прочитать), всё лежит в {@code levels.dat}, как раньше. */
	private boolean bound, placesInFile;
	private java.nio.file.Path placesFile;
	private String lastPlaces = "";

	private static final com.google.gson.Gson PLACES_GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/**
	 * Локации и реестр групп живут в файле {@code <папка игры>/data/levelmod/worlds/<папка мира>/places.json}: вне мира, поэтому откат версии мира их не трогает.
	 * Данные, лежавшие в {@code levels.dat}, переезжают один раз: сначала копия {@code levels.dat.pre_places.bak}, потом запись файла и проверка чтением;
	 * только после этого из {@code levels.dat} они перестают писаться. Если файл не вышло записать или прочитать, всё остаётся как было.
	 */
	private void bind(MinecraftServer server) {
		bound = true;
		java.nio.file.Path world = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
		String name = world.getFileName() == null ? "world" : world.getFileName().toString();
		placesFile = com.smeshariki.level.ModData.moved(com.smeshariki.level.ModData.game("levelmod", "worlds"), com.smeshariki.level.ModData.dir().resolve("worlds")).resolve(name).resolve("places.json");
		// мир из архива (инструмент экспорта ManagementMod): локации лежат в самом мире, а у получателя файла по имени его папки ещё нет
		java.nio.file.Path bundled = world.resolve("levelmod_places.json");
		if (!java.nio.file.Files.exists(placesFile) && java.nio.file.Files.isRegularFile(bundled)) {
			try {
				java.nio.file.Files.createDirectories(placesFile.getParent());
				java.nio.file.Files.copy(bundled, placesFile);
				LevelMod.LOGGER.info("Локации и группы взяты из мира: {}", bundled);
			} catch (java.io.IOException e) {
				LevelMod.LOGGER.warn("Не удалось взять локации из {}", bundled, e);
			}
		}
		try {
			boolean legacy = !locationPoints.isEmpty() || !locationAreas.isEmpty() || !groups.isEmpty();
			if (java.nio.file.Files.exists(placesFile)) {
				if (legacy) LevelMod.LOGGER.warn("Локации и группы есть и в мире, и в файле {}: берутся из файла", placesFile);
				readPlaces();
			} else if (legacy) {
				java.nio.file.Path dat = world.resolve("data").resolve(LevelMod.NAMESPACE).resolve("levels.dat");
				java.nio.file.Path backup = dat.resolveSibling("levels.dat.pre_places.bak");
				if (java.nio.file.Files.exists(dat) && !java.nio.file.Files.exists(backup)) java.nio.file.Files.copy(dat, backup);
				String text = serializePlaces();
				writePlacesText(text);
				// проверка: прочитанное из файла совпадает с тем, что было в памяти
				Map<String, Vec3> points = new HashMap<>(locationPoints);
				Map<String, TaskArea> areas = new HashMap<>(locationAreas);
				Set<String> names = new HashSet<>(groups);
				readPlaces();
				if (!points.equals(locationPoints) || !areas.keySet().equals(locationAreas.keySet()) || !names.equals(groups)) throw new java.io.IOException("файл локаций прочитан не так, как записан");
				LevelMod.LOGGER.warn("Локации ({}), области ({}) и группы ({}) перенесены из мира в {}; копия levels.dat — {}", points.size(), areas.size(), names.size(), placesFile, backup);
			}
			placesInFile = true;
			if (legacy) setDirty();
		} catch (java.io.IOException | RuntimeException e) {
			LevelMod.LOGGER.error("Локации и группы остаются в мире: не вышло работать с файлом {}", placesFile, e);
			placesInFile = false;
		}
	}

	private String serializePlaces() {
		com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		com.google.gson.JsonObject points = new com.google.gson.JsonObject(), areas = new com.google.gson.JsonObject();
		new java.util.TreeMap<>(locationPoints).forEach((n, p) -> points.add(n, Vec3.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, p).getOrThrow()));
		new java.util.TreeMap<>(locationAreas).forEach((n, a) -> areas.add(n, TaskArea.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, a).getOrThrow()));
		com.google.gson.JsonArray names = new com.google.gson.JsonArray();
		new java.util.TreeSet<>(groups).forEach(names::add);
		root.add("points", points);
		root.add("areas", areas);
		root.add("groups", names);
		return PLACES_GSON.toJson(root);
	}

	private void writePlacesText(String text) throws java.io.IOException {
		java.nio.file.Files.createDirectories(placesFile.getParent());
		java.nio.file.Path tmp = placesFile.resolveSibling("places.json.tmp");
		java.nio.file.Files.writeString(tmp, text, java.nio.charset.StandardCharsets.UTF_8);
		java.nio.file.Files.move(tmp, placesFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		lastPlaces = text;
	}

	private void readPlaces() throws java.io.IOException {
		String text = java.nio.file.Files.readString(placesFile, java.nio.charset.StandardCharsets.UTF_8);
		com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
		Map<String, Vec3> points = new HashMap<>();
		Map<String, TaskArea> areas = new HashMap<>();
		Set<String> names = new java.util.TreeSet<>();
		if (root.has("points")) for (var e : root.getAsJsonObject("points").entrySet()) points.put(e.getKey(), Vec3.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, e.getValue()).getOrThrow());
		if (root.has("areas")) for (var e : root.getAsJsonObject("areas").entrySet()) areas.put(e.getKey(), TaskArea.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, e.getValue()).getOrThrow());
		if (root.has("groups")) for (var e : root.getAsJsonArray("groups")) names.add(e.getAsString());
		locationPoints.clear();
		locationPoints.putAll(points);
		locationAreas.clear();
		locationAreas.putAll(areas);
		groups.clear();
		groups.addAll(names);
		lastPlaces = text;
	}

	/** Записать локации и группы в файл, если они изменились; без файла (сбой при подключении) — в {@code levels.dat}. Звать после каждой правки. */
	public void savePlaces() {
		if (!placesInFile) {
			setDirty();
			return;
		}
		try {
			String text = serializePlaces();
			if (!text.equals(lastPlaces)) writePlacesText(text);
		} catch (java.io.IOException | RuntimeException e) {
			LevelMod.LOGGER.error("Не записались локации и группы в {}", placesFile, e);
		}
	}

	public List<LevelRun> runs() {
		return runs;
	}

	public @Nullable LevelRun runOf(Identifier level) {
		for (LevelRun run : runs) if (run.level().equals(level)) return run;
		return null;
	}

	/** Все прогоны игрока: сюжетный и побочные. */
	public List<LevelRun> runsWith(UUID player) {
		return runs.stream().filter(run -> run.hasParticipant(player)).toList();
	}

	public LevelStats statsOf(Identifier level) {
		return stats.getOrDefault(level, LevelStats.EMPTY);
	}

	/** Убирает закончившиеся прогоны; в статистику идут только завершённые на 100%. */
	public void sweep() {
		runs.removeIf(run -> switch (run.state()) {
			case COMPLETED -> {
				stats.put(run.level(), statsOf(run.level()).plus(run.elapsed()));
				yield true;
			}
			case ABANDONED -> true;
			default -> false;
		});
	}
}
