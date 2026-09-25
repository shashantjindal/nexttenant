package com.househunting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * <p>The package of this class is the scan root: Spring discovers components in
 * {@code com.househunting} and every package beneath it. Anything outside that
 * tree is invisible to the container, so this class stays at the top level.
 *
 * <p>{@code @SpringBootApplication} bundles three annotations:
 * <ul>
 *   <li>{@code @Configuration}      — this class may declare beans</li>
 *   <li>{@code @EnableAutoConfiguration} — configure from what is on the classpath</li>
 *   <li>{@code @ComponentScan}      — find components from here downward</li>
 * </ul>
 */
@SpringBootApplication
public class HouseHuntingApplication {

    public static void main(String[] args) {
        SpringApplication.run(HouseHuntingApplication.class, args);
    }
}
