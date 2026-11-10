package com.smeshariki.level.definition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.JsonOps;

import com.smeshariki.behavior.cutscene.Cutscenes;
import com.smeshariki.gameplay.character.Possession;
import com.smeshariki.level.LevelMod;
import com.smeshariki.level.run.LevelStore;
import com.smeshariki.level.task.TaskArea;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * Загруженные из файлов ({@link LevelFiles}) уровни и кат-сцены. Уровень в файле ссылается на локации мира строкой {@code "@имя"}, поэтому пересобирается и при их правке.
 * Это кэш ресурсов, а не состояние мира: он перечитывается при каждой загрузке данных.
 * Уровни анализируются после загрузки обоих каталогов, поэтому порядок их чтения не важен.
 */
public final class Levels {
	/** @param expectedTicks ожидаемая длительность для минимального числа игроков */
	public record Loaded(Identifier id, LevelDefinition definition, long expectedTicks) {
	}

	private static Map<Identifier, JsonElement> packLevels = Map.of();

	/** Есть ли у уровня заготовка в моде (датапак {@code data/<мод>/level/*.json}): тогда можно вернуть исходный вид. */
	public static boolean hasTemplate(Identifier id) {
		return packLevels.containsKey(id);
	}

	/** Заготовка мода для уровня. */
	public static @Nullable JsonElement template(Identifier id) {
		return packLevels.get(id);
	}

	/** Автор менял уровень: файл отличается от заготовки мода. */
	public static boolean overridesTemplate(Identifier id) {
		JsonElement template = packLevels.get(id);
		return template != null && LevelFiles.differsFrom(id, template);
	}
	private static List<String> problems = List.of();
	private static List<String> missingLocations = List.of();
	private static final Set<String> TEXT_KEYS = Set.of("title", "description", "text");
	private static Map<Identifier, Loaded> levels = Map.of();
	private static List<Loaded> sorted = List.of();

	private Levels() {
	}

	public static void init() {
		ResourceLoader loader = ResourceLoader.get(PackType.SERVER_DATA);
		// файлы хранятся как есть: разобрать их можно только вместе с локациями мира, а те приходят позже и меняются в игре
		loader.registerReloadListener(Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "levels"), new SimpleJsonResourceReloadListener<>(ExtraCodecs.JSON, FileToIdConverter.json("level")) {
			@Override
			protected void apply(Map<Identifier, JsonElement> loaded, ResourceManager manager, ProfilerFiller profiler) {
				packLevels = loaded;
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(Levels::rebuild);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> rebuild(server));
	}

	/** Анализ развёрнутого уровня с текущими кат-сценами. */
	public static LevelAnalyzer.Result analyze(LevelDefinition level) {
		LevelAnalyzer.Result result = LevelAnalyzer.analyze(level, id -> {
			int length = Cutscenes.length(id);
			return length < 0 ? null : length;
		}, level.minPlayers());
		List<String> problems = new ArrayList<>(result.problems());
		for (Identifier character : level.characters()) {
			if (Possession.choices().stream().noneMatch(type -> EntityType.getKey(type).equals(character))) problems.add("нет такого персонажа: " + character);
		}
		return new LevelAnalyzer.Result(result.expectedTicks(), problems);
	}

	/** Перечитывает уровни из файлов ({@link LevelFiles}: заготовки мода копируются туда при первой загрузке) и пересобирает список. */
	public static void rebuild(MinecraftServer server) {
		LevelStore store = LevelStore.get(server);
		LevelFiles.sync(server, packLevels, store);
		Set<String> missing = new TreeSet<>();
		List<String> errors = new ArrayList<>();
		Map<Identifier, LevelDefinition> all = new HashMap<>();
		Map<Identifier, JsonElement> sources = new HashMap<>();
		LevelFiles.all().forEach((id, json) -> sources.put(id, JsonParser.parseString(json)));
		sources.forEach((id, json) -> {
			Set<String> own = new TreeSet<>();
			Parsed parsed = parse(json, store, own);
			missing.addAll(own);
			if (parsed.level() != null) all.put(id, parsed.level());
			else errors.add(id + ": " + parsed.problems());
		});
		Map<Identifier, Loaded> checked = new HashMap<>();
		for (var entry : all.entrySet()) {
			LevelDefinition level = entry.getValue();
			LevelAnalyzer.Result result = analyze(level);
			if (result.problems().isEmpty()) checked.put(entry.getKey(), new Loaded(entry.getKey(), level, result.expectedTicks()));
			else errors.add(entry.getKey() + ": " + result.problems());
		}
		for (String error : errors) LevelMod.LOGGER.error("Уровень не загружен: {}", error);
		problems = List.copyOf(errors);
		missingLocations = List.copyOf(missing);
		levels = checked;
		List<Loaded> list = new ArrayList<>(checked.values());
		list.sort(Comparator.<Loaded, LevelKind>comparing(l -> l.definition().kind()).thenComparingInt(l -> l.definition().order()).thenComparing(Loaded::id));
		sorted = List.copyOf(list);
	}

	/** Разобранный файл уровня: либо уровень, либо что не так. */
	public record Parsed(@Nullable LevelDefinition level, List<String> problems) {
	}

	/** Разбирает JSON уровня с подстановкой локаций. */
	public static Parsed parse(JsonElement json, LevelStore store, Set<String> missing) {
		Set<String> own = new TreeSet<>();
		JsonElement resolved = resolve(LegacyCheckpoints.migrate(json), store, own);
		missing.addAll(own);
		if (!own.isEmpty()) return new Parsed(null, List.of("не отмечены локации " + own));
		var result = LevelDefinition.CODEC.parse(JsonOps.INSTANCE, resolved);
		return result.result().map(level -> new Parsed(level, List.of())).orElseGet(() -> new Parsed(null, List.of(result.error().orElseThrow().message())));
	}

	/** Подставляет локации вместо строк {@code "@имя"}: точка становится координатами, область — контуром. Тексты не трогает. */
	public static JsonElement resolve(JsonElement json, LevelStore store, Set<String> missing) {
		if (json.isJsonObject()) {
			JsonObject out = new JsonObject();
			for (var entry : json.getAsJsonObject().entrySet()) {
				// подсказка направления необязательна: пока место не отмечено, уровень грузится без неё
				if (entry.getKey().equals("guide") && entry.getValue().isJsonPrimitive() && entry.getValue().getAsString().startsWith("@")
						&& !store.locationAreas().containsKey(entry.getValue().getAsString().substring(1)) && !store.locationPoints().containsKey(entry.getValue().getAsString().substring(1))) continue;
				out.add(entry.getKey(), TEXT_KEYS.contains(entry.getKey()) ? entry.getValue() : resolve(entry.getValue(), store, missing));
			}
			return out;
		}
		if (json.isJsonArray()) {
			JsonArray out = new JsonArray();
			for (JsonElement element : json.getAsJsonArray()) out.add(resolve(element, store, missing));
			return out;
		}
		if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString() || !json.getAsString().startsWith("@")) return json;
		String name = json.getAsString().substring(1);
		if (store.locationAreas().containsKey(name)) return TaskArea.CODEC.encodeStart(JsonOps.INSTANCE, store.locationAreas().get(name)).getOrThrow();
		if (store.locationPoints().containsKey(name)) return Vec3.CODEC.encodeStart(JsonOps.INSTANCE, store.locationPoints().get(name)).getOrThrow();
		missing.add(name);
		return json;
	}

	/** JSON уровня для редактора: содержимое его файла. */
	public static @Nullable String source(Identifier id) {
		return LevelFiles.get(id);
	}

	/** Что не загрузилось и почему: автору уровня. */
	public static List<String> problems() {
		return problems;
	}

	/** Какие локации просят файлы уровней, а в мире их нет. */
	public static List<String> missingLocations() {
		return missingLocations;
	}

	public static List<Loaded> all() {
		return sorted;
	}

	public static Loaded get(Identifier id) {
		return levels.get(id);
	}

	public static List<Identifier> cutsceneIds() {
		return Cutscenes.ids().stream().toList();
	}

	public static int cutsceneDuration(Identifier id) {
		return Math.max(0, Cutscenes.length(id));
	}
}
