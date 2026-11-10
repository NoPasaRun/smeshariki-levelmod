package com.smeshariki.level.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Наблюдатели уровня ({@link LevelDraft.WatchDraft}): имя (событие {@code custom:имя}, его ловят триггеры и правила механик), с какого события
 * наблюдать и проверки состояния ({@link ConditionsScreen}).
 */
final class WatchesScreen extends Screen {
	private static final int W = 460, ROW = 24, H = 20, TOP = 40, ID_W = 100, DELAY_W = 50, CONDITIONS_W = 100, DELETE_W = 20;

	private final LevelDraft draft;
	private int first;

	WatchesScreen(LevelDraft draft) {
		super(Component.translatable("gui.levelmod.watches"));
		this.draft = draft;
	}

	private int visible() {
		return Math.max(1, (height - TOP - 34) / ROW);
	}

	@Override
	protected void init() {
		int w = Math.min(W, width - 8), x = (width - w) / 2;
		first = Math.max(0, Math.min(first, draft.watches.size() - visible()));
		for (int i = first; i < Math.min(draft.watches.size(), first + visible()); i++) {
			LevelDraft.WatchDraft watch = draft.watches.get(i);
			int y = TOP + (i - first) * ROW;
			addRenderableWidget(LevelEditorScreen.field(this, font, x, y, ID_W, "gui.levelmod.watches.id", watch.id, 64, v -> watch.id = v));
			int afterW = w - ID_W - DELAY_W - CONDITIONS_W - DELETE_W - 8;
			com.smeshariki.level.definition.LevelEvent after = watch.after.isBlank() ? null
					: com.smeshariki.level.definition.LevelEvent.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, new com.google.gson.JsonPrimitive(watch.after.trim())).result().orElse(null);
			Component afterName = after == null ? Component.translatable(watch.after.isBlank() ? "gui.levelmod.watches.from_start" : "gui.levelmod.editor.none") : MechanicRulesScreen.eventName(draft, after);
			addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.watches.after_value", afterName), b -> {
				java.util.List<com.smeshariki.level.definition.LevelEvent> all = new java.util.ArrayList<>();
				all.add(null);
				all.addAll(MechanicRulesScreen.eventOptions(draft));
				SelectScreen.open(this, Component.translatable("gui.levelmod.select.event"), all,
						e -> e == null ? Component.translatable("gui.levelmod.watches.from_start") : MechanicRulesScreen.eventName(draft, e), after, e -> watch.after = e == null ? "" : e.toString());
			}).bounds(x + ID_W + 2, y, afterW, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.watches.after"))).build());
			addRenderableWidget(LevelEditorScreen.field(this, font, x + ID_W + afterW + 4, y, DELAY_W, "gui.levelmod.watches.delay", watch.delay, 8, v -> watch.delay = v));
			addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.watches.conditions", watch.conditions.size()), b -> minecraft.gui.setScreen(new ConditionsScreen(Component.literal(watch.id), watch.conditions, () -> minecraft.gui.setScreen(new WatchesScreen(draft))))).bounds(x + ID_W + afterW + DELAY_W + 6, y, CONDITIONS_W, H).build());
			addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
				draft.watches.remove(watch);
				rebuildWidgets();
			}).bounds(x + w - DELETE_W, y, DELETE_W, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.task.delete"))).build());
		}
		int bottom = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.watches.add"), b -> {
			LevelDraft.WatchDraft watch = new LevelDraft.WatchDraft();
			watch.id = "w" + (draft.watches.size() + 1);
			watch.conditions.add(ConditionDraft.blank());
			draft.watches.add(watch);
			first = Math.max(0, draft.watches.size() - visible());
			rebuildWidgets();
		}).bounds(x, bottom, w / 2 - 2, H).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.back"), b -> LevelEditorScreen.reopen(minecraft)).bounds(x + w / 2 + 2, bottom, w / 2 - 2, H).build());
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int before = first;
		first = Math.max(0, Math.min(first - (int) Math.signum(scrollY), draft.watches.size() - visible()));
		if (first != before) rebuildWidgets();
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		g.centeredText(font, Component.translatable("gui.levelmod.watches.hint"), width / 2, 22, 0xFFA0A0A0);
		if (draft.watches.isEmpty()) g.centeredText(font, Component.translatable("gui.levelmod.watches.empty"), width / 2, TOP + 6, 0xFFA0A0A0);
	}
}
