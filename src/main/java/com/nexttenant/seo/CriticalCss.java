package com.nexttenant.seo;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The stylesheet for what a visitor sees before scrolling, held in memory and
 * inlined into every page.
 *
 * <p><b>Why inline at all.</b> {@code app.css} is a render-blocking request:
 * the browser will not paint until it arrives. On a Mumbai 4G connection that
 * is a round trip the visitor spends looking at a white screen, and it lands
 * squarely on Largest Contentful Paint, which is the metric Google actually
 * uses. Inlining the handful of rules that style the header and the top of the
 * page means the first paint needs no network at all.
 *
 * <p><b>Why it duplicates app.css rather than being carved out of it.</b> A
 * split would mean every rule lives in exactly one of two files and a mistake
 * leaves it in neither — a bug that appears only below the fold, only on pages
 * nobody tested, and only in production. Here the file is additive: if it goes
 * stale the page still renders correctly from {@code app.css} a moment later.
 * The cost of being wrong is a flash of unstyled content; the cost of being
 * wrong the other way is a broken page.
 *
 * <p><b>Why a bean and not a {@code th:include}.</b> Thymeleaf would read the
 * file from disk on every request in production as well as in development.
 * This reads it once at startup. It also fails loudly at startup if the file is
 * missing, rather than quietly serving pages with no critical CSS for a week.
 */
@Component
public class CriticalCss {

    private static final Logger log = LoggerFactory.getLogger(CriticalCss.class);

    /**
     * Above which this has stopped being an optimisation.
     *
     * <p>Inlined CSS is paid on every single page load and is never cached, so
     * past a few kilobytes it costs more than the round trip it saves. This is
     * a warning rather than a failure because the right response is to trim the
     * file, not to take the site down.
     */
    private static final int WARN_BYTES = 14_000;

    private static final String PATH = "static/css/critical.css";

    private String css = "";

    @PostConstruct
    void load() throws IOException {
        ClassPathResource resource = new ClassPathResource(PATH);
        try (InputStream in = resource.getInputStream()) {
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        if (css.length() > WARN_BYTES) {
            log.warn("critical.css is {} bytes, inlined into every page. "
                            + "Past about {} it costs more than the round trip it saves.",
                    css.length(), WARN_BYTES);
        }
    }

    /** The CSS itself, written into a {@code <style>} block by the layout. */
    public String getInline() {
        return css;
    }
}
