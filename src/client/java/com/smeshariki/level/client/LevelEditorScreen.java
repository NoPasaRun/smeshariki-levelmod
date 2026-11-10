package com.smeshariki.level.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.buildmod.client.OutlineDrawing;
import com.smeshariki.gameplay.character.Possession;
import com.smeshariki.level.definition.LevelKind;
import com.smeshariki.level.net.LevelSave;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/** Редактор уровня: название, описание, превью, число игроков, кат-сцена в начале. Задачи — в своём меню ({@link TaskEditorScreen}), правила механик — в {@link MechanicRulesScreen}. */
final class LevelEditorScreen extends Screen {
	private static final int W = 320, ROW = 24, H = 20;
	private static final int MAX_PLAYERS = 8;

	private final LevelDraft draft;
	private final List<Component> problems = new ArrayList<>();

	private LevelEditorScreen(LevelDraft draft) {
		super(Component.translatable("gui.levelmod.editor"));
		this.draft = draft;
	}

	static void open(Minecraft mc, LevelDraft draft) {
		ClientLevels.draft = draft;
		ClientLevels.draftTask = null;
		ClientLevels.draftMission = null;
		ClientLevels.inTaskMenu = false;
		mc.gui.setScreen(new LevelEditorScreen(draft));
	}

	/** Возвращает к тому, что было открыто до прогулки за углами области. */
	static void reopen(Minecraft mc) {
		OutlineDrawing.cancel();
		if (ClientLevels.inTaskMenu) mc.gui.setScreen(new TaskEditorScreen(ClientLevels.draft));
		else mc.gui.setScreen(new LevelEditorScreen(ClientLevels.draft));
	}

	/** Поле ввода, сразу пишущее в черновик. */
	static EditBox field(Screen screen, net.minecraft.client.gui.Font font, int x, int y, int w, String hint, String value, int maxLength, Consumer<String> target) {
		EditBox box = new EditBox(font, x, y, w, H, Component.translatable(hint));
		box.setMaxLength(maxLength);
		box.setHint(Component.translatable(hint));
		box.setValue(value);
		box.setResponder(target);
		box.setTooltip(Tooltip.create(Component.translatable(hint)));
		return box;
	}

	/** Варианты кат-сцены для выбора: «нет» и все известные. */
	static List<Identifier> cutsceneOptions() {
		List<Identifier> all = new java.util.ArrayList<>();
		all.add(null);
		all.addAll(ClientLevels.cutscenes);
		return all;
	}

	/** Имя локации на кнопке выбора. */
	private static Component location(String name) {
		return name.isEmpty() ? Component.translatable("gui.levelmod.editor.none") : Component.literal(name);
	}

	static Component cutsceneName(Identifier cutscene) {
		return cutscene == null ? Component.translatable("gui.levelmod.editor.none") : Component.literal(cutscene.toString());
	}

	@Override
	protected void init() {
		int x = (width - W) / 2, y = 22;
		addRenderableWidget(field(this, font, x, y, W, "gui.levelmod.editor.title", draft.title, 64, v -> draft.title = v));
		y += ROW;
		addRenderableWidget(field(this, font, x, y, W, "gui.levelmod.editor.description", draft.description, 300, v -> draft.description = v));
		y += ROW;
		addRenderableWidget(field(this, font, x, y, W, "gui.levelmod.editor.preview", draft.preview, 128, v -> draft.preview = v));
		y += ROW;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.kind." + draft.kind.getSerializedName()), b -> {
			draft.kind = draft.kind == LevelKind.STORY ? LevelKind.SIDE : LevelKind.STORY;
			rebuildWidgets();
		}).bounds(x, y, 78, H).build());
		// у сюжетного уровня старт свой у каждого персонажа, а побочный берут в одном месте
		if (draft.kind == LevelKind.SIDE) addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.start", location(draft.start)), b -> minecraft.gui.setScreen(new LocationPickerScreen(this, false, name -> draft.start = name))).bounds(x + 80, y, W - 80, H).build());
		y += ROW;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.min_players", draft.minPlayers), b -> {
			draft.minPlayers = draft.minPlayers % MAX_PLAYERS + 1;
			draft.maxPlayers = Math.max(draft.minPlayers, draft.maxPlayers);
			rebuildWidgets();
		}).bounds(x, y, 78, H).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.max_players", draft.maxPlayers), b -> {
			draft.maxPlayers = draft.maxPlayers >= MAX_PLAYERS ? draft.minPlayers : draft.maxPlayers + 1;
			rebuildWidgets();
		}).bounds(x + 80, y, 78, H).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.intro", cutsceneName(draft.intro)),
				b -> SelectScreen.open(this, Component.translatable("gui.levelmod.select.cutscene"), cutsceneOptions(), LevelEditorScreen::cutsceneName, draft.intro, c -> draft.intro = c)).bounds(x + 160, y, 160, H).build());
		y += ROW;
		// никто не отмечен — подходит любой
		var types = Possession.choices();
		int cell = W / types.size();
		for (int i = 0; i < types.size(); i++) {
			Identifier character = EntityType.getKey(types.get(i));
			boolean on = draft.characters.contains(character);
			addRenderableWidget(Button.builder(Component.literal(on ? "✔ " : "").append(types.get(i).getDescription()), b -> {
				if (!draft.characters.remove(character)) draft.characters.add(character);
				rebuildWidgets();
			}).bounds(x + i * cell, y, cell - 2, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.editor.characters"))).build());
		}
		y += ROW;
		// откуда начинает каждый: только те, кто привязан к уровню
		for (int i = 0; i < types.size(); i++) {
			Identifier character = EntityType.getKey(types.get(i));
			if (!draft.boundCharacters().contains(character)) continue;
			addRenderableWidget(Button.builder(location(draft.characterStarts.getOrDefault(character, "")), b -> minecraft.gui.setScreen(new LocationPickerScreen(this, false, name -> {
				if (name.isEmpty()) draft.characterStarts.remove(character);
				else draft.characterStarts.put(character, name);
			}))).bounds(x + i * cell, y, cell - 2, H).tooltip(Tooltip.create(Component.translatable("gui.levelmod.editor.character_start"))).build());
		}
		y += ROW;
		int third = (W - 4) / 3;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.tasks.open", draft.missions.size()), b -> {
			ClientLevels.inTaskMenu = true;
			reopen(minecraft);
		}).bounds(x, y, third, H).build());
		// в один ряд с задачами: ниже на невысоком экране уже кнопки сохранения
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.rules.open", draft.rules.size()), b -> minecraft.gui.setScreen(new MechanicRulesScreen(draft))).bounds(x + third + 2, y, third, H).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.watches.open", draft.watches.size()), b -> minecraft.gui.setScreen(new WatchesScreen(draft))).bounds(x + 2 * third + 4, y, third, H).build());
		int buttons = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.save"), b -> save()).bounds(x, buttons, W / 2 - 2, H).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.levelmod.editor.cancel"), b -> toManager()).bounds(x + W / 2 + 2, buttons, W / 2 - 2, H).build());
	}

	private void toManager() {
		ClientLevels.draft = null;
		ClientLevels.draftTask = null;
		ClientLevels.inTaskMenu = false;
		minecraft.gui.setScreen(new LevelManagerScreen());
	}

	private void save() {
		problems.clear();
		try {
			ClientPlayNetworking.send(new LevelSave(Optional.ofNullable(draft.id), draft.toJson().toString()));
		} catch (TaskDraft.Invalid e) {
			problems.add(e.problem);
			rebuildWidgets();
		}
	}

	/** Сервер проверил уровень: при ошибках остаёмся в редакторе и показываем их. */
	void onSaveResult(List<String> serverProblems) {
		if (serverProblems.isEmpty()) {
			toManager();
			return;
		}
		problems.clear();
		for (String problem : serverProblems) problems.add(Component.literal(problem));
		rebuildWidgets();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		int y = height - 30 - problems.size() * 11;
		for (Component problem : problems) {
			g.centeredText(font, problem, width / 2, y, 0xFFFF6060);
			y += 11;
		}
	}
}
