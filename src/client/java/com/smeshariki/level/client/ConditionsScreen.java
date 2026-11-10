package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.gameplay.api.Mechanics;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Список проверок состояния ({@link ConditionDraft}) задачи или наблюдателя: строка — проверка из реестра моста и её параметры. Все проверки должны
 * быть верны разом. Какие проверки есть, окно берёт из {@link Mechanics#conditions()}, названия — {@code condition.<пр>.<имя>}, подсказку о
 * параметрах — у самой проверки.
 */
final class ConditionsScreen extends Screen {
	private static final int W = 420, ROW = 24, H = 20, TOP = 40, CHECK_W = 150, DELETE_W = 20;

	private final List<ConditionDraft> conditions;
	private final Runnable back;
	private int first;

	ConditionsScreen(Component owner, List<ConditionDraft> conditions, Runnable back) {
		super(Component.translatable("gui.levelmod.conditions", owner));
		this.conditions = conditions;
		this.back = back;
	}

	private int visible() {
		return Math.max(1, (height - TOP - 34) / ROW);
	}

	@Override
	protected void init() {
		int w = Math.min(W, width - 8), x = (width - w) / 2;
		first = Math.max(0, Math.min(first, conditions.size() - visible()));
		for (int i = first; i < Math.min(conditions.size(), first + visible()); i++) {
			ConditionDraft c = conditions.get(i);
			int y = TOP + (i - first) * ROW;
			Mechanics.Condition registered = c.check == null ? null : Mechanics.condition(c.check);
			addRenderableWidget(Button.builder(ConditionDraft.name(c.check), b -> SelectScreen.open(this, Component.translatable("gui.levelmod.select.check"),
					new ArrayList<>(Mechanics.conditions().keySet()), id -> ConditionDraft.name(id).copy().append(Component.literal("  " + id).withStyle(net.minecraft.ChatFormatting.GRAY)), c.check, id -> c.check = id)).bounds(x, y, CHECK_W, H).tooltip(Tooltip.create(Component.literal(c.check == null ? "" : c.check.toString()))).build());
			String hint = registered == null ? "" : registered.hint();
			EditBox box = new EditBox(font, x + CHECK_W + 2, y, w - CHECK_W - DELETE_W - 4, H, Component.literal(hint));
			box.setMaxLength(256);
			box.setHint(Component.literal(hint));
			box.setValue(c.args);
			box.setResponder(v -> c.args = v);
			if (!hint.isEmpty()) box.setTooltip(Tooltip.create(Component.literal(hint)));
			addRenderableWidget(box);
			addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
				conditions.remove(c);
				rebuildWidgets();
			}).bounds(x + w - DELETE_W, y, DELETE_W, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.task.delete"))).build());
		}
		int bottom = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.conditions.add"), b -> {
			conditions.add(ConditionDraft.blank());
			first = Math.max(0, conditions.size() - visible());
			rebuildWidgets();
		}).bounds(x, bottom, w / 2 - 2, H).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.back"), b -> back.run()).bounds(x + w / 2 + 2, bottom, w / 2 - 2, H).build());
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int before = first;
		first = Math.max(0, Math.min(first - (int) Math.signum(scrollY), conditions.size() - visible()));
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
		g.centeredText(font, Component.translatable("gui.levelmod.conditions.hint"), width / 2, 22, 0xFFA0A0A0);
		if (conditions.isEmpty()) g.centeredText(font, Component.translatable("gui.levelmod.conditions.empty"), width / 2, TOP + 6, 0xFFA0A0A0);
	}
}
