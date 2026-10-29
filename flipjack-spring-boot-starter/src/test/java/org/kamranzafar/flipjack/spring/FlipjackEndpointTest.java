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

import java.lang.reflect.Method;
import org.springframework.boot.actuate.endpoint.OperationType;
import org.springframework.boot.actuate.endpoint.invoke.reflect.OperationMethod;
import org.junit.jupiter.api.Test;
import org.kamranzafar.flipjack.FeatureContext;
import org.kamranzafar.flipjack.FeatureToggles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class FlipjackEndpointTest {

    private final FeatureToggles toggles = new FeatureToggles() {
        @Override
        public boolean isEnabled(String feature) {
            return false;
        }

        @Override
        public boolean isEnabled(String feature, FeatureContext context) {
            return "beta".equals(feature)
                    && context.subject().filter("alice"::equals).isPresent();
        }
    };

    private final FlipjackEndpoint endpoint = new FlipjackEndpoint(
            new FlipjackProperties(), toggles, null);

    @Test
    void withoutSubjectKeepsTheExistingResponseAndEvaluation() {
        assertThat(endpoint.feature("beta", null))
                .containsExactlyInAnyOrderEntriesOf(Map.of("feature", "beta", "enabled", false));
    }

    @Test
    void withSubjectEvaluatesAndIncludesItInTheResponse() {
        assertThat(endpoint.feature("beta", "alice"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "feature", "beta", "subject", "alice", "enabled", true));
    }
    @Test
    void actuatorCanDiscoverEndpointParameterNames() throws Exception {
        Method method = FlipjackEndpoint.class.getMethod(
                "feature", String.class, String.class);
        OperationMethod operation = new OperationMethod(method, OperationType.READ);

        assertThat(operation.getParameters())
                .extracting(parameter -> parameter.getName(),
                        parameter -> parameter.isMandatory())
                .containsExactly(
                        tuple("name", true),
                        tuple("subject", false));
    }
}
