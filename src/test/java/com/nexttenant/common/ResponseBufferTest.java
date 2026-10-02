package com.nexttenant.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pages render to the end, with their CSRF token.
 *
 * <p>These exist because of a real outage. Inlining the critical CSS pushed
 * every page's {@code <head>} past Tomcat's 8KB response buffer, so the
 * response committed before the body rendered. Spring Security 6 creates the
 * CSRF token lazily — at the moment a {@code <form th:action>} is rendered —
 * and creating it creates the session, which is illegal once the response is
 * committed. The login page threw mid-render, the connection closed with a
 * half-written document, and the browser showed a white page.
 *
 * <p>Nothing caught it: the status was 200, the logs recorded it as a handled
 * exception, and no test asserted that a page is COMPLETE rather than merely
 * returned. Hence the closing-tag assertions below, which look trivial and are
 * the only thing that would have noticed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ResponseBufferTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("the login page renders to the end, for a signed-out visitor")
    void loginPageIsComplete() throws Exception {
        String html = body("/login");

        // The symptom: output stopped shortly after the <head>, before the
        // form. Everything below was missing.
        assertThat(html).contains("Log in or sign up");
        assertThat(html.strip()).endsWith("</html>");
    }

    @Test
    @DisplayName("the login form carries a CSRF token")
    void loginFormHasCsrfToken() throws Exception {
        // The token is what forced the session creation that failed. Without
        // it the form would be rejected on submit — a different bug, equally
        // invisible from a status code.
        assertThat(body("/login")).contains("_csrf");
    }

    @Test
    @DisplayName("every public page renders to its closing tag")
    void publicPagesAreComplete() throws Exception {
        // Cheap, and the class of failure is "returns 200 and is truncated",
        // which no other assertion in the suite would see.
        for (String path : new String[]{"/", "/flats", "/login", "/guides", "/privacy"}) {
            assertThat(body(path).strip())
                    .as("%s should render to its closing tag", path)
                    .endsWith("</html>");
        }
    }

    @Test
    @DisplayName("the response buffer is wide enough for the pages this app renders")
    void bufferIsLargeEnough() {
        // The listing page is the biggest at roughly 36KB. A buffer smaller
        // than the largest page is the whole bug, so the relationship is
        // asserted rather than left as a comment that drifts.
        assertThat(ResponseBufferConfig.BUFFER_BYTES).isGreaterThanOrEqualTo(48 * 1024);
    }

    private String body(String path) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
