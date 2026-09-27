package com.nexttenant.geo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Registers {@link MapsProperties} and exposes it to every template.
 *
 * <p>Advice rather than an attribute added per controller: three different
 * pages render a map today, and a fourth added next month would otherwise
 * render a blank one until somebody worked out which line was missing.
 */
@Configuration
@EnableConfigurationProperties(MapsProperties.class)
@ControllerAdvice
public class MapsConfig {

    private final MapsProperties maps;

    public MapsConfig(MapsProperties maps) {
        this.maps = maps;
    }

    @ModelAttribute("maps")
    public MapsProperties maps() {
        return maps;
    }
}
