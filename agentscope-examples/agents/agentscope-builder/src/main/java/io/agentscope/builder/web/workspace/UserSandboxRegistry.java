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

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the per-{@code (userId, agentId)} live {@link Sandbox} instances used by the
 * agentscope-builder web tier when {@code builder.sandbox.enabled=true}.
 *
 * <p>Both the browser workspace controllers and the agent runtime (via
 * {@link io.agentscope.harness.agent.sandbox.SandboxContext#getExternalSandbox()}) read and write
 * through the sandbox returned by {@link #borrow(String, String, Path)}. Reusing one container per
 * (user, agent) across browser requests and agent turns is what makes the workspace consistent —
 * the file browser sees exactly what the agent wrote on its last turn, because it is the same
 * container. Without this, the per-call {@code SandboxBackedFilesystem} proxy has no sandbox
 * outside an agent call, so every workspace-file endpoint 500s with
 * {@code SandboxConfigurationException}.
 *
 * <p>Lifecycle: a sandbox is created+started lazily on the first {@link #borrow} for a key, kept
 * alive across subsequent borrows, and closed once it has gone idle for {@link #idleTtl}.
 * {@link #shutdownAll()} runs on bean destruction.
 *
 * <p>Threading: {@link ConcurrentHashMap#compute} serialises {@link #borrow} for the same key, so
 * {@link Sandbox#start()} is only invoked once per container. Concurrent borrows on different keys
 * are independent.
 *
 * <p>Multi-replica: this registry is in-memory only. Deployments with more than one replica must
 * use sticky load-balancing by {@code userId} to keep a user's traffic on the same pod.
 */
public final class UserSandboxRegistry {

    private static final Logger log = LoggerFactory.getLogger(UserSandboxRegistry.class);

    /** Default host-side roots projected (read-only) into every fresh container. */
    static final List<String> DEFAULT_PROJECTION_ROOTS =
            List.of("AGENTS.md", "skills", "subagents", "knowledge", ".skills-cache");

    private final SandboxClient<DockerSandboxClientOptions> client;
    private final DockerSandboxClientOptions clientOptions;
    private final Duration idleTtl;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictor;

    /**
     * @param client backend client used to {@link SandboxClient#create create} new sandboxes. Must
     *     be the same instance the agent's {@code DockerFilesystemSpec} uses so spec-defaults and
     *     registry-managed sandboxes share one Docker store.
     * @param clientOptions Docker container options (image, workspace root, network, resource
     *     limits) applied to every browsing/runtime sandbox. Must match the agent's
     *     {@code DockerFilesystemSpec} configuration.
     * @param idleTtl how long a sandbox may sit unused before {@link #evictIdle()} closes it
     * @param evictionPollInterval how often the background scheduler checks for idle sandboxes
     */
    public UserSandboxRegistry(
            SandboxClient<DockerSandboxClientOptions> client,
            DockerSandboxClientOptions clientOptions,
            Duration idleTtl,
            Duration evictionPollInterval) {
        this.client = Objects.requireNonNull(client, "client");
        this.clientOptions = Objects.requireNonNull(clientOptions, "clientOptions");
        this.idleTtl = Objects.requireNonNull(idleTtl, "idleTtl");
        long pollMs =
                Math.max(
                        1_000L,
                        Objects.requireNonNull(evictionPollInterval, "evictionPollInterval")
                                .toMillis());
        this.evictor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "BuilderSandboxRegistry-evictor");
                            t.setDaemon(true);
                            return t;
                        });
        this.evictor.scheduleWithFixedDelay(
                this::evictIdleQuietly, pollMs, pollMs, TimeUnit.MILLISECONDS);
        log.info(
                "BuilderSandboxRegistry initialised: idleTtl={}, evictionPoll={}",
                idleTtl,
                evictionPollInterval);
    }

    /**
     * Returns the live {@link Sandbox} for {@code (userId, agentId)}, creating + starting it on
     * first call. {@code hostWorkspaceRoot} is the agent's host-side workspace root to project
     * (read-only) into a fresh container so seed content (AGENTS.md, skills/, subagents/) is
     * visible; it is only consumed on the cache-miss (create) path and ignored thereafter. May be
     * {@code null} to skip projection entirely (the container starts empty).
     */
    public Sandbox borrow(String userId, String agentId, Path hostWorkspaceRoot) {
        validateSegment("userId", userId);
        validateSegment("agentId", agentId);
        Key key = new Key(userId, agentId);
        Entry entry =
                entries.compute(
                        key,
                        (k, existing) -> {
                            if (existing != null) {
                                existing.touch();
                                return existing;
                            }
                            return new Entry(createAndStart(k, hostWorkspaceRoot));
                        });
        return entry.sandbox;
    }

    /** Equivalent to {@link #borrow(String, String, Path) borrow(userId, agentId, null)}. */
    public Sandbox borrow(String userId, String agentId) {
        return borrow(userId, agentId, null);
    }

    /**
     * Returns the live {@link Sandbox} for {@code (userId, agentId)} if one is already cached.
     * Does NOT create a new container — useful for callers that should not pay the cold-start cost
     * when nothing has happened yet.
     */
    public Optional<Sandbox> peek(String userId, String agentId) {
        validateSegment("userId", userId);
        validateSegment("agentId", agentId);
        Entry e = entries.get(new Key(userId, agentId));
        if (e == null) {
            return Optional.empty();
        }
        e.touch();
        return Optional.of(e.sandbox);
    }

    /**
     * Closes and removes cached sandboxes matching {@code (userId, agentId)}. Safe to call
     * concurrently with {@link #borrow}; the next access simply re-creates the sandbox.
     */
    public void invalidate(String userId, String agentId) {
        validateSegment("agentId", agentId);
        int removed = 0;
        var it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Key k = e.getKey();
            if (!k.agentId().equals(agentId)) {
                continue;
            }
            if (userId != null && !userId.isBlank() && !k.userId().equals(userId)) {
                continue;
            }
            it.remove();
            closeQuietly(k, e.getValue().sandbox, "invalidate");
            removed++;
        }
        if (removed > 0) {
            log.info(
                    "[builder-sandbox-registry] invalidated {} sandbox(es) for agentId={},"
                            + " userId={}",
                    removed,
                    agentId,
                    userId == null ? "(all)" : userId);
        }
    }

    /** Visible for tests. Closes every sandbox whose last access time is older than {@link #idleTtl}. */
    void evictIdle() {
        long cutoff = System.currentTimeMillis() - idleTtl.toMillis();
        entries.entrySet()
                .removeIf(
                        e -> {
                            if (e.getValue().lastAccessMs >= cutoff) {
                                return false;
                            }
                            closeQuietly(e.getKey(), e.getValue().sandbox, "idle");
                            return true;
                        });
    }

    private void evictIdleQuietly() {
        try {
            evictIdle();
        } catch (RuntimeException ex) {
            log.warn("[builder-sandbox-registry] eviction sweep failed: {}", ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void shutdownAll() {
        evictor.shutdownNow();
        for (Map.Entry<Key, Entry> e : entries.entrySet()) {
            closeQuietly(e.getKey(), e.getValue().sandbox, "shutdown");
        }
        entries.clear();
    }

    private Sandbox createAndStart(Key key, Path hostWorkspaceRoot) {
        WorkspaceSpec ws = buildWorkspaceSpec(hostWorkspaceRoot);
        Sandbox sandbox = client.create(ws, new NoopSnapshotSpec(), clientOptions);
        try {
            sandbox.start();
        } catch (Exception startErr) {
            try {
                sandbox.close();
            } catch (Exception closeErr) {
                log.warn(
                        "[builder-sandbox-registry] failed to close half-started sandbox for {}:"
                                + " {}",
                        key,
                        closeErr.getMessage());
            }
            throw new IllegalStateException(
                    "Failed to start sandbox for " + key + ": " + startErr.getMessage(), startErr);
        }
        log.info("[builder-sandbox-registry] started sandbox for {}", key);
        return sandbox;
    }

    /**
     * Builds the workspace projection spec: the host agent workspace root is mounted read-only into
     * the container so seed content (AGENTS.md, skills/, subagents/, knowledge/) is visible. The
     * directory is created on demand to avoid a Docker mount failure when it doesn't exist yet.
     */
    private WorkspaceSpec buildWorkspaceSpec(Path hostWorkspaceRoot) {
        WorkspaceSpec spec = new WorkspaceSpec();
        if (hostWorkspaceRoot == null) {
            return spec;
        }
        Path root = hostWorkspaceRoot.toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to ensure agent workspace dir " + root + ": " + e.getMessage(), e);
        }
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(root.toString());
        projection.setIncludeRoots(DEFAULT_PROJECTION_ROOTS);
        Map<String, WorkspaceEntry> es = new LinkedHashMap<>();
        es.put("__workspace_projection__", projection);
        spec.setEntries(es);
        return spec;
    }

    private void closeQuietly(Key key, Sandbox sandbox, String reason) {
        try {
            sandbox.close();
            log.info("[builder-sandbox-registry] closed sandbox for {} ({})", key, reason);
        } catch (Exception e) {
            log.warn(
                    "[builder-sandbox-registry] failed to close sandbox for {} ({}): {}",
                    key,
                    reason,
                    e.getMessage(),
                    e);
        }
    }

    private static void validateSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be null or blank");
        }
    }

    /** Composite key used to scope a sandbox to one user + agent. */
    public record Key(String userId, String agentId) {
        public Key {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(agentId, "agentId");
        }

        @Override
        public String toString() {
            return "(" + userId + ", " + agentId + ")";
        }
    }

    private static final class Entry {
        final Sandbox sandbox;
        volatile long lastAccessMs;

        Entry(Sandbox sandbox) {
            this.sandbox = sandbox;
            this.lastAccessMs = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessMs = System.currentTimeMillis();
        }
    }
}
