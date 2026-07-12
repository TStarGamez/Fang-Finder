package dev.fangfinder;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu integration. This class is only instantiated by ModMenu itself
 * (via the "modmenu" entrypoint), so it is safe when ModMenu is absent.
 */
public final class FangFinderModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return FangFinderConfigScreen::new;
	}
}
