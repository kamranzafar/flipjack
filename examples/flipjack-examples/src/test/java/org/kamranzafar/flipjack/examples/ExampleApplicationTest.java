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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExampleApplicationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @Autowired
    private ExampleFeatureToggles toggles;

    @BeforeEach
    void resetToggles() {
        toggles.set(FeatureNames.PREMIUM_GREETING, true);
        toggles.set(FeatureNames.EXPRESS_SHIPPING, false);
        toggles.set(FeatureNames.REGIONAL_TAX, true);
    }

    @Test
    void classLevelToggleUsesTheRequestUserContext() throws Exception {
        HttpResponse<String> alice = get("/demo/greeting?name=Sam", "alice");
        assertEquals(200, alice.statusCode());
        assertTrue(alice.body().contains("premium greeting is enabled"));

        HttpResponse<String> bob = get("/demo/greeting?name=Sam", "bob");
        assertEquals(200, bob.statusCode());
        assertEquals("Hello, Sam.", bob.body());

        HttpResponse<String> aliceState = get("/actuator/flipjack/premium-greeting?subject=alice", null);
        assertEquals(200, aliceState.statusCode());
        assertTrue(aliceState.body().contains("\"enabled\":true"));
        HttpResponse<String> bobState = get("/actuator/flipjack/premium-greeting?subject=bob", null);
        assertEquals(200, bobState.statusCode());
        assertTrue(bobState.body().contains("\"enabled\":false"));
    }

    @Test
    void runtimeChangesAffectMethodFallbackAndActuatorReportsState() throws Exception {
        assertEquals("Standard shipping for order-123", get("/demo/shipping?order=order-123", null).body());

        HttpResponse<String> changed = post("/demo/toggles/express-shipping?enabled=true");
        assertEquals(200, changed.statusCode());
        assertTrue(changed.body().contains("\"enabled\":true"));

        assertEquals("Express shipping for order-123", get("/demo/shipping?order=order-123", null).body());
        HttpResponse<String> state = get("/actuator/flipjack/express-shipping", null);
        assertEquals(200, state.statusCode());
        assertTrue(state.body().contains("\"enabled\":true"));
    }

    @Test
    void eitherStepSelectsTheRegionalOrFlatImplementation() throws Exception {
        assertEquals("Regional tax rules for EU", get("/demo/tax?region=EU", null).body());

        assertEquals(200, post("/demo/toggles/regional-tax?enabled=false").statusCode());

        assertEquals("Flat tax rules for EU", get("/demo/tax?region=EU", null).body());
    }

    private HttpResponse<String> get(String path, String user) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (user != null) {
            request.header("X-Demo-User", user);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + path);
    }
}
