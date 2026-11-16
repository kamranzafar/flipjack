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
import org.kamranzafar.flipjack.spring.FeatureContextProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SpringBootApplication
public class FlipjackExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlipjackExampleApplication.class, args);
    }

    @Bean
    FeatureContextProvider exampleRequestContext() {
        return () -> {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request) {
                String user = request.getRequest().getHeader("X-Demo-User");
                if (user != null && !user.isBlank()) {
                    return FeatureContext.of(user);
                }
            }
            return FeatureContext.EMPTY;
        };
    }
}
