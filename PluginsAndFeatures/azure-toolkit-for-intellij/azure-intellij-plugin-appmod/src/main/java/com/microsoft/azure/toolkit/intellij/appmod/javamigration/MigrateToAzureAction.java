/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.intellij.appmod.javamigration;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.project.Project;
import com.microsoft.azure.toolkit.intellij.appmod.common.AppModPluginInstaller;
import com.microsoft.azure.toolkit.intellij.appmod.javamigration.MigrationStateService.MigrationState;
import com.microsoft.azure.toolkit.intellij.appmod.javamigration.MigrationStateService.State;
import com.microsoft.azure.toolkit.intellij.appmod.utils.AppModPanelHelper;
import com.microsoft.azure.toolkit.intellij.appmod.utils.AppModUtils;
import com.microsoft.azure.toolkit.intellij.appmod.utils.Constants;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Unified ActionGroup for "Migrate to Azure" functionality.
 * Handles all three states:
 * 1. Plugin NOT installed - direct click triggers installation
 * 2. Plugin installed but no migration options - shows "Open App Mod Panel" action
 * 3. Plugin installed with migration options - sub-menu shows migration options
 *
 * <p>Migration state is owned by the shared {@link MigrationStateService} so this menu always
 * shows the same options as the Azure Explorer and Project Explorer surfaces.</p>
 */
@Slf4j
public class MigrateToAzureAction extends ActionGroup {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /**
     * Gets migration state from the shared service. Returns LOADING if the shared cache is not
     * ready yet (the service triggers async loading) so the menu stays responsive.
     */
    private MigrationState getOrComputeState(Project project) {
        return MigrationStateService.getInstance(project).getState();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        final long startTime = System.currentTimeMillis();
        final Project project = e.getProject();
        if (project == null) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        
        final MigrationState migrationState = getOrComputeState(project);
        log.debug("[MigrateToAzureAction] update - state: {}, took {}ms", migrationState.state, System.currentTimeMillis() - startTime);
        
        // Common settings for all states
        e.getPresentation().setPopupGroup(true);
        e.getPresentation().putClientProperty(ActionUtil.ALWAYS_VISIBLE_GROUP, true);
        
        switch (migrationState.state) {
            case NOT_INSTALLED:
                e.getPresentation().setText("Migrate to Azure (Install " + Constants.APPMOD_NAME + ")");
                e.getPresentation().setPerformGroup(true);
                e.getPresentation().putClientProperty(ActionUtil.SUPPRESS_SUBMENU, true);
                break;
            case LOADING:
            case NO_OPTIONS:
                e.getPresentation().setText("Migrate to Azure (Open " + Constants.APPMOD_NAME + ")");
                e.getPresentation().setPerformGroup(true);
                e.getPresentation().putClientProperty(ActionUtil.SUPPRESS_SUBMENU, true);
                break;
            case HAS_OPTIONS:
                e.getPresentation().setText("Migrate to Azure");
                e.getPresentation().setPerformGroup(false);
                e.getPresentation().putClientProperty(ActionUtil.SUPPRESS_SUBMENU, false);
                break;
        }
        e.getPresentation().setEnabledAndVisible(true);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        final Project project = e.getProject();
        if (project == null) {
            return;
        }
        
        final MigrationState migrationState = getOrComputeState(project);
        log.debug("[MigrateToAzureAction] actionPerformed - state: {}", migrationState.state);
        
        switch (migrationState.state) {
            case NOT_INSTALLED:
                log.info("[MigrateToAzureAction] Install click triggered");
                AppModUtils.logTelemetryEvent("action.click-install");
                AppModPluginInstaller.showInstallConfirmation(project, false,
                    () -> AppModPluginInstaller.installPlugin(project, false));
                break;
            case LOADING:
            case NO_OPTIONS:
                log.info("[MigrateToAzureAction] Opening AppMod panel (state: {})", migrationState.state);
                AppModPanelHelper.openAppModPanel(project, "action");
                break;
            case HAS_OPTIONS:
                // Handled by popup menu
                break;
        }
    }

    @Override
    public AnAction @NotNull [] getChildren(@Nullable AnActionEvent e) {
        final long startTime = System.currentTimeMillis();
        try {
            if (e == null) {
                return AnAction.EMPTY_ARRAY;
            }
            
            final Project project = e.getProject();
            if (project == null) {
                return AnAction.EMPTY_ARRAY;
            }
            
            final MigrationState migrationState = getOrComputeState(project);
            
            if (migrationState.state == State.HAS_OPTIONS) {
                final AnAction[] result = migrationState.nodes.stream()
                    .map(this::convertNodeToAction)
                    .toArray(AnAction[]::new);
                log.debug("[MigrateToAzureAction] getChildren - returned {} actions, took {}ms", result.length, System.currentTimeMillis() - startTime);
                return result;
            }
            
            log.debug("[MigrateToAzureAction] getChildren - no options, took {}ms", System.currentTimeMillis() - startTime);
            return AnAction.EMPTY_ARRAY;
        } catch (Exception ex) {
            log.error("[MigrateToAzureAction] Failed to get children, took {}ms", System.currentTimeMillis() - startTime, ex);
            return AnAction.EMPTY_ARRAY;
        }
    }
    
    private AnAction convertNodeToAction(MigrateNodeData nodeData) {
        if (nodeData.hasChildren()) {
            final DefaultActionGroup subgroup = new DefaultActionGroup(nodeData.getLabel(), true);
            subgroup.getTemplatePresentation().setIcon(AllIcons.Vcs.Changelist);

            try {
                final long loadStartTime = System.currentTimeMillis();
                final List<MigrateNodeData> children = nodeData.isLazyLoading() 
                    ? nodeData.getChildrenLoader().get() 
                    : nodeData.getChildren();
                log.debug("[MigrateToAzureAction] convertNodeToAction - loaded {} children for '{}', lazy={}, took {}ms", 
                    children.size(), nodeData.getLabel(), nodeData.isLazyLoading(), System.currentTimeMillis() - loadStartTime);
            
                for (MigrateNodeData child : children) {
                    if (child.isVisible()) {
                        subgroup.add(convertNodeToAction(child));
                    }
                }
            } catch (Exception e) {
                log.error("[MigrateToAzureAction] Failed to load children for node: {}", nodeData.getLabel(), e);
            }
            return subgroup;
        } else {
            return new AnAction(nodeData.getLabel(), nodeData.getDescription(), AllIcons.Vcs.Changelist) {
                @Override
                public void update(@NotNull AnActionEvent e) {
                    e.getPresentation().setEnabled(nodeData.isEnabled());
                }
                
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    AppModUtils.logTelemetryEvent("action.click-task", Map.of("label", nodeData.getLabel()));
                    nodeData.click(e);
                }
            };
        }
    }
}
