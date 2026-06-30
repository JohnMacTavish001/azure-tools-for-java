/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.intellij.connector.projectexplorer;

import com.intellij.icons.AllIcons;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.openapi.project.Project;
import com.intellij.ui.tree.LeafState;
import com.intellij.util.messages.MessageBusConnection;
import com.microsoft.azure.toolkit.intellij.appmod.utils.AppModPanelHelper;
import com.microsoft.azure.toolkit.intellij.appmod.utils.AppModUtils;
import com.microsoft.azure.toolkit.intellij.appmod.utils.Constants;
import com.microsoft.azure.toolkit.intellij.common.IntelliJAzureIcons;
import com.microsoft.azure.toolkit.intellij.connector.dotazure.AzureModule;
import com.microsoft.azure.toolkit.intellij.appmod.javamigration.MigrateNodeData;
import com.microsoft.azure.toolkit.intellij.appmod.javamigration.MigrationStateService;
import com.microsoft.azure.toolkit.intellij.appmod.common.AppModPluginInstaller;
import com.microsoft.azure.toolkit.ide.common.icon.AzureIcons;
import com.microsoft.azure.toolkit.lib.common.action.Action;
import com.microsoft.azure.toolkit.lib.common.action.ActionGroup;
import com.microsoft.azure.toolkit.lib.common.action.IActionGroup;
import com.microsoft.azure.toolkit.lib.common.task.AzureTaskManager;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Project Explorer facet node for "Migrate to Azure" functionality.
 * Reads migration options from the shared {@link MigrationStateService} so it stays consistent
 * with the Azure Explorer and context menu surfaces, and listens to
 * {@link MigrationStateService#TOPIC} to refresh when the shared state changes.
 */
@Slf4j
public class MigrateToAzureFacetNode extends AbstractAzureFacetNode<AzureModule> {

    public MigrateToAzureFacetNode(Project project, AzureModule module) {
        super(project, module);
        log.debug("[MigrateToAzureFacetNode] Creating node for project: {}", project.getName());
        // Refresh this node whenever the shared migration state changes. Connection is tied to
        // this node's lifecycle so it is released when the node is disposed. The view update is
        // marshalled to the EDT because rerender() touches Swing/ProjectView APIs.
        final MessageBusConnection connection = project.getMessageBus().connect(this);
        connection.subscribe(MigrationStateService.TOPIC,
            (MigrationStateService.MigrationStateListener) () -> AzureTaskManager.getInstance().runLater(() -> {
                if (!project.isDisposed() && !this.isDisposed()) {
                    updateChildren();
                }
            }));
    }

    /**
     * Computes migration nodes from the shared service (blocking; called off the EDT).
     */
    private List<MigrateNodeData> getMigrationNodes() {
        return MigrationStateService.getInstance(getProject()).getOrComputeNodes();
    }

    /**
     * Checks if there are any visible migration options available.
     * Only returns true if the shared state has already been loaded (non-blocking).
     */
    private boolean hasMigrationOptions() {
        final MigrationStateService.MigrationState state = MigrationStateService.getInstance(getProject()).getCachedState();
        return state != null && !state.nodes.isEmpty();
    }

    /**
     * Checks if the shared migration state has been loaded.
     */
    private boolean isMigrationNodesLoaded() {
        return MigrationStateService.getInstance(getProject()).isLoaded();
    }

    /**
     * Refreshes migration nodes and updates the tree view.
     * Triggers a shared recompute so all surfaces refresh together.
     */
    public void refresh() {
        log.debug("[MigrateToAzureFacetNode] refresh called");
        MigrationStateService.getInstance(getProject()).refreshAsync();
    }
    
    @Nullable
    @Override
    public IActionGroup getActionGroup() {
        final Action<Object> refreshAction = new Action<>(Action.Id.of("user/appmod.refresh_migrate_node"))
            .withLabel("Refresh")
            .withIcon(AzureIcons.Action.REFRESH.getIconPath())
            .withHandler((v, e) -> {
                AppModUtils.logTelemetryEvent("facet.refresh");
                refresh();
            })
            .withAuthRequired(false);
        return new ActionGroup(refreshAction);
    }

    @Override
    public Collection<? extends AbstractAzureFacetNode<?>> buildChildren() {
        final ArrayList<AbstractAzureFacetNode<?>> nodes = new ArrayList<>();
        
        // Convert MigrateNodeData to FacetNode
        for (MigrateNodeData nodeData : getMigrationNodes()) {
            nodes.add(new MigrationNodeWrapper(getProject(), nodeData));
        }
        
        return nodes;
    }

    @Override
    protected void buildView(@Nonnull PresentationData presentation) {
        presentation.setIcon(IntelliJAzureIcons.getIcon(Constants.ICON_APPMOD_PATH));
        
        if (!AppModPluginInstaller.isAppModPluginInstalled()) {
            final String text = "Migrate to Azure (Install GitHub Copilot modernization)";
            presentation.addText(text, com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES);
        } else if (isMigrationNodesLoaded() && !hasMigrationOptions()) {
            // Only show "Open..." if we've already loaded and found no options
            presentation.addText("Migrate to Azure", com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES);
            presentation.setLocationString("Open GitHub Copilot modernization");
        } else {
            // Default state or has options
            presentation.addText("Migrate to Azure", com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES);
        }
    }

    @Override
    public void navigate(boolean requestFocus) {
        log.debug("[MigrateToAzureFacetNode] navigate - appModInstalled: {}, hasMigrationOptions: {}", 
            AppModPluginInstaller.isAppModPluginInstalled(), hasMigrationOptions());
        if (!AppModPluginInstaller.isAppModPluginInstalled()) {
            // Plugin not installed - trigger install on double-click
            log.info("[MigrateToAzureFacetNode] Install click triggered");
            AppModUtils.logTelemetryEvent("facet.click-install");
            AppModPluginInstaller.showInstallConfirmation(getProject(), false,
                () -> AppModPluginInstaller.installPlugin(getProject(), false));
        } else if (isMigrationNodesLoaded() && !hasMigrationOptions()) {
            // No migration options - open App Modernization Panel
            log.info("[MigrateToAzureFacetNode] Opening AppMod panel (no options)");
            AppModPanelHelper.openAppModPanel(getProject(), "facet");
        }
    }

    @Override
    public boolean canNavigate() {
        // Enable navigation when plugin is not installed OR when loaded and no migration options
        return !AppModPluginInstaller.isAppModPluginInstalled() || (isMigrationNodesLoaded() && !hasMigrationOptions());
    }

    @Override
    public @Nonnull LeafState getLeafState() {
        // Use ASYNC to avoid triggering extension point loading synchronously
        // The actual leaf state will be determined when buildChildren() is called
        if (!AppModPluginInstaller.isAppModPluginInstalled()) {
            return LeafState.ALWAYS;
        }
        // ASYNC means IntelliJ will call buildChildren() to determine if there are children
        return LeafState.ASYNC;
    }

    /**
     * Wrapper class that converts MigrateNodeData to AbstractAzureFacetNode for Project Explorer display.
     */
    private static class MigrationNodeWrapper extends AbstractAzureFacetNode<MigrateNodeData> {
        private final MigrateNodeData nodeData;

        protected MigrationNodeWrapper(Project project, MigrateNodeData nodeData) {
            super(project, nodeData);
            this.nodeData = nodeData;
        }

        @Override
        public Collection<? extends AbstractAzureFacetNode<?>> buildChildren() {
            final ArrayList<AbstractAzureFacetNode<?>> children = new ArrayList<>();
            
            // Get children - lazy or static (Project Explorer's buildChildren is already lazy)
            final List<MigrateNodeData> childDataList = nodeData.isLazyLoading()
                ? nodeData.getChildrenLoader().get()
                : nodeData.getChildren();
            
            for (MigrateNodeData child : childDataList) {
                if (child.isVisible()) {
                    children.add(new MigrationNodeWrapper(getProject(), child));
                }
            }
            
            return children;
        }

        @Override
        protected void buildView(@Nonnull PresentationData presentation) {
            presentation.addText(nodeData.getLabel(), com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES);
            
            // Set tooltip if available
            if (nodeData.getDescription() != null) {
                presentation.setTooltip(nodeData.getDescription());
            }
            
            // Use node's icon if available, otherwise use default app_mod icon
            presentation.setIcon(AllIcons.Vcs.Changelist);
        }

        @Override
        public void navigate(boolean requestFocus) {
            // Trigger click handler
            AppModUtils.logTelemetryEvent("facet.click-task", java.util.Map.of("label", nodeData.getLabel()));
            nodeData.doubleClick(null);
        }

        @Override
        public boolean canNavigate() {
            // Enable navigation for leaf nodes OR nodes with click handlers
            return !nodeData.hasChildren() || nodeData.hasClickHandler();
        }

        @Override
        public boolean canNavigateToSource() {
            // Enable source navigation only for leaf nodes
            return !nodeData.hasChildren();
        }

        @Override
        public @Nonnull LeafState getLeafState() {
            // ALWAYS = leaf node (no expand arrow, double-click triggers navigate)
            // NEVER = always show expand arrow
            return nodeData.hasChildren() ? LeafState.NEVER : LeafState.ALWAYS;
        }
    }
}
