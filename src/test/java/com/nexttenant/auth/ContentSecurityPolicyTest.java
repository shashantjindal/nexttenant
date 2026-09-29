package com.nexttenant.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The Content-Security-Policy, checked against what the pages actually load.
 *
 * <p>Written after the policy silently broke every map in production. CSP
 * failures are invisible server-side: nothing throws, nothing is logged, the
 * page returns 200, and the only symptom is a grey rectangle where a map
 * should be. These assertions are the only place that failure becomes visible
 * before a user sees it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContentSecurityPolicyTest {

    @Autowired private MockMvc mockMvc;

    private String policy() throws Exception {
        return mockMvc.perform(get("/"))
                .andReturn().getResponse().getHeader("Content-Security-Policy");
    }

    @Test
    @DisplayName("map tiles are allowed from the exact host Leaflet requests")
    void tilesAreAllowed() throws Exception {
        String csp = policy();

        // approx-map.js and pin-map.js both request
        // https://tile.openstreetmap.org/{z}/{x}/{y}.png — no subdomain.
        //
        // A CSP wildcard needs at least one label in front of it, so
        // *.tile.openstreetmap.org matches a.tile.openstreetmap.org and NOT
        // tile.openstreetmap.org. Listing only the wildcard blocked every
        // tile, and the map rendered as a grey box with no error anywhere.
        assertThat(csp).contains("https://tile.openstreetmap.org");
    }

    @Test
    @DisplayName("Leaflet's marker images are allowed from where its CSS lives")
    void markerIconsAreAllowed() throws Exception {
        String csp = policy();

        // Leaflet is loaded from unpkg, and its stylesheet references
        // marker-icon.png relative to itself. Allowing the CSS but not the
        // images it pulls in gives a working map with a broken pin, which
        // reads as a bug in our own code.
        int imgSrc = csp.indexOf("img-src");
        int nextDirective = csp.indexOf(';', imgSrc);
        String imgPolicy = csp.substring(imgSrc, nextDirective);

        assertThat(imgPolicy).contains("https://unpkg.com");
    }

    @Test
    @DisplayName("the wizard can still preview a photo before it is uploaded")
    void blobAndDataUrisAreAllowed() throws Exception {
        String csp = policy();

        // The photo step shows a preview straight from the file input, which
        // is a blob: URL. Dropping these from img-src would break the upload
        // step while leaving everything else working.
        assertThat(csp).contains("blob:");
        assertThat(csp).contains("data:");
    }

    @Test
    @DisplayName("scripts stay same-origin apart from the two map providers")
    void scriptSourcesStayNarrow() throws Exception {
        String csp = policy();
        String scriptSrc = csp.substring(csp.indexOf("script-src"),
                csp.indexOf(';', csp.indexOf("script-src")));

        assertThat(scriptSrc).contains("'self'");
        assertThat(scriptSrc).contains("https://unpkg.com");

        // THE assertion in this class. 'unsafe-inline' on script-src is what
        // an injected <script> needs to run, and it is the single change most
        // likely to be made in frustration while debugging something else.
        assertThat(scriptSrc).doesNotContain("unsafe-inline");
        assertThat(scriptSrc).doesNotContain("unsafe-eval");
    }

    @Test
    @DisplayName("forms may only post back to us")
    void formsCannotPostElsewhere() throws Exception {
        // So an injected form cannot exfiltrate a CSRF token or a phone
        // number somebody was in the middle of typing.
        assertThat(policy()).contains("form-action 'self'");
    }

    @Test
    @DisplayName("the site cannot be framed")
    void framingIsRefused() throws Exception {
        assertThat(policy()).contains("frame-ancestors 'none'");
    }
}
