package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.blaze3d.platform.InputConstants;
import com.smeshariki.level.LevelMod;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Текст со значками управления для окна «Описание механики». В строке (из языкового файла любого мода) значки задаются так:
 * {@code {lmb}} — мышь с левой кнопкой, {@code {rmb}} — с правой, {@code {wheel}} — с колесом (белая мышь, нажимаемая часть оранжевая),
 * {@code {ctrl}}, {@code {shift}} — клавиши, {@code {key:название}} — клавиша с любой надписью. Мышь рисуется кубиками.
 * Клавиши — картинки из {@code textures/gui/sprites/key/} (делает {@code tools/make_key_sprites.py}): у стрелок, Shift, Enter, Backspace, Tab и пробела
 * нарисован символ (узнаются по названию клавиши на языке игры или по значку в {@code {key:↑}}), остальные — пустая клавиша {@code cap} с надписью.
 */
final class RichText {
	private static final Pattern TOKEN = Pattern.compile("\\{(lmb|rmb|wheel|ctrl|shift|key:[^}]*)}");
	private static final int MOUSE_W = 9, MOUSE_H = 11, GAP = 2, CAP_PAD = 3, CAP_MIN_W = 12, KEY_H = 12, KEY_ABOVE = 2;
	private static final int MOUSE_EDGE = 0xFF5B6B80, MOUSE_BODY = 0xFFF2F4F7, MOUSE_LINE = 0xFF9AA5B1, PRESSED = 0xFFFF8C1A, CAP_TEXT = 0xFF2B3442;
	private static final Identifier CAP = sprite("cap");
	/** Клавиши с нарисованным символом: имя спрайта {@code key/<имя>} → ширина (должна совпадать с PNG). */
	private static final Map<String, Integer> SPRITE_WIDTH = Map.of("up", 11, "down", 11, "left", 11, "right", 11, "shift", 13, "enter", 13, "backspace", 13, "tab", 12, "space", 25);
	private static final Map<String, Identifier> PICTURES = Map.of("up", sprite("up"), "down", sprite("down"), "left", sprite("left"), "right", sprite("right"), "shift", sprite("shift"),
			"enter", sprite("enter"), "backspace", sprite("backspace"), "tab", sprite("tab"), "space", sprite("space"));
	/** Названия, которыми автор строки может назвать клавишу, не завися от языка. */
	private static final Map<String, String> ALIASES = Map.ofEntries(Map.entry("up", "up"), Map.entry("↑", "up"), Map.entry("down", "down"), Map.entry("↓", "down"),
			Map.entry("left", "left"), Map.entry("←", "left"), Map.entry("right", "right"), Map.entry("→", "right"), Map.entry("shift", "shift"), Map.entry("⇧", "shift"),
			Map.entry("enter", "enter"), Map.entry("return", "enter"), Map.entry("↵", "enter"), Map.entry("⏎", "enter"), Map.entry("backspace", "backspace"),
			Map.entry("⌫", "backspace"), Map.entry("tab", "tab"), Map.entry("⇥", "tab"), Map.entry("space", "space"), Map.entry("␣", "space"));
	/** Клавиши, которые игра называет сама: код → спрайт (названия берутся на текущем языке игры). */
	private static final int[] SPRITE_KEYS = {InputConstants.KEY_UP, InputConstants.KEY_DOWN, InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_LSHIFT,
			InputConstants.KEY_RSHIFT, InputConstants.KEY_RETURN, InputConstants.KEY_BACKSPACE, InputConstants.KEY_TAB, InputConstants.KEY_SPACE};
	private static final String[] SPRITE_NAMES = {"up", "down", "left", "right", "shift", "shift", "enter", "backspace", "tab", "space"};

	private static Identifier sprite(String name) {
		return Identifier.fromNamespaceAndPath(LevelMod.NAMESPACE, "key/" + name);
	}

	/** Кусок строки: слово или значок. */
	record Atom(String text, String icon, int width) {
	}

	record Line(List<Atom> atoms) {
	}

	private RichText() {
	}

	/** Разбивает текст на строки не шире {@code width}, значки не рвутся. */
	static List<Line> layout(Font font, Component component, int width) {
		String s = component.getString();
		List<Atom> atoms = new ArrayList<>();
		Matcher m = TOKEN.matcher(s);
		Map<String, String> names = null;
		int pos = 0;
		while (m.find()) {
			words(font, s.substring(pos, m.start()), atoms);
			if (names == null) names = keyNames();
			atoms.add(icon(font, m.group(1), names));
			pos = m.end();
		}
		words(font, s.substring(pos), atoms);
		List<Line> lines = new ArrayList<>();
		List<Atom> cur = new ArrayList<>();
		int x = 0;
		for (Atom atom : atoms) {
			if (atom.icon == null && cur.isEmpty() && atom.text.isBlank()) continue;
			if (x + atom.width > width && !cur.isEmpty()) {
				lines.add(new Line(cur));
				cur = new ArrayList<>();
				x = 0;
				if (atom.icon == null && atom.text.isBlank()) continue;
			}
			cur.add(atom);
			x += atom.width;
		}
		if (!cur.isEmpty() || lines.isEmpty()) lines.add(new Line(cur));
		return lines;
	}

	private static void words(Font font, String text, List<Atom> out) {
		for (String w : text.split("(?<= )")) if (!w.isEmpty()) out.add(new Atom(w, null, font.width(w)));
	}

	private static Atom icon(Font font, String name, Map<String, String> names) {
		if (name.startsWith("key:")) return key(font, name.substring(4), names);
		return switch (name) {
			case "ctrl" -> keycap(font, "Ctrl");
			case "shift" -> picture("shift");
			default -> new Atom(null, name, MOUSE_W + GAP);
		};
	}

	/** Клавиша по надписи: с нарисованным символом, если надпись — название такой клавиши, иначе пустая с текстом. */
	private static Atom key(Font font, String label, Map<String, String> names) {
		String trimmed = label.trim();
		String sprite = ALIASES.get(trimmed.toLowerCase(Locale.ROOT));
		if (sprite == null) sprite = names.get(trimmed);
		return sprite != null ? picture(sprite) : keycap(font, trimmed);
	}

	private static Atom picture(String sprite) {
		return new Atom(null, "key:" + sprite, SPRITE_WIDTH.get(sprite) + GAP);
	}

	private static Atom keycap(Font font, String label) {
		return new Atom(label, "cap", Math.max(CAP_MIN_W, font.width(label) + CAP_PAD * 2) + GAP);
	}

	/** Названия клавиш с символом на текущем языке игры («Стрелка влево», «Пробел», «Левый Shift»…) → имя спрайта. */
	private static Map<String, String> keyNames() {
		Map<String, String> names = new HashMap<>();
		for (int i = 0; i < SPRITE_KEYS.length; i++) names.put(InputConstants.Type.KEYSYM.getOrCreate(SPRITE_KEYS[i]).getDisplayName().getString(), SPRITE_NAMES[i]);
		return names;
	}

	/** Рисует строку с левого края {@code x}, {@code y} — верх текста. */
	static void draw(GuiGraphicsExtractor g, Font font, Line line, int x, int y, int color) {
		int cx = x;
		for (Atom atom : line.atoms) {
			if (atom.icon == null) g.text(font, atom.text, cx, y, color);
			else if (atom.icon.equals("cap")) {
				int w = atom.width - GAP;
				g.blitSprite(RenderPipelines.GUI_TEXTURED, CAP, cx, y - KEY_ABOVE, w, KEY_H);
				g.text(font, atom.text, cx + (w - font.width(atom.text)) / 2, y, CAP_TEXT, false);
			} else if (atom.icon.startsWith("key:")) g.blitSprite(RenderPipelines.GUI_TEXTURED, PICTURES.get(atom.icon.substring(4)), cx, y - KEY_ABOVE, atom.width - GAP, KEY_H);
			else mouse(g, cx, y - 1, atom.icon);
			cx += atom.width;
		}
	}

	/** Белая мышь; нажимаемая часть оранжевая. */
	private static void mouse(GuiGraphicsExtractor g, int x, int y, String which) {
		g.fill(x, y, x + MOUSE_W, y + MOUSE_H, MOUSE_EDGE);
		g.fill(x + 1, y + 1, x + MOUSE_W - 1, y + MOUSE_H - 1, MOUSE_BODY);
		g.fill(x + 1, y + 5, x + MOUSE_W - 1, y + 6, MOUSE_LINE);
		g.fill(x + 4, y + 1, x + 5, y + 5, MOUSE_LINE);
		switch (which) {
			case "lmb" -> g.fill(x + 1, y + 1, x + 4, y + 5, PRESSED);
			case "rmb" -> g.fill(x + 5, y + 1, x + 8, y + 5, PRESSED);
			default -> g.fill(x + 3, y + 1, x + 6, y + 5, PRESSED);
		}
	}
}
