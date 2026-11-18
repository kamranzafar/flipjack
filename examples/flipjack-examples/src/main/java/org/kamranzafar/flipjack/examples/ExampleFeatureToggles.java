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
package org.kamranzafar.flipjack.examples;

import org.kamranzafar.flipjack.FeatureContext;
import org.kamranzafar.flipjack.FeatureToggles;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Small mutable feature source used only by the runnable example. */
@Component
public class ExampleFeatureToggles implements FeatureToggles {

    private final Map<String, Boolean> toggles = new ConcurrentHashMap<>(Map.of(
            FeatureNames.PREMIUM_GREETING, true,
            FeatureNames.EXPRESS_SHIPPING, false,
            FeatureNames.REGIONAL_TAX, true));

    @Override
    public boolean isEnabled(String feature) {
        return toggles.getOrDefault(feature, false);
    }

    @Override
    public boolean isEnabled(String feature, FeatureContext context) {
        if (FeatureNames.PREMIUM_GREETING.equals(feature)) {
            return isEnabled(feature) && context.subject().filter("alice"::equals).isPresent();
        }
        return isEnabled(feature);
    }

    @Override
    public Optional<Set<String>> knownFeatures() {
        return Optional.of(Set.copyOf(toggles.keySet()));
    }

    public boolean set(String feature, boolean enabled) {
        if (!toggles.containsKey(feature)) {
            return false;
        }
        toggles.put(feature, enabled);
        return true;
    }
}
