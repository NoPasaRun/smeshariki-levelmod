package com.smeshariki.level.definition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

import net.minecraft.resources.Identifier;

/**
 * Откуда окно уровней берёт превью. Пока только картинка; отрывок первой кат-сцены станет ещё одной записью здесь (кат-сцена,
 * начало, конец) и веткой в {@code LevelManagerScreen.drawPreview}, формат уровней и окно при этом не меняются.
 */
public sealed interface LevelPreview {
	/** Уровень без своего превью: такой текстуры нет, и окно рисует заглушку. */
	LevelPreview NONE = new Image(Identifier.fromNamespaceAndPath("levelmod", "textures/gui/level/none.png"));
	Codec<LevelPreview> CODEC = Codec.STRING.<LevelPreview>dispatch("type", LevelPreview::type, type -> Image.MAP_CODEC);

	String type();

	/** Текстура из ресурсов клиента, рисуется целиком в рамке 16:9. */
	record Image(Identifier texture) implements LevelPreview {
		static final MapCodec<Image> MAP_CODEC = Identifier.CODEC.fieldOf("texture").xmap(Image::new, Image::texture);

		@Override
		public String type() {
			return "image";
		}
	}
}
