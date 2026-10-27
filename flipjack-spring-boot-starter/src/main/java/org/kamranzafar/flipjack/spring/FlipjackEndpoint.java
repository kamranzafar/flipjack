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

import org.kamranzafar.flipjack.FeatureToggles;
import org.kamranzafar.flipjack.Toggled;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Exposes the current state of Flipjack features through the Spring Boot
 * Actuator endpoint.
 *
 * <p>The endpoint is read-only and is available as
 * {@code /actuator/flipjack} when the endpoint is exposed.</p>
 */
@Endpoint(id = "flipjack")
public class FlipjackEndpoint {

    private final FlipjackProperties properties;
    private final FeatureToggles toggles;
    private final ObjectProvider<Toggled> toggledBeans;
    private final BeanFactory beanFactory;

    /**
     * Creates the endpoint without an explicit BeanFactory.
     *
     * @param properties Flipjack configuration properties
     * @param toggles feature-toggle provider
     * @param toggledBeans provider for toggled beans
     */
    public FlipjackEndpoint(
            FlipjackProperties properties,
            FeatureToggles toggles,
            ObjectProvider<Toggled> toggledBeans) {

        this(properties, toggles, toggledBeans, null);
    }

    /**
     * Creates the Flipjack actuator endpoint.
     *
     * @param properties Flipjack configuration properties
     * @param toggles feature-toggle provider
     * @param toggledBeans provider for toggled beans
     * @param beanFactory Spring bean factory used to discover toggled fields
     */
    public FlipjackEndpoint(
            FlipjackProperties properties,
            FeatureToggles toggles,
            ObjectProvider<Toggled> toggledBeans,
            BeanFactory beanFactory) {

        this.properties = properties;
        this.toggles = toggles;
        this.toggledBeans = toggledBeans;
        this.beanFactory = beanFactory;
    }

    /**
     * Returns all configured and discovered features with their effective state.
     *
     * @return feature names mapped to their enabled state
     */
    @ReadOperation
    public Map<String, Boolean> features() {

        TreeSet<String> names =
                new TreeSet<>(properties.getToggles().keySet());

        names.addAll(
                ApplicationFeatures.used(
                        beanFactory,
                        toggledBeans
                )
        );

        Map<String, Boolean> result = new TreeMap<>();

        names.forEach(
                name -> result.put(
                        name,
                        toggles.isEnabled(name)
                )
        );

        return result;
    }

    /**
     * Returns the effective state of one feature.
     *
     * @param name feature name
     * @return feature name and enabled state
     */
    @ReadOperation
    public Map<String, Object> feature(@Selector String name) {

        return Map.of(
                "feature",
                name,
                "enabled",
                toggles.isEnabled(name)
        );
    }
}