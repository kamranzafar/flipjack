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
package org.kamranzafar.flipjack.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Binds {@code flipjack.*}. Deliberately a mutable JavaBean so Spring Cloud's
 * refresh can rebind it in place at runtime.
 */
@ConfigurationProperties(prefix = "flipjack")
public class FlipjackProperties {

    /**
     * What to do when code and the toggle store disagree, checked once at startup.
     */
    public enum ConsistencyCheck {

        /**
         * Don't check.
         */
        OFF,

        /**
         * Log features missing from the store as warnings and unused ones as info.
         */
        WARN,

        /**
         * Fail startup if code uses a feature the store doesn't have; log unused ones.
         */
        FAIL
    }

    /**
     * State used for features that have no explicit entry.
     */
    private boolean defaultEnabled = false;

    /**
     * Feature name to enabled flag (used by the default, configuration-backed source).
     */
    private Map<String, Boolean> toggles = new LinkedHashMap<>();

    /**
     * Startup check comparing features used by code with features in the store.
     */
    private ConsistencyCheck consistencyCheck = ConsistencyCheck.WARN;

    /**
     * Features checked inline with FeatureToggles.isEnabled, so the check counts them as used.
     */
    private Set<String> inlineFeatures = new LinkedHashSet<>();

    /**
     * Returns the default enabled state for features without an explicit entry.
     *
     * @return {@code true} if features are enabled by default; {@code false} otherwise
     */
    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    /**
     * Sets the default enabled state for features without an explicit entry.
     *
     * @param defaultEnabled the default enabled state
     */
    public void setDefaultEnabled(boolean defaultEnabled) {
        this.defaultEnabled = defaultEnabled;
    }

    /**
     * Returns the configured feature toggles.
     *
     * @return a map of feature names to their enabled states
     */
    public Map<String, Boolean> getToggles() {
        return toggles;
    }

    /**
     * Sets the configured feature toggles.
     *
     * @param toggles a map of feature names to their enabled states
     */
    public void setToggles(Map<String, Boolean> toggles) {
        this.toggles = toggles;
    }

    /**
     * Returns the consistency check mode.
     *
     * @return the configured consistency check mode
     */
    public ConsistencyCheck getConsistencyCheck() {
        return consistencyCheck;
    }

    /**
     * Sets the consistency check mode.
     *
     * @param consistencyCheck the consistency check mode
     */
    public void setConsistencyCheck(ConsistencyCheck consistencyCheck) {
        this.consistencyCheck = consistencyCheck;
    }

    /**
     * Returns the feature names checked inline with {@code FeatureToggles.isEnabled}.
     *
     * @return the set of inline feature names
     */
    public Set<String> getInlineFeatures() {
        return inlineFeatures;
    }

    /**
     * Sets the feature names checked inline with {@code FeatureToggles.isEnabled}.
     *
     * @param inlineFeatures the set of inline feature names
     */
    public void setInlineFeatures(Set<String> inlineFeatures) {
        this.inlineFeatures = inlineFeatures;
    }
}
