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

import io.agentscope.harness.agent.filesystem.sandbox.SharedSandboxFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Builds {@link WorkspaceManager} instances whose filesystem is backed by a per-{@code (userId,
 * agentId)} live sandbox from {@link UserSandboxRegistry}.
 *
 * <p>The same sandbox instance backs both the agent runtime (via
 * {@link io.agentscope.harness.agent.sandbox.SandboxContext#getExternalSandbox()
 * SandboxContext.externalSandbox} injected by
 * {@link io.agentscope.builder.runtime.gateway.HarnessGateway}) and every browser controller's
 * workspace operation. This is what makes browsing work outside of an agent call: the
 * {@code SharedSandboxFilesystem} holds the persistent sandbox directly, so there is always a live
 * container to read/write — unlike the agent's {@code SandboxBackedFilesystem} proxy, which only
 * has a sandbox during a call.
 *
 * <p>The {@link Path} returned by {@link WorkspaceManager#getWorkspace()} is a host-side label
 * (used for display and audit), <em>not</em> the on-disk location of the file data. Actual
 * reads/writes go through {@link SharedSandboxFilesystem} into the container at
 * {@code /workspace}.
 */
public final class WorkspaceManagerFactory {

    private final UserSandboxRegistry registry;

    public WorkspaceManagerFactory(UserSandboxRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Returns a {@link WorkspaceManager} for a user-scoped agent. Borrows (and starts on first use)
     * the per-{@code (ownerId, agentId)} sandbox; the returned {@link WorkspaceManager} reads and
     * writes through that sandbox via {@link SharedSandboxFilesystem}.
     *
     * @param ownerId the filesystem-namespacing user id (the agent owner for shared agents)
     * @param agentId the gateway agent id (must match what the gateway uses for
     *     {@code externalSandbox} injection so browsing and runtime share one container)
     * @param hostWorkspaceRoot the agent's host-side workspace root, projected read-only into a
     *     fresh container so seed content is visible; also used as the
     *     {@link WorkspaceManager#getWorkspace()} display label. May be {@code null}.
     */
    public WorkspaceManager forAgent(String ownerId, String agentId, Path hostWorkspaceRoot) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        Path root =
                hostWorkspaceRoot != null
                        ? hostWorkspaceRoot.toAbsolutePath().normalize()
                        : Path.of(".");
        return new WorkspaceManager(
                root, new SharedSandboxFilesystem(registry.borrow(ownerId, agentId, root)));
    }

    /** Equivalent to {@link #forAgent(String, String, Path) forAgent(ownerId, agentId, null)}. */
    public WorkspaceManager forAgent(String ownerId, String agentId) {
        return forAgent(ownerId, agentId, null);
    }

    private static void validateSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be null or blank");
        }
        if (value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException(
                    label + " must not contain path separators or '..': " + value);
        }
    }
}
