package com.terraformersmc.modmenu.api;

/** Заглушка для компиляции. В готовый jar не попадает (см. build.gradle). */
public interface ModMenuApi {
	default ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> null;
	}
}
