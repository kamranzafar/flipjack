/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kamranzafar.flipjack.openfeature;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import org.kamranzafar.flipjack.FeatureContext;
import org.kamranzafar.flipjack.FeatureToggles;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Toggles evaluated by an OpenFeature {@link Client}, so any OpenFeature provider (flagd,
 * Unleash, LaunchDarkly, ...) can drive flipjack's routing. Evaluation, targeting and rollouts
 * stay with the provider.
 * <pre>{@code
 * FeatureToggles toggles = OpenFeatureToggles.builder(OpenFeatureAPI.getInstance().getClient())
 *         .requireReady(Duration.ofSeconds(10))
 *         .build()
 *         .withOverrides();
 * }</pre>
 * <ul>
 *   <li>Each feature is a boolean flag. A {@link FeatureContext} becomes the evaluation context:
 *       the subject is the targeting key and the attributes are string attributes.
 *       {@link FeatureContext#EMPTY} adds nothing, so the API and client contexts still apply.</li>
 *   <li>OpenFeature never throws: a missing flag, a provider that isn't ready, a type mismatch
 *       or any other error returns {@code defaultEnabled} and is passed to
 *       {@link Builder#onEvaluationError}. Wrapping this source in {@code failSafe(...)} is
 *       unnecessary, and so is {@code cached(...)}, since providers keep flags in memory.</li>
 *   <li>OpenFeature can't list its flags, so {@link #knownFeatures()} is empty and the
 *       startup consistency check is skipped. A misspelt feature name shows up as a
 *       {@link ErrorCode#FLAG_NOT_FOUND} error the first time it is evaluated.</li>
 * </ul>
 */
public final class OpenFeatureToggles implements FeatureToggles {

    /**
     * Receives evaluations that ended with an error code.
     */
    @FunctionalInterface
    public interface ErrorHandler {
        void onError(String feature, FlagEvaluationDetails<Boolean> details);
    }

    /**
     * Longest single wait for a provider event, in case an event arrives before the state changes.
     */
    private static final long READY_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private final Client client;
    private final boolean defaultEnabled;
    private final ErrorHandler errorHandler;
    private final Consumer<String> recovered;
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    private OpenFeatureToggles(Builder builder) {
        this.client = builder.client;
        this.defaultEnabled = builder.defaultEnabled;
        this.errorHandler = builder.errorHandler;
        this.recovered = builder.recovered;
        if (builder.readyTimeout != null) {
            awaitReady(builder.readyTimeout);
        }
    }

    public static Builder builder(Client client) {
        return new Builder(client);
    }

    @Override
    public boolean isEnabled(String feature) {
        return enabled(feature, client.getBooleanDetails(feature, defaultEnabled));
    }

    @Override
    public boolean isEnabled(String feature, FeatureContext context) {
        if (context.isEmpty()) {
            return isEnabled(feature);
        }
        return enabled(feature, client.getBooleanDetails(feature, defaultEnabled, evaluationContext(context)));
    }

    /**
     * The client evaluations go through, for anything this adapter doesn't cover.
     */
    public Client client() {
        return client;
    }

    /**
     * Does nothing: the provider belongs to the application, which shuts it down through
     * {@code OpenFeatureAPI}. Closing the toggles never stops the provider.
     */
    @Override
    public void close() {
    }

    private boolean enabled(String feature, FlagEvaluationDetails<Boolean> details) {
        if (details.getErrorCode() == null) {
            if (!failing.isEmpty() && failing.remove(feature)) {
                recovered.accept(feature);
            }
        } else {
            failing.add(feature);
            report(feature, details);
        }
        Boolean value = details.getValue();
        return value != null ? value : defaultEnabled;
    }

    private void report(String feature, FlagEvaluationDetails<Boolean> details) {
        try {
            errorHandler.onError(feature, details);
        } catch (RuntimeException ignored) {
            // a broken error handler must not break the request
        }
    }

    private static EvaluationContext evaluationContext(FeatureContext context) {
        Map<String, Value> attributes = new HashMap<>();
        context.attributes().forEach((key, value) -> attributes.put(key, new Value(value)));
        return context.subject()
                .map(subject -> new ImmutableContext(subject, attributes))
                .orElseGet(() -> new ImmutableContext(attributes));
    }

    /**
     * Waits until the provider is ready. Fails at once if it is FATAL, since it won't recover;
     * keeps waiting through ERROR and STALE, which can.
     */
    private void awaitReady(Duration timeout) {
        Object signal = new Object();
        Consumer<EventDetails> wake = event -> {
            synchronized (signal) {
                signal.notifyAll();
            }
        };
        client.onProviderReady(wake);
        client.onProviderError(wake);
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            synchronized (signal) {
                ProviderState state = client.getProviderState();
                while (state != ProviderState.READY) {
                    if (state == ProviderState.FATAL) {
                        throw new IllegalStateException("OpenFeature provider failed fatally; it won't become ready");
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new IllegalStateException("OpenFeature provider not ready after " + timeout
                                + " (state: " + state + ")");
                    }
                    TimeUnit.NANOSECONDS.timedWait(signal, Math.min(remaining, READY_POLL_NANOS));
                    state = client.getProviderState();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the OpenFeature provider", e);
        } finally {
            client.removeHandler(ProviderEvent.PROVIDER_READY, wake);
            client.removeHandler(ProviderEvent.PROVIDER_ERROR, wake);
        }
    }

    public static final class Builder {

        private final Client client;
        private boolean defaultEnabled;
        private Duration readyTimeout;
        private ErrorHandler errorHandler;
        private Consumer<String> recovered;

        private Builder(Client client) {
            this.client = Objects.requireNonNull(client, "client");
            errorLog(new ThrottledErrorLog());
        }

        /**
         * State returned whenever OpenFeature can't answer: a missing flag, a provider that
         * isn't ready, or any other error (default {@code false}).
         */
        public Builder defaultEnabled(boolean defaultEnabled) {
            this.defaultEnabled = defaultEnabled;
            return this;
        }

        /**
         * Fail {@link #build()} if the provider isn't ready within {@code timeout}, or at once
         * if it has failed fatally. By default
         * the toggles are built at once and answer {@code defaultEnabled} until it is.
         */
        public Builder requireReady(Duration timeout) {
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.readyTimeout = timeout;
            return this;
        }

        /**
         * Receives every evaluation that ended with an error code. By default each is logged
         * as a warning, once per feature and error code until that feature evaluates without
         * an error again.
         */
        public Builder onEvaluationError(ErrorHandler errorHandler) {
            this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
            this.recovered = feature -> { };
            return this;
        }

        /**
         * Uses {@code log} as the default handler, told when a failing feature recovers.
         */
        Builder errorLog(ThrottledErrorLog log) {
            this.errorHandler = log;
            this.recovered = log::recovered;
            return this;
        }

        public OpenFeatureToggles build() {
            return new OpenFeatureToggles(this);
        }
    }
}
