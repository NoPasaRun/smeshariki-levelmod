package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Выбор из списка: вместо перебора по нажатию — все варианты сразу, с поиском по подписи; выбранный отмечен. Выбор возвращает в окно, откуда открыли.
 * Варианты могут быть {@code null} («нет», «начало миссии»): подпись для них даёт {@code label}.
 */
final class SelectScreen<T> extends Screen {
	private static final int W = 300, H = 20, ROW = 22, TOP = 50;

	private final Screen parent;
	private final List<T> options;
	private final Function<T, Component> label;
	private final T current;
	private final Consumer<T> apply;
	private String filter = "";
	private int scroll;

	SelectScreen(Screen parent, Component title, List<T> options, Function<T, Component> label, T current, Consumer<T> apply) {
		super(title);
		this.parent = parent;
		this.options = options;
		this.label = label;
		this.current = current;
		this.apply = apply;
	}

	/** Открыть выбор поверх окна. */
	static <T> void open(Screen parent, Component title, List<T> options, Function<T, Component> label, T current, Consumer<T> apply) {
		net.minecraft.client.Minecraft.getInstance().gui.setScreen(new SelectScreen<>(parent, title, options, label, current, apply));
	}

	private List<T> shown() {
		String f = filter.trim().toLowerCase(Locale.ROOT);
		if (f.isEmpty()) return options;
		List<T> out = new ArrayList<>();
		for (T option : options) if (label.apply(option).getString().toLowerCase(Locale.ROOT).contains(f)) out.add(option);
		return out;
	}

	@Override
	protected void init() {
		int w = Math.min(W, width - 16), x = (width - w) / 2;
		EditBox search = new EditBox(font, x, 24, w, H, Component.translatable("gui.levelmod.select.search"));
		search.setHint(Component.translatable("gui.levelmod.select.search"));
		search.setValue(filter);
		search.setResponder(v -> {
			filter = v;
			scroll = 0;
			rebuildWidgets();
		});
		addRenderableWidget(search);
		setInitialFocus(search);
		List<T> list = shown();
		int visible = Math.max(1, (height - TOP - 32) / ROW);
		scroll = Math.max(0, Math.min(scroll, list.size() - visible));
		for (int i = 0; i < visible && scroll + i < list.size(); i++) {
			T option = list.get(scroll + i);
			Component text = label.apply(option);
			if (Objects.equals(option, current)) text = Component.literal("✔ ").append(text);
			addRenderableWidget(Button.builder(text, b -> {
				apply.accept(option);
				minecraft.gui.setScreen(parent);
			}).bounds(x, TOP + i * ROW, w, H).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.back"), b -> minecraft.gui.setScreen(parent)).bounds(x + w / 4, height - 26, w / 2, H).build());
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int before = scroll;
		scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
		if (scroll != before) rebuildWidgets();
		return true;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		if (shown().isEmpty()) g.centeredText(font, Component.translatable("gui.levelmod.select.empty"), width / 2, TOP + 6, 0xFFA0A0A0);
	}
}
