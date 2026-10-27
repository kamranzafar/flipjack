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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.kamranzafar.flipjack.FeatureConsistency;
import org.kamranzafar.flipjack.FeatureConsistencyReport;
import org.kamranzafar.flipjack.FeatureToggles;
import org.kamranzafar.flipjack.Toggled;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks consistency between features used by the application and features
 * available in the configured feature store.
 *
 * <p>The check runs after all singleton beans have been initialized so that
 * toggled beans and inline feature declarations can be discovered before
 * validation takes place.</p>
 */
public class FeatureConsistencyChecker implements SmartInitializingSingleton {

    private static final Log LOG =
            LogFactory.getLog(FeatureConsistencyChecker.class);

    private final FlipjackProperties properties;
    private final ObjectProvider<FeatureToggles> toggles;
    private final ObjectProvider<Toggled> toggledBeans;
    private final BeanFactory beanFactory;

    /**
     * Creates a consistency checker without an explicit BeanFactory.
     *
     * @param properties Flipjack configuration properties
     * @param toggles feature-toggle provider
     * @param toggledBeans provider for toggled beans
     */
    public FeatureConsistencyChecker(
            FlipjackProperties properties,
            ObjectProvider<FeatureToggles> toggles,
            ObjectProvider<Toggled> toggledBeans) {

        this(properties, toggles, toggledBeans, null);
    }

    /**
     * Creates a consistency checker.
     *
     * @param properties Flipjack configuration properties
     * @param toggles feature-toggle provider
     * @param toggledBeans provider for toggled beans
     * @param beanFactory Spring bean factory used to inspect toggled fields
     */
    public FeatureConsistencyChecker(
            FlipjackProperties properties,
            ObjectProvider<FeatureToggles> toggles,
            ObjectProvider<Toggled> toggledBeans,
            BeanFactory beanFactory) {

        this.properties = properties;
        this.toggles = toggles;
        this.toggledBeans = toggledBeans;
        this.beanFactory = beanFactory;
    }

    /**
     * Performs the consistency check after all singleton beans have been
     * instantiated.
     */
    @Override
    public void afterSingletonsInstantiated() {

        FlipjackProperties.ConsistencyCheck mode =
                properties.getConsistencyCheck();

        if (mode == FlipjackProperties.ConsistencyCheck.OFF) {
            return;
        }

        Set<String> used =
                new TreeSet<>(properties.getInlineFeatures());

        used.addAll(
                ApplicationFeatures.used(
                        beanFactory,
                        toggledBeans
                )
        );

        Optional<FeatureConsistencyReport> result =
                FeatureConsistency.check(
                        toggles.getObject(),
                        List.of(),
                        used
                );

        if (result.isEmpty()) {
            LOG.debug(
                    "Flipjack consistency check skipped: the toggle source "
                            + "can't list its features (or hasn't loaded yet)"
            );
            return;
        }

        FeatureConsistencyReport report = result.get();

        if (!report.unusedInStore().isEmpty()) {
            LOG.info(report.describeUnused());
        }

        if (!report.missingFromStore().isEmpty()) {

            if (mode == FlipjackProperties.ConsistencyCheck.FAIL) {
                throw new IllegalStateException(
                        report.describeMissing()
                );
            }

            LOG.warn(report.describeMissing());
        }
    }
}