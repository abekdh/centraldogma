/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.server.internal.api;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.centraldogma.common.Entry;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.server.internal.storage.repository.Repository;

import io.netty.channel.EventLoop;

/**
 * A service class for watching repository or a file.
 */
public final class WatchService {

    private static final CancellationException CANCELLATION_EXCEPTION =
            Exceptions.clearTrace(new CancellationException("watch timed out"));

    private static final double JITTER_RATE = 0.2;

    private final Set<CompletableFuture<?>> pendingFutures =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Awaits and retrieves the latest revision of the commit that changed the file that matches the specified
     * {@code pathPattern} since the specified {@code lastKnownRevision}. This will wait until the specified
     * {@code timeoutMillis} passes. If there's no change during the time, the returned future will be
     * exceptionally completed with the {@link CancellationException}.
     */
    public CompletableFuture<Revision> watchRepository(Repository repo, Revision lastKnownRevision,
                                                       String pathPattern, long timeoutMillis) {
        final CompletableFuture<Revision> result = repo.watch(lastKnownRevision, pathPattern);
        if (result.isDone()) {
            return result;
        }

        scheduleTimeout(result, timeoutMillis);
        return result;
    }

    /**
     * Awaits and retrieves the latest revision of the commit that changed the file that matches the specified
     * {@link Query} since the specified {@code lastKnownRevision}. This will wait until the specified
     * {@code timeoutMillis} passes. If there's no change during the time, the returned future will be
     * exceptionally completed with the {@link CancellationException}.
     */
    public <T> CompletableFuture<Entry<T>> watchFile(Repository repo, Revision lastKnownRevision,
                                                     Query<T> query, long timeoutMillis) {
        final CompletableFuture<Entry<T>> result = repo.watch(lastKnownRevision, query);
        if (result.isDone()) {
            return result;
        }

        scheduleTimeout(result, timeoutMillis);
        return result;
    }

    private <T> void scheduleTimeout(CompletableFuture<T> result, long timeoutMillis) {
        pendingFutures.add(result);

        final ScheduledFuture<?> timeoutFuture;
        if (timeoutMillis > 0) {
            timeoutMillis = applyJitter(timeoutMillis);
            final EventLoop eventLoop = RequestContext.current().eventLoop();
            timeoutFuture = eventLoop.schedule(() -> result.completeExceptionally(CANCELLATION_EXCEPTION),
                                               timeoutMillis, TimeUnit.MILLISECONDS);
        } else {
            timeoutFuture = null;
        }

        result.whenComplete((revision, cause) -> {
            if (timeoutFuture != null) {
                timeoutFuture.cancel(true);
            }
            pendingFutures.remove(result);
        });
    }

    private static long applyJitter(long timeoutMillis) {
        // Specify the 'bound' value that's slightly greater than 1.0 because it's exclusive.
        final double rate = ThreadLocalRandom.current().nextDouble(1 - JITTER_RATE, 1.001);
        if (rate < 1) {
            return (long) (timeoutMillis * rate);
        } else {
            return timeoutMillis;
        }
    }
}
