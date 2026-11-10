package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.level.definition.LevelDefinition;
import com.smeshariki.level.definition.LevelKind;
import com.smeshariki.level.definition.LevelPreview;
import com.smeshariki.level.net.LevelCommand;
import com.smeshariki.level.net.LevelSwapCharacter;
import com.smeshariki.level.run.LevelManager;
import com.smeshariki.level.net.LevelList.LevelInfo;
import com.smeshariki.level.net.LevelList.RunInfo;
import com.smeshariki.level.net.LevelList.TaskInfo;
import com.smeshariki.level.run.LevelRun;
import com.smeshariki.level.run.LevelStats;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.GameType;

/**
 * Менеджер уровней (клавиша L): слева карточки уровней с превью, справа выбранный уровень (превью, описание, длительность, задачи) и
 * кнопки прогона. Данные присылает сервер; игра в этом окне не останавливается, чтобы группа не ждала открывшего его.
 */
final class LevelManagerScreen extends Screen {
	private static final int MARGIN = 8, LIST_W = 150, CARD_H = 40, THUMB_W = 53, THUMB_H = 30, BUTTON_H = 20, LINE = 11;
	private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0, DARK = 0xFF707070, GREEN = 0xFF60D060, YELLOW = 0xFFFFD040;
	private static final int CARD = 0x60000000, CARD_HOVER = 0x80202020, CARD_SELECTED = 0xA0304050, FRAME = 0xFF000000;

	/** Есть ли текстура превью в ресурсах: чтобы не искать её заново каждый кадр. */
	private final Map<Identifier, Boolean> textures = new HashMap<>();
	private List<LevelInfo> shown;
	private Identifier selected;
	private int listScroll, detailScroll, detailHeight;
	private boolean confirmDelete;

	LevelManagerScreen() {
		super(Component.translatable("gui.levelmod.levels"));
	}

	private LevelInfo current() {
		for (LevelInfo level : ClientLevels.levels) if (level.id().equals(selected)) return level;
		return null;
	}

	@Override
	protected void init() {
		shown = ClientLevels.levels;
		if (current() == null) selected = shown.isEmpty() ? null : shown.getFirst().id();
		int y = height - MARGIN - BUTTON_H;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.close"), b -> onClose()).bounds(width - MARGIN - 70, y, 70, BUTTON_H).build());
		if (ClientLevels.editing()) addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.create"), b -> LevelEditorScreen.open(minecraft, LevelDraft.blank())).bounds(MARGIN, y, 50, BUTTON_H).build());
		if (ClientLevels.editing()) addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.locations"), b -> minecraft.gui.setScreen(new LocationsScreen())).bounds(width - MARGIN - 70, 3, 70, 18).build());
		LevelInfo level = current();
		if (level == null) return;
		if (ClientLevels.editing() && level.source().isPresent()) {
			boolean free = level.run().isEmpty();
			addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.edit"), b -> LevelEditorScreen.open(minecraft, LevelDraft.of(level.id(), level.source().get()))).bounds(MARGIN + 52, y, 48, BUTTON_H).build()).active = free;
			// уровень из файла с правкой из редактора: вместо удаления — вернуть файл (удалить можно после)
			boolean reset = level.meta().overridesFile();
			addRenderableWidget(Button.builder(Component.translatable(confirmDelete ? "gui.levelmod.delete.confirm" : reset ? "gui.levelmod.reset" : "gui.levelmod.delete"), b -> {
				if (confirmDelete) ClientPlayNetworking.send(new LevelCommand(reset ? LevelCommand.RESET : LevelCommand.DELETE, level.id()));
				confirmDelete = !confirmDelete;
				rebuildWidgets();
			}).bounds(MARGIN + 102, y, 48, BUTTON_H).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(reset ? "gui.levelmod.reset.hint" : "gui.levelmod.delete"))).build()).active = free;
		}
		boolean ready = eligible() && level.meta().refusal().isEmpty();
		boolean near = !tooFar(level);
		int x = MARGIN + LIST_W + MARGIN;
		UUID me = minecraft.player.getUUID();
		RunInfo run = level.run().orElse(null);
		if (run == null) {
			String label = level.maxPlayers() == 1 ? "gui.levelmod.start" : "gui.levelmod.gather";
			if (level.meta().refusal().equals(LevelManager.REFUSE_CHARACTER_SWAP)) addSwapButtons(level, x, y);
			else addAction(x, y, 110, label, ready && near, LevelCommand.START);
		} else if (run.state() == LevelRun.State.GATHERING) {
			if (hasMe(run, me)) {
				boolean leader = run.participants().getFirst().id().equals(me);
				addAction(x, y, 90, "gui.levelmod.begin", ready && leader && run.participants().size() >= level.minPlayers(), LevelCommand.BEGIN);
				addAction(x + 92, y, 60, "gui.levelmod.leave", true, LevelCommand.LEAVE);
			} else {
				addAction(x, y, 110, "gui.levelmod.join", ready && near && run.participants().size() < level.maxPlayers(), LevelCommand.JOIN);
			}
		} else if (hasMe(run, me)) {
			addAction(x, y, 110, "gui.levelmod.abandon", true, LevelCommand.LEAVE);
		} else if (run.state() == LevelRun.State.RUNNING) {
			// вышедший из идущего прогона (сменил режим) возвращается, если роль за ним осталась; проверяет сервер
			addAction(x, y, 110, "gui.levelmod.join", eligible(), LevelCommand.JOIN);
		}
	}

	/** Персонаж не подходит уровню: вместо начала предлагаем подходящих, смена ведёт обратно к обычной кнопке. */
	private void addSwapButtons(LevelInfo level, int x, int y) {
		int offset = 0;
		for (Identifier character : level.meta().characters()) {
			addRenderableWidget(Button.builder(characterName(character), b -> ClientPlayNetworking.send(new LevelSwapCharacter(level.id(), character))).bounds(x + offset, y, 38, BUTTON_H).build());
			offset += 40;
		}
	}

	private static Component characterName(Identifier character) {
		return BuiltInRegistries.ENTITY_TYPE.getValue(character).getDescription();
	}

	private void addAction(int x, int y, int w, String label, boolean active, int action) {
		Identifier level = selected;
		addRenderableWidget(Button.builder(Component.translatable(label), b -> ClientPlayNetworking.send(new LevelCommand(action, level))).bounds(x, y, w, BUTTON_H).build()).active = active;
	}

	/** Уровни проходят только в режиме выживания и вселившись в персонажа; окончательно это проверяет сервер. */
	private boolean eligible() {
		return minecraft.gameMode.getPlayerMode() == GameType.SURVIVAL && minecraft.player.getVehicle() instanceof CharacterEntity;
	}

	/** Побочный уровень берут, подойдя к его началу. */
	private boolean tooFar(LevelInfo level) {
		if (level.meta().kind() != LevelKind.SIDE || level.run().filter(run -> hasMe(run, minecraft.player.getUUID())).isPresent()) return false;
		return level.meta().startPos().filter(start -> !LevelDefinition.nearStart(start, minecraft.player.position())).isPresent();
	}

	private static Component farHint(LevelInfo level) {
		var start = level.meta().startPos().orElseThrow();
		return Component.translatable("message.levelmod.refuse.side_far", (int) start.x, (int) start.y, (int) start.z);
	}

	private boolean isRunningWithMe(LevelInfo level) {
		return level.run().isPresent() && level.run().get().state() == LevelRun.State.RUNNING && hasMe(level.run().get(), minecraft.player.getUUID());
	}

	private static boolean hasMe(RunInfo run, UUID me) {
		return run.participants().stream().anyMatch(p -> p.id().equals(me));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		if (shown != ClientLevels.levels) rebuildWidgets();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (LevelModClient.OPEN.matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && event.x() >= MARGIN && event.x() < MARGIN + LIST_W && event.y() >= listTop() && event.y() < listBottom()) {
			int row = ((int) event.y() - listTop() + listScroll) / CARD_H;
			if (row >= 0 && row < shown.size()) {
				selected = shown.get(row).id();
				detailScroll = 0;
				confirmDelete = false;
				rebuildWidgets();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int step = (int) Math.signum(scrollY) * LINE * 2;
		if (x < MARGIN + LIST_W) listScroll = clamp(listScroll - step, shown.size() * CARD_H - (listBottom() - listTop()));
		else detailScroll = clamp(detailScroll - step, detailHeight);
		return true;
	}

	private static int clamp(int value, int max) {
		return Math.max(0, Math.min(value, Math.max(0, max)));
	}

	private int listTop() {
		return 24;
	}

	private int listBottom() {
		return height - MARGIN - BUTTON_H - 4;
	}

	/** Низ правой части: под ней подсказка и кнопки. */
	private int detailBottom() {
		return height - MARGIN - BUTTON_H - 4 - LINE;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, title, MARGIN, 9, WHITE);
		if (!ClientLevels.locations.problems().isEmpty()) g.text(font, Component.translatable("gui.levelmod.locations.problems", ClientLevels.locations.problems().size()), MARGIN + 50, 9, YELLOW);
		if (shown.isEmpty()) {
			g.centeredText(font, Component.translatable("gui.levelmod.empty"), width / 2, height / 2 - 4, GRAY);
			return;
		}
		drawList(g, mouseX, mouseY);
		LevelInfo level = current();
		if (level != null) drawDetail(g, level);
		if (level != null && !isRunningWithMe(level)) {
			Component hint = !eligible() ? Component.translatable("gui.levelmod.need_survival") : !level.meta().refusal().isEmpty() ? Component.translatable(level.meta().refusal()) : tooFar(level) ? farHint(level) : null;
			if (hint != null) g.text(font, firstLine(hint, width - 2 * MARGIN - LIST_W - MARGIN), MARGIN + LIST_W + MARGIN, detailBottom() + 1, YELLOW);
		}
	}

	private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int top = listTop(), bottom = listBottom();
		g.enableScissor(MARGIN, top, MARGIN + LIST_W, bottom);
		for (int i = 0; i < shown.size(); i++) {
			LevelInfo level = shown.get(i);
			int y = top + i * CARD_H - listScroll;
			if (y + CARD_H <= top || y >= bottom) continue;
			boolean hover = mouseX >= MARGIN && mouseX < MARGIN + LIST_W && mouseY >= Math.max(y, top) && mouseY < Math.min(y + CARD_H - 2, bottom);
			g.fill(MARGIN, y, MARGIN + LIST_W, y + CARD_H - 2, level.id().equals(selected) ? CARD_SELECTED : hover ? CARD_HOVER : CARD);
			drawPreview(g, level.preview(), MARGIN + 3, y + 4, THUMB_W, THUMB_H);
			int textX = MARGIN + THUMB_W + 7, textW = LIST_W - THUMB_W - 10;
			g.text(font, firstLine(level.title(), textW), textX, y + 5, WHITE);
			g.text(font, firstLine(Component.translatable("gui.levelmod.kind." + level.meta().kind().getSerializedName()).append(" · ≈ " + LevelStats.format(level.expectedTicks())), textW), textX, y + 16, GRAY);
			Component status = status(level);
			if (status != null) g.text(font, firstLine(status, textW), textX, y + 26, level.run().isPresent() ? YELLOW : GREEN);
		}
		g.disableScissor();
	}

	/** Строка состояния на карточке: идёт, сбор группы или сколько раз пройден. */
	private static Component status(LevelInfo level) {
		if (level.run().isPresent()) return Component.translatable(level.run().get().state() == LevelRun.State.GATHERING ? "gui.levelmod.status.gathering" : "gui.levelmod.status.running");
		return level.stats().runs() > 0 ? Component.translatable("gui.levelmod.status.done", level.stats().runs()) : null;
	}

	private void drawDetail(GuiGraphicsExtractor g, LevelInfo level) {
		int x = MARGIN + LIST_W + MARGIN, w = width - x - MARGIN, top = listTop(), bottom = detailBottom();
		// превью 16:9 на всю ширину, но не больше трети окна
		int previewH = Math.min(w * 9 / 16, height / 3), previewW = previewH * 16 / 9;
		drawPreview(g, level.preview(), x, top, previewW, previewH);
		int infoX = x + previewW + MARGIN, infoW = x + w - infoX;
		boolean beside = infoW >= 100;
		int y = top;
		if (beside) {
			y = drawInfo(g, level, infoX, y, infoW);
			y = Math.max(y, top + previewH + MARGIN);
		} else {
			y = top + previewH + MARGIN;
		}
		int contentTop = y;
		g.enableScissor(x, contentTop, x + w, bottom);
		y -= detailScroll;
		if (!beside) y = drawInfo(g, level, x, y, w);
		for (FormattedCharSequence line : font.split(level.description(), w)) {
			g.text(font, line, x, y, WHITE);
			y += LINE;
		}
		y += 4;
		g.text(font, Component.translatable("gui.levelmod.tasks"), x, y, GRAY);
		y += LINE;
		int mission = -1;
		for (TaskInfo task : level.tasks()) {
			if (!task.mine()) continue;
			if (task.mission() != mission) {
				mission = task.mission();
				g.text(font, firstLine(Component.translatable("gui.levelmod.mission.number", mission + 1, level.missions().get(mission)), w), x, y, 0xFF80C0FF);
				y += LINE;
			}
			y = drawTask(g, task, x, y, w);
		}
		g.disableScissor();
		detailHeight = y + detailScroll - contentTop - (bottom - contentTop);
	}

	/** Блок сведений рядом с превью; возвращает y под собой. */
	private int drawInfo(GuiGraphicsExtractor g, LevelInfo level, int x, int y, int w) {
		List<Component> lines = new ArrayList<>();
		lines.add(level.title());
		lines.add(Component.translatable("gui.levelmod.kind." + level.meta().kind().getSerializedName()));
		lines.add(Component.translatable(level.minPlayers() == level.maxPlayers() ? "gui.levelmod.players.exact" : "gui.levelmod.players.range", level.minPlayers(), level.maxPlayers()));
		lines.add(Component.translatable("gui.levelmod.expected", LevelStats.format(level.expectedTicks())));
		lines.add(level.stats().runs() == 0 ? Component.translatable("gui.levelmod.average.none")
				: Component.translatable("gui.levelmod.average", LevelStats.format(level.stats().averageTicks()), level.stats().runs()));
		lines.add(Component.translatable("gui.levelmod.characters", level.meta().characters().isEmpty() ? Component.translatable("gui.levelmod.characters.all") : ComponentUtils.formatList(level.meta().characters(), LevelManagerScreen::characterName)));
		level.run().ifPresent(run -> {
			if (run.state() == LevelRun.State.RUNNING) lines.add(Component.translatable("gui.levelmod.elapsed", LevelStats.format(run.elapsed() + ClientLevels.age)));
			lines.add(Component.translatable("gui.levelmod.group", String.join(", ", run.participants().stream().map(LevelRun.Participant::name).toList())));
		});
		for (int i = 0; i < lines.size(); i++) {
			for (FormattedCharSequence line : font.split(lines.get(i), w)) {
				g.text(font, line, x, y, i == 0 ? WHITE : GRAY);
				y += LINE;
			}
		}
		return y + 2;
	}

	private int drawTask(GuiGraphicsExtractor g, TaskInfo task, int x, int y, int w) {
		String mark = task.completed() ? "✔" : task.missed() ? "✗" : task.started() ? "▶" : "○";
		int color = task.completed() ? GREEN : task.missed() ? DARK : task.started() ? YELLOW : DARK;
		g.text(font, mark, x, y, color);
		int counterW = task.counter().isEmpty() || task.completed() ? 0 : font.width(task.counter()) + 6;
		if (counterW > 0) g.text(font, task.counter(), x + w - counterW + 6, y, color);
		g.text(font, firstLine(TaskHud.titleOf(task), w - 12 - counterW), x + 12, y, task.started() && !task.missed() ? WHITE : GRAY);
		return y + LINE;
	}

	/** Рисует превью по его виду; здесь же появится отрывок кат-сцены. */
	private void drawPreview(GuiGraphicsExtractor g, LevelPreview preview, int x, int y, int w, int h) {
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, FRAME);
		switch (preview) {
			case LevelPreview.Image(Identifier texture) -> {
				if (textures.computeIfAbsent(texture, id -> minecraft.getResourceManager().getResource(id).isPresent())) g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, w, h, w, h);
				else {
					g.fill(x, y, x + w, y + h, 0xFF303038);
					g.centeredText(font, "?", x + w / 2, y + h / 2 - 4, DARK);
				}
			}
		}
	}

	private FormattedCharSequence firstLine(Component text, int w) {
		List<FormattedCharSequence> lines = font.split(text, Math.max(1, w));
		return lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.getFirst();
	}
}
