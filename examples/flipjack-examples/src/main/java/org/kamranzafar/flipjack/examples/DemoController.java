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

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/demo")
public class DemoController {

    private final GreetingService greeting;
    private final ShippingService shipping;
    private final TaxExampleService tax;
    private final ExampleFeatureToggles toggles;

    public DemoController(GreetingService greeting, ShippingService shipping,
                          TaxExampleService tax, ExampleFeatureToggles toggles) {
        this.greeting = greeting;
        this.shipping = shipping;
        this.tax = tax;
        this.toggles = toggles;
    }

    @GetMapping("/greeting")
    public String greeting(@RequestParam(defaultValue = "world") String name) {
        return greeting.greet(name);
    }

    @GetMapping("/shipping")
    public String shipping(@RequestParam(defaultValue = "order-123") String order) {
        return shipping.ship(order);
    }

    @GetMapping("/tax")
    public String tax(@RequestParam(defaultValue = "EU") String region) {
        return tax.describeTax(region);
    }

    @PostMapping("/toggles/{feature}")
    public Map<String, Object> setToggle(@PathVariable String feature, @RequestParam boolean enabled) {
        if (!toggles.set(feature, enabled)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown feature: " + feature);
        }
        return Map.of("feature", feature, "enabled", enabled);
    }
}
