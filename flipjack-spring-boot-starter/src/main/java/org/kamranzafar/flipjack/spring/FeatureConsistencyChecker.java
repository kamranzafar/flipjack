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
import org.kamranzafar.flipjack.spring.FlipjackProperties.ConsistencyCheck;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs {@link FeatureConsistency} once all singletons exist (so every toggled bean has been
 * created and a polling source has done its first load), then warns or fails startup
 * according to {@code flipjack.consistency-check}. If the source can't list its features the
 * check is skipped, never failing startup: logged as a warning under {@code fail}, else as info.
 */
public class FeatureConsistencyChecker implements SmartInitializingSingleton {

    private static final Log LOG = LogFactory.getLog(FeatureConsistencyChecker.class);

    private final FlipjackProperties properties;
    private final ObjectProvider<FeatureToggles> toggles;
    private final ObjectProvider<Toggled> toggledBeans;
    private final BeanFactory beanFactory;

    public FeatureConsistencyChecker(FlipjackProperties properties, ObjectProvider<FeatureToggles> toggles,
                                     ObjectProvider<Toggled> toggledBeans) {
        this(properties, toggles, toggledBeans, null);
    }

    /**
     * With a bean factory, toggled steps held in any bean's fields are checked too.
     */
    public FeatureConsistencyChecker(FlipjackProperties properties, ObjectProvider<FeatureToggles> toggles,
                                     ObjectProvider<Toggled> toggledBeans, BeanFactory beanFactory) {
        this.properties = properties;
        this.toggles = toggles;
        this.toggledBeans = toggledBeans;
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ConsistencyCheck mode = properties.getConsistencyCheck();
        if (mode == ConsistencyCheck.OFF) {
            return;
        }
        Set<String> used = new TreeSet<>(properties.getInlineFeatures());
        used.addAll(ApplicationFeatures.used(beanFactory, toggledBeans));
        Optional<FeatureConsistencyReport> result = FeatureConsistency.check(toggles.getObject(), List.of(), used);
        if (result.isEmpty()) {
            String skipped = "Flipjack consistency check skipped: the toggle source can't list its features "
                    + "(or hasn't loaded yet), so misspelt feature names won't be caught at startup";
            if (mode == ConsistencyCheck.FAIL) {
                LOG.warn(skipped + "; consistency-check: fail has no effect with this source");
            } else {
                LOG.info(skipped);
            }
            return;
        }

        FeatureConsistencyReport report = result.get();
        if (!report.unusedInStore().isEmpty()) {
            LOG.info(report.describeUnused());
        }
        if (!report.missingFromStore().isEmpty()) {
            if (mode == ConsistencyCheck.FAIL) {
                throw new IllegalStateException(report.describeMissing());
            }
            LOG.warn(report.describeMissing());
        }
    }
}
