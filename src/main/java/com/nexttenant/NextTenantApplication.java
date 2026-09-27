package com.nexttenant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * <p>The package of this class is the scan root: Spring discovers components in
 * {@code com.nexttenant} and every package beneath it. Anything outside that
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
@org.springframework.scheduling.annotation.EnableScheduling
public class NextTenantApplication {

    public static void main(String[] args) {
        SpringApplication.run(NextTenantApplication.class, args);
    }
}
