/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.web.workspace;

import io.agentscope.builder.web.catalog.AgentCatalogService;
import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared helper that resolves a {@link WorkspaceManager} for a {@code (userId, agentId)} pair,
 * transparently routing through the persistent sandbox registry in sandbox mode.
 *
 * <p>In sandbox mode ({@code builder.sandbox.enabled=true}), every agent's filesystem is a
 * {@code SandboxBackedFilesystem} proxy whose sandbox is only live during an agent call. Browser
 * controllers run outside of a call, so they must route through {@link WorkspaceManagerFactory}
 * (which holds a persistent per-(user, agent) container) instead of
 * {@link HarnessAgent#workspaceFor(String, String)}. This helper centralises that routing logic so
 * every controller stays consistent.
 *
 * <p>In non-sandbox mode the factory is {@code null} and the helper delegates to
 * {@link HarnessAgent#workspaceFor(String, String)} — identical to the pre-sandbox behaviour.
 */
@Component
public class SandboxWorkspaceResolver {

    private final AgentCatalogService catalogService;
    @Nullable private final WorkspaceManagerFactory workspaceManagerFactory;

    public SandboxWorkspaceResolver(
            AgentCatalogService catalogService,
            @Nullable WorkspaceManagerFactory workspaceManagerFactory) {
        this.catalogService = catalogService;
        this.workspaceManagerFactory = workspaceManagerFactory;
    }

    /** {@code true} when sandbox mode is active and the factory is wired. */
    public boolean isSandboxMode() {
        return workspaceManagerFactory != null;
    }

    /**
     * Resolves a {@link WorkspaceManager} for {@code (userId, agentId)}. In sandbox mode the
     * container is borrowed from the shared registry; otherwise
     * {@link HarnessAgent#workspaceFor(String, String)} is used.
     */
    public WorkspaceManager resolve(String userId, String agentId) {
        HarnessAgent agent = catalogService.getOrInstantiateRunningAgent(userId, agentId);
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + agentId);
        }
        AgentDefinition def =
                catalogService
                        .findVisible(userId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found or not accessible: " + agentId));
        String ctxUser;
        if (AgentDefinition.SCOPE_USER.equals(def.scope())) {
            ctxUser = def.ownerId() != null ? def.ownerId() : userId;
        } else {
            ctxUser = userId;
        }
        return resolveWithAgent(agent, ctxUser);
    }

    /**
     * Resolves a {@link WorkspaceManager} given an already-instantiated {@link HarnessAgent} and
     * an explicit ctxUser. Use this when the caller has already resolved the agent and the
     * namespace user id (e.g. {@link AgentSkillsController} which needs both the agent and the
     * owner for different purposes).
     */
    public WorkspaceManager resolveWithAgent(HarnessAgent agent, String ctxUser) {
        if (workspaceManagerFactory != null) {
            Path hostWorkspaceRoot =
                    agent.getWorkspaceManager() != null
                            ? agent.getWorkspaceManager().getWorkspace()
                            : null;
            return workspaceManagerFactory.forAgent(ctxUser, agent.getAgentId(), hostWorkspaceRoot);
        }
        return agent.workspaceFor(ctxUser, null);
    }
}
