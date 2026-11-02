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

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * The default error handler: logs a warning once per feature and error code, and again only
 * after that feature has evaluated without an error, so an outage doesn't flood the log.
 */
final class ThrottledErrorLog implements OpenFeatureToggles.ErrorHandler {

    private static final System.Logger LOG = System.getLogger(OpenFeatureToggles.class.getName());

    private final Consumer<String> sink;
    private final ConcurrentMap<String, Set<ErrorCode>> logged = new ConcurrentHashMap<>();

    ThrottledErrorLog() {
        this(message -> LOG.log(System.Logger.Level.WARNING, message));
    }

    ThrottledErrorLog(Consumer<String> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public void onError(String feature, FlagEvaluationDetails<Boolean> details) {
        if (logged.computeIfAbsent(feature, key -> ConcurrentHashMap.newKeySet()).add(details.getErrorCode())) {
            sink.accept("OpenFeature evaluation of '" + feature + "' failed (" + details.getErrorCode()
                    + (details.getErrorMessage() == null ? "" : ": " + details.getErrorMessage())
                    + "); using " + details.getValue());
        }
    }

    /**
     * {@code feature} evaluated without an error, so its next error is logged again.
     */
    void recovered(String feature) {
        logged.remove(feature);
    }
}
