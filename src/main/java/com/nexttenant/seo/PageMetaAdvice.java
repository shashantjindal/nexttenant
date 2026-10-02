package com.nexttenant.seo;

import com.nexttenant.common.SiteProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Gives every page the four things the shared {@code <head>} needs.
 *
 * <p>The important one is {@code page}: the default is
 * {@link PageMeta#noindex}, so a controller that says nothing about SEO gets a
 * page that is not indexed, carries no canonical and makes no claims — rather
 * than one that inherits whatever the last page set. Public pages replace it by
 * putting their own {@code page} in the model, which runs after this and wins.
 *
 * <p>That direction is chosen on purpose. Most pages here are signed-in ones:
 * the dashboard, the wizard, requests, moderation. A default of "index me"
 * means every page added later is published to search engines unless somebody
 * remembers otherwise, and nobody finds out until a user's dashboard appears in
 * a search result. A missed opt-in costs traffic; a missed opt-out costs
 * privacy, and only one of those is recoverable.
 *
 * <p>Advice rather than an attribute added per controller, for the same reason
 * {@code MapsConfig} is: fifteen controllers render pages, and the sixteenth
 * would otherwise render a head with holes in it until somebody worked out
 * which line was missing.
 */
@Configuration
@EnableConfigurationProperties(SeoProperties.class)
@ControllerAdvice
public class PageMetaAdvice {

    private final SiteProperties site;
    private final SeoProperties seo;
    private final CriticalCss criticalCss;

    public PageMetaAdvice(SiteProperties site, SeoProperties seo, CriticalCss criticalCss) {
        this.site = site;
        this.seo = seo;
        this.criticalCss = criticalCss;
    }

    @ModelAttribute("pageMeta")
    public PageMeta defaultMeta() {
        return PageMeta.noindex("NextTenant");
    }

    /**
     * Scheme and host, for turning the paths in {@link PageMeta} into the
     * absolute URLs that canonical and Open Graph tags require.
     *
     * <p>Both are ignored when relative — a relative canonical is tolerated by
     * Google and by nothing else, and Open Graph has no notion of a document
     * base at all, so a relative {@code og:image} is simply no image.
     */
    @ModelAttribute("baseUrl")
    public String baseUrl() {
        return site.baseUrl();
    }

    @ModelAttribute("seo")
    public SeoProperties seo() {
        return seo;
    }

    @ModelAttribute("criticalCss")
    public String criticalCss() {
        return criticalCss.getInline();
    }
}
