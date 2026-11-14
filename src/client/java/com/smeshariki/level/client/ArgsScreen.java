package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.smeshariki.level.net.LevelList;
import com.smeshariki.ui.ListScreen;
import com.smeshariki.ui.SelectScreen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Параметры действия механики или проверки, записанные строкой {@code ключ=значение, ...}, как список строк. Значение-объект (группа, локация, кат-сцена,
 * предмет, «вкл/выкл») выбирается из списка, а не набирается: имя нельзя написать с опечаткой. Ключ берётся из подсказок ({@link #KEYS}) или пишется своим;
 * значение неизвестного ключа остаётся текстовым полем.
 */
final class ArgsScreen extends ListScreen {
	private static final int W = 420, ROW = 24, H = 20, TOP = 40, KEY_W = 110;
	/** Ключи, которые предлагает «Добавить»: для них известно, что выбирать. */
	private static final List<String> KEYS = List.of("group", "area", "cutscene", "item", "on");

	private enum Kind { GROUP, PLACE, CUTSCENE, ITEM, SWITCH, TEXT }

	private final Component owner;
	private final List<String[]> pairs = new ArrayList<>();
	private final Consumer<String> apply;

	/** @param text текущая строка параметров; {@code apply} получает новую при каждой правке */
	ArgsScreen(Screen parent, Component owner, String text, Consumer<String> apply) {
		super(Component.translatable("gui.levelmod.args", owner), parent);
		this.owner = owner;
		this.apply = apply;
		for (String part : text.split(",")) {
			if (part.isBlank()) continue;
			int eq = part.indexOf('=');
			pairs.add(eq < 0 ? new String[] {part.trim(), ""} : new String[] {part.substring(0, eq).trim(), part.substring(eq + 1).trim()});
		}
	}

	/** Подпись кнопки, открывающей это окно: строка параметров или «Параметры». */
	static Component label(String args) {
		return args.isBlank() ? Component.translatable("gui.levelmod.args.none") : Component.literal(args);
	}

	private static Kind kindOf(String key, String value) {
		return switch (key) {
			case "group" -> Kind.GROUP;
			case "area", "at", "place", "from", "to", "target", "zone" -> Kind.PLACE;
			case "cutscene" -> Kind.CUTSCENE;
			case "item" -> Kind.ITEM;
			case "on" -> Kind.SWITCH;
			default -> value.startsWith("@") ? Kind.PLACE : Kind.TEXT;
		};
	}

	private String join() {
		List<String> out = new ArrayList<>();
		for (String[] pair : pairs) if (!pair[0].isBlank()) out.add(pair[0] + "=" + pair[1]);
		return String.join(", ", out);
	}

	private void changed() {
		apply.accept(join());
	}

	private int visible() {
		return Math.max(1, (height - TOP - 34) / ROW);
	}

	/** Варианты для выбора значения; текущее добавляется, если его в списке нет (чужое имя не теряется). */
	private static List<String> options(Kind kind, String current) {
		List<String> all = new ArrayList<>();
		switch (kind) {
			case GROUP -> {
				for (LevelList.GroupInfo group : ClientLevels.groups) all.add(group.name());
			}
			case PLACE -> {
				for (LevelList.LocationInfo info : ClientLevels.locations.marked()) all.add("@" + info.name());
			}
			case CUTSCENE -> {
				for (Identifier cutscene : ClientLevels.cutscenes) all.add(cutscene.toString());
			}
			case ITEM -> all.addAll(registryOptions(false, ""));
			default -> {
			}
		}
		if (!current.isBlank() && !all.contains(current)) all.add(0, current);
		return all;
	}

	/** Предметы или блоки реестра по алфавиту; текущее значение, которого в реестре нет (тег), идёт первым. */
	static List<String> registryOptions(boolean blocks, String current) {
		List<String> all = new ArrayList<>();
		for (Identifier id : (blocks ? BuiltInRegistries.BLOCK : BuiltInRegistries.ITEM).keySet()) if (!id.getPath().equals("air")) all.add(id.toString());
		all.sort(String::compareTo);
		if (!current.isBlank() && !all.contains(current)) all.add(0, current);
		return all;
	}

	/** Название предмета или блока и его идентификатор серым; незнакомое значение — как есть. */
	static Component registryName(String value, boolean blocks) {
		Identifier id = Identifier.tryParse(value);
		if (id != null && blocks && BuiltInRegistries.BLOCK.containsKey(id)) return BuiltInRegistries.BLOCK.getValue(id).getName().copy().append(Component.literal("  " + value).withStyle(net.minecraft.ChatFormatting.GRAY));
		if (id != null && !blocks && BuiltInRegistries.ITEM.containsKey(id)) return BuiltInRegistries.ITEM.getValue(id).getDefaultInstance().getHoverName().copy().append(Component.literal("  " + value).withStyle(net.minecraft.ChatFormatting.GRAY));
		return Component.literal(value);
	}

	private static Component optionName(Kind kind, String value) {
		return kind == Kind.ITEM ? registryName(value, false) : Component.literal(value);
	}

	@Override
	protected void init() {
		int w = listWidth(W), x = centered(w);
		int visible = visible();
		bar.layout(x + w + 3, TOP, visible * ROW - 2, pairs.size(), visible);
		for (int i = bar.scroll; i < Math.min(pairs.size(), bar.scroll + visible); i++) {
			String[] pair = pairs.get(i);
			int y = TOP + (i - bar.scroll) * ROW;
			boolean known = KEYS.contains(pair[0]);
			if (known) {
				// известный ключ меняется выбором, свой — полем
				addRenderableWidget(Button.builder(Component.literal(pair[0]), b -> SelectScreen.open(this, Component.translatable("gui.levelmod.args.pick_key"), keyChoices(), k -> Component.literal(k), pair[0], k -> {
					pair[0] = k;
					pair[1] = "";
					changed();
				})).bounds(x, y, KEY_W, H).build());
			} else {
				EditBox key = new EditBox(font, x, y, KEY_W, H, Component.translatable("gui.levelmod.args.key"));
				key.setMaxLength(32);
				key.setHint(Component.translatable("gui.levelmod.args.key"));
				key.setValue(pair[0]);
				key.setResponder(v -> {
					pair[0] = v.trim();
					changed();
				});
				addRenderableWidget(key);
			}
			int vx = x + KEY_W + 2, vw = w - KEY_W - 24;
			Kind kind = kindOf(pair[0], pair[1]);
			switch (kind) {
				case SWITCH -> addRenderableWidget(Button.builder(Component.translatable(pair[1].equals("false") ? "gui.levelmod.args.off" : "gui.levelmod.args.on"), b -> {
					pair[1] = pair[1].equals("false") ? "true" : "false";
					changed();
					rebuildWidgets();
				}).bounds(vx, y, vw, H).build());
				case TEXT -> {
					EditBox value = new EditBox(font, vx, y, vw, H, Component.translatable("gui.levelmod.args.value"));
					value.setMaxLength(128);
					value.setHint(Component.translatable("gui.levelmod.args.value"));
					value.setValue(pair[1]);
					value.setResponder(v -> {
						pair[1] = v.trim();
						changed();
					});
					addRenderableWidget(value);
				}
				default -> addRenderableWidget(Button.builder(pair[1].isBlank() ? Component.translatable("gui.levelmod.args.choose") : optionName(kind, pair[1]), b -> {
					List<String> all = options(kind, pair[1]);
					if (all.isEmpty()) return;
					SelectScreen.open(this, Component.translatable("gui.levelmod.args.pick." + kind.name().toLowerCase(java.util.Locale.ROOT)), all, v -> optionName(kind, v), pair[1], v -> {
						pair[1] = v;
						changed();
					});
				}).bounds(vx, y, vw, H).build());
			}
			addDelete(x + w - 20, y, () -> {
				pairs.remove(pair);
				changed();
				rebuildWidgets();
			});
		}
		int bottom = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.args.add"), b -> {
			List<String> choices = new ArrayList<>(KEYS);
			choices.add("");
			SelectScreen.open(this, Component.translatable("gui.levelmod.args.pick_key"), choices, k -> k.isEmpty() ? Component.translatable("gui.levelmod.args.other") : Component.literal(k), null, k -> {
				pairs.add(new String[] {k, k.equals("on") ? "true" : ""});
				bar.scroll = Math.max(0, pairs.size() - visible());
				changed();
			});
		}).bounds(x, bottom, w / 2 - 2, H).build());
		addBack(x + w / 2 + 2, bottom, w / 2 - 2);
	}

	/** Ключи для смены известного ключа: подсказки; свой ключ добавляется через «Добавить». */
	private static List<String> keyChoices() {
		return new ArrayList<>(KEYS);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		drawHeader(g, Component.translatable("gui.levelmod.args.hint", owner));
		if (pairs.isEmpty()) drawNote(g, Component.translatable("gui.levelmod.args.empty"), TOP + 6);
	}
}
