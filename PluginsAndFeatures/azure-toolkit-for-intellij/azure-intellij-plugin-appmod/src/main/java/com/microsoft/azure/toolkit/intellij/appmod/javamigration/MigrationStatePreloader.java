/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.intellij.appmod.javamigration;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.microsoft.azure.toolkit.intellij.appmod.common.AppModPluginInstaller;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Preloads migration state when project opens.
 * This ensures that when user opens the context menu, the data is already available.
 * Delegates to the shared {@link MigrationStateService} so all UI surfaces share one cache.
 */
@Slf4j
public class MigrationStatePreloader implements ProjectActivity {

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        // Only preload if AppMod plugin is installed
        if (!AppModPluginInstaller.isAppModPluginInstalled()) {
            log.info("[MigrationStatePreloader] AppMod plugin not installed, skipping preload");
            return Unit.INSTANCE;
        }

        final MigrationStateService service = MigrationStateService.getInstance(project);
        if (service.isLoaded()) {
            log.debug("[MigrationStatePreloader] Already loaded, skipping");
            return Unit.INSTANCE;
        }

        // Compute and cache asynchronously; this also notifies any already-built UI surfaces.
        log.info("[MigrationStatePreloader] Starting preload for project: {}", project.getName());
        service.refreshAsync();

        return Unit.INSTANCE;
    }
}
