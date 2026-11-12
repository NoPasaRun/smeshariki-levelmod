package com.smeshariki.level.definition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.smeshariki.level.LevelMod;
import com.smeshariki.level.run.LevelStore;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/**
 * Файлы уровней: единственное место, где живут определения уровней, — JSON в папке игры {@code levelmod/levels/<пространство>/<путь>.json}
 * (у сервера — в его корне). Они читаются при загрузке мира (и при перезагрузке данных); редактор пишет в файл и в память одновременно.
 * Папка лежит вне мира намеренно: откат версии мира и откат блоков после уровня её не трогают, правки уровней не пропадают вместе с версией.
 * <p>
 * Уровни из мода (датапак {@code data/<мод>/level/*.json}) — заготовки: при первой загрузке каждая копируется в папку, если её там нет; список скопированных
 * лежит в {@code .seeded.json}, поэтому удалённая автором заготовка не возвращается, а правка автора не затирается. Вернуть исходный вид можно кнопкой в окне уровней.
 * <p>
 * Уровни, которые раньше хранились в {@code levels.dat} мира, переезжают сюда один раз ({@link #migrate}).
 */
public final class LevelFiles {
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String SEEDED = ".seeded.json";

	/** Прочитанные файлы: то, что сейчас «в памяти». Обновляется при чтении и при записи из редактора. */
	private static Map<Identifier, String> loaded = Map.of();

	private LevelFiles() {
	}

	/** Папка уровней. */
	public static Path dir(MinecraftServer server) {
		return com.smeshariki.level.ModData.moved(com.smeshariki.level.ModData.game("levelmod", "levels"), com.smeshariki.level.ModData.dir().resolve("levels"));
	}

	private static Path fileOf(MinecraftServer server, Identifier id) {
		return dir(server).resolve(id.getNamespace()).resolve(id.getPath() + ".json");
	}

	/** Привести файлы в порядок и прочитать их: перенос из мира, заготовки из мода, чтение. Безопасно звать сколько угодно раз. */
	public static void sync(MinecraftServer server, Map<Identifier, JsonElement> templates, LevelStore store) {
		try {
			Files.createDirectories(dir(server));
		} catch (IOException e) {
			LevelMod.LOGGER.error("Нет папки уровней {}", dir(server), e);
		}
		TreeSet<String> seeded = readSeeded(server);
		migrate(server, templates, store, seeded);
		for (var template : templates.entrySet()) {
			String key = template.getKey().toString();
			if (seeded.contains(key)) continue;
			seeded.add(key);
			if (!Files.exists(fileOf(server, template.getKey()))) writeFile(server, template.getKey(), pretty(template.getValue()));
		}
		writeSeeded(server, seeded);
		loaded = read(server);
	}

	/**
	 * Переезд из мира: уровни из редактора, лежавшие в {@code levels.dat}, и список скрытых заготовок. Правка заготовки из мира не переносится: она могла
	 * устареть (откат версии мира возвращал её к старому состоянию), поэтому лежит рядом файлом {@code *.world-copy.bak}, а берётся заготовка мода.
	 */
	private static void migrate(MinecraftServer server, Map<Identifier, JsonElement> templates, LevelStore store, TreeSet<String> seeded) {
		for (var legacy : store.takeLegacyCustom().entrySet()) {
			Identifier id = legacy.getKey();
			Path target = fileOf(server, id);
			try {
				if (templates.containsKey(id)) {
					Files.createDirectories(target.getParent());
					Files.writeString(target.resolveSibling(id.getPath().substring(id.getPath().lastIndexOf('/') + 1) + ".world-copy.bak"), legacy.getValue(), StandardCharsets.UTF_8);
				} else if (!Files.exists(target)) {
					writeFile(server, id, pretty(JsonParser.parseString(legacy.getValue())));
				}
			} catch (IOException | RuntimeException e) {
				LevelMod.LOGGER.error("Не перенесён уровень {} из мира в файл", id, e);
			}
		}
		for (Identifier hidden : store.takeLegacyHidden()) seeded.add(hidden.toString());
	}

	private static Map<Identifier, String> read(MinecraftServer server) {
		Map<Identifier, String> out = new HashMap<>();
		Path root = dir(server);
		if (!Files.isDirectory(root)) return out;
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path file : (Iterable<Path>) walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json") && !p.getFileName().toString().equals(SEEDED))::iterator) {
				Path rel = root.relativize(file);
				if (rel.getNameCount() < 2) continue;
				String path = rel.subpath(1, rel.getNameCount()).toString().replace('\\', '/');
				Identifier id = Identifier.tryBuild(rel.getName(0).toString(), path.substring(0, path.length() - ".json".length()));
				if (id == null) continue;
				try {
					String text = Files.readString(file, StandardCharsets.UTF_8);
					JsonParser.parseString(text);
					out.put(id, text);
				} catch (IOException | RuntimeException e) {
					LevelMod.LOGGER.error("Файл уровня {} не прочитан: {}", file, e.toString());
				}
			}
		} catch (IOException e) {
			LevelMod.LOGGER.error("Не прочитана папка уровней {}", root, e);
		}
		return out;
	}

	/** Все уровни из файлов: то, что в памяти. */
	public static Map<Identifier, String> all() {
		return loaded;
	}

	public static @Nullable String get(Identifier id) {
		return loaded.get(id);
	}

	/** Записать уровень: файл и память вместе. Ошибка записи — исключение: редактор должен сказать, что не сохранилось. */
	public static void write(MinecraftServer server, Identifier id, String json) throws IOException {
		writeFileOrThrow(server, id, pretty(JsonParser.parseString(json)));
		Map<Identifier, String> copy = new HashMap<>(loaded);
		copy.put(id, Files.readString(fileOf(server, id), StandardCharsets.UTF_8));
		loaded = copy;
	}

	/** Удалить уровень: файл и память. Заготовка мода с этим именем помечена скопированной и не вернётся. */
	public static void delete(MinecraftServer server, Identifier id) throws IOException {
		TreeSet<String> seeded = readSeeded(server);
		seeded.add(id.toString());
		writeSeeded(server, seeded);
		Files.deleteIfExists(fileOf(server, id));
		Map<Identifier, String> copy = new HashMap<>(loaded);
		copy.remove(id);
		loaded = copy;
	}

	private static String pretty(JsonElement json) {
		return PRETTY.toJson(json) + "\n";
	}

	private static void writeFile(MinecraftServer server, Identifier id, String text) {
		try {
			writeFileOrThrow(server, id, text);
		} catch (IOException e) {
			LevelMod.LOGGER.error("Не записан файл уровня {}", id, e);
		}
	}

	private static void writeFileOrThrow(MinecraftServer server, Identifier id, String text) throws IOException {
		Path file = fileOf(server, id);
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, text, StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static TreeSet<String> readSeeded(MinecraftServer server) {
		TreeSet<String> out = new TreeSet<>();
		Path file = dir(server).resolve(SEEDED);
		try {
			if (Files.exists(file)) for (JsonElement e : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) out.add(e.getAsString());
		} catch (IOException | RuntimeException e) {
			LevelMod.LOGGER.error("Не прочитан список скопированных заготовок {}", file, e);
		}
		return out;
	}

	private static void writeSeeded(MinecraftServer server, TreeSet<String> seeded) {
		JsonArray array = new JsonArray();
		new ArrayList<>(seeded).forEach(array::add);
		try {
			Path file = dir(server).resolve(SEEDED);
			Files.createDirectories(file.getParent());
			Files.writeString(file, PRETTY.toJson(array) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			LevelMod.LOGGER.error("Не записан список скопированных заготовок", e);
		}
	}

	/** Файл отличается от заготовки мода (автор правил уровень). */
	public static boolean differsFrom(Identifier id, JsonElement template) {
		String text = loaded.get(id);
		if (text == null) return false;
		try {
			return !JsonParser.parseString(text).equals(template);
		} catch (RuntimeException e) {
			return true;
		}
	}
}
