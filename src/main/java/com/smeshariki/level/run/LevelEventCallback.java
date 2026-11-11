package com.smeshariki.level.run;

import com.smeshariki.level.definition.LevelEvent;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/** Для кода, которому нужны события уровней (музыка, показ кат-сцен). Срабатывает на сервере. Связи внутри уровня пишутся триггерами. */
@FunctionalInterface
public interface LevelEventCallback {
	Event<LevelEventCallback> EVENT = EventFactory.createArrayBacked(LevelEventCallback.class, listeners -> (run, event) -> {
		for (LevelEventCallback listener : listeners) listener.onEvent(run, event);
	});

	void onEvent(LevelRun run, LevelEvent event);
}
