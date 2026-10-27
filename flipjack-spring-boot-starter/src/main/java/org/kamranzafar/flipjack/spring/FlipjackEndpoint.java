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

import org.kamranzafar.flipjack.FeatureContext;
import org.kamranzafar.flipjack.FeatureToggles;
import org.kamranzafar.flipjack.Toggled;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Read-only {@code /actuator/flipjack} endpoint: every feature that is configured or
 * checked by a toggled bean (class or method level) or by a toggled step held in a bean's
 * fields, with its effective state.
 */
@Endpoint(id = "flipjack")
public class FlipjackEndpoint {

    private final FlipjackProperties properties;
    private final FeatureToggles toggles;
    private final ObjectProvider<Toggled> toggledBeans;
    private final BeanFactory beanFactory;

    public FlipjackEndpoint(FlipjackProperties properties, FeatureToggles toggles,
                            ObjectProvider<Toggled> toggledBeans) {
        this(properties, toggles, toggledBeans, null);
    }

    /**
     * With a bean factory, toggled steps held in any bean's fields are listed too.
     */
    public FlipjackEndpoint(FlipjackProperties properties, FeatureToggles toggles,
                            ObjectProvider<Toggled> toggledBeans, BeanFactory beanFactory) {
        this.properties = properties;
        this.toggles = toggles;
        this.toggledBeans = toggledBeans;
        this.beanFactory = beanFactory;
    }

    @ReadOperation
    public Map<String, Boolean> features() {
        TreeSet<String> names = new TreeSet<>(properties.getToggles().keySet());
        names.addAll(ApplicationFeatures.used(beanFactory, toggledBeans));
        Map<String, Boolean> result = new TreeMap<>();
        names.forEach(name -> result.put(name, toggles.isEnabled(name)));
        return result;
    }

    @ReadOperation
    public Map<String, Object> feature(@Selector String name, @Nullable String subject) {
        if (subject == null) {
            return Map.of("feature", name, "enabled", toggles.isEnabled(name));
        }
        return Map.of("feature", name, "subject", subject,
                "enabled", toggles.isEnabled(name, FeatureContext.of(subject)));
    }
}
