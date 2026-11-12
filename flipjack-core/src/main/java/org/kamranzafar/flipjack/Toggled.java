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
package org.kamranzafar.flipjack;

import java.util.Set;

/**
 * Implemented by every proxy {@link ToggledFeature} creates, so tools such as admin
 * endpoints can discover which features a component checks.
 */
public interface Toggled {

    /**
     * Names of every feature (class and method level) this proxy checks.
     */
    Set<String> toggledFeatures();

    /**
     * Classes named by {@code fallback} in this component's {@link Toggleable} annotations,
     * so a DI integration can check they exist before the first disabled call looks them up.
     */
    default Set<Class<?>> fallbackTypes() {
        return Set.of();
    }
}
