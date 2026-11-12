package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.ui.ListScreen;
import com.smeshariki.ui.SelectScreen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Список проверок состояния ({@link ConditionDraft}) задачи или наблюдателя: строка — проверка из реестра моста и её параметры. Все проверки должны
 * быть верны разом. Какие проверки есть, окно берёт из {@link Mechanics#conditions()}, названия — {@code condition.<пр>.<имя>}, подсказку о
 * параметрах — у самой проверки.
 */
final class ConditionsScreen extends ListScreen {
	private static final int W = 420, ROW = 24, H = 20, TOP = 40, CHECK_W = 150, DELETE_W = 20;

	private final List<ConditionDraft> conditions;
	private final Runnable back;

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
		int w = listWidth(W), x = centered(w);
		int visible = visible();
		bar.layout(x + w + 3, TOP, visible * ROW - 2, conditions.size(), visible);
		for (int i = bar.scroll; i < Math.min(conditions.size(), bar.scroll + visible); i++) {
			ConditionDraft c = conditions.get(i);
			int y = TOP + (i - bar.scroll) * ROW;
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
			addDelete(x + w - DELETE_W, y, () -> {
				conditions.remove(c);
				rebuildWidgets();
			});
		}
		int bottom = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.conditions.add"), b -> {
			conditions.add(ConditionDraft.blank());
			bar.scroll = Math.max(0, conditions.size() - visible());
			rebuildWidgets();
		}).bounds(x, bottom, w / 2 - 2, H).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.back"), b -> back.run()).bounds(x + w / 2 + 2, bottom, w / 2 - 2, H).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		drawHeader(g, Component.translatable("gui.levelmod.conditions.hint"));
		if (conditions.isEmpty()) drawNote(g, Component.translatable("gui.levelmod.conditions.empty"), TOP + 6);
	}
}
