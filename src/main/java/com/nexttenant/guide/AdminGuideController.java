package com.nexttenant.guide;

import com.nexttenant.seo.Markdown;
import com.nexttenant.seo.PageMeta;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

/**
 * Writing and publishing guides.
 *
 * <p>No security annotation, deliberately: {@code SecurityConfig} matches
 * {@code /admin/**} to {@code hasRole("ADMIN")} before {@code anyRequest()}, so
 * a route added under that prefix is protected by default and a forgotten
 * annotation cannot expose one.
 */
@Controller
public class AdminGuideController {

    private final GuideRepository guides;

    public AdminGuideController(GuideRepository guides) {
        this.guides = guides;
    }

    @GetMapping("/admin/guides")
    public String list(Model model) {
        model.addAttribute("guides", guides.findAll());
        model.addAttribute("pageMeta", PageMeta.noindex("Guides — Admin"));
        return "admin/guides";
    }

    @GetMapping("/admin/guides/new")
    public String blank(Model model) {
        model.addAttribute("guide", null);
        model.addAttribute("pageMeta", PageMeta.noindex("New guide — Admin"));
        return "admin/guide-form";
    }

    @GetMapping("/admin/guides/{id}")
    public String edit(@PathVariable UUID id, Model model) {
        Guide guide = guides.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("guide", guide);
        model.addAttribute("preview", Markdown.toHtml(guide.body()));
        model.addAttribute("pageMeta", PageMeta.noindex("Edit guide — Admin"));
        return "admin/guide-form";
    }

    /**
     * Create or update, from one form.
     *
     * <p>{@code id} is empty for a new guide. One handler rather than two
     * because the validation is identical and two copies of it is how the
     * create path ends up accepting a duplicate slug that the edit path
     * rejects.
     */
    @PostMapping("/admin/guides")
    public String save(@RequestParam(required = false) UUID id,
                       @RequestParam String title,
                       @RequestParam(required = false) String slug,
                       @RequestParam String summary,
                       @RequestParam String body,
                       @RequestParam(required = false) String authorName,
                       @RequestParam(defaultValue = "DRAFT") GuideStatus status,
                       Model model) {

        String finalSlug = slug == null || slug.isBlank() ? slugify(title) : slugify(slug);

        String error = validate(finalSlug, title, summary, body, id);
        if (error != null) {
            // Re-render with what they typed still in the boxes. Throwing the
            // text away over a duplicate slug is how somebody loses four
            // paragraphs they will not retype the same way.
            model.addAttribute("error", error);
            model.addAttribute("guide", new Guide(id, finalSlug, title, summary, body,
                    authorName, status, null, null, null));
            model.addAttribute("pageMeta", PageMeta.noindex("Edit guide — Admin"));
            return "admin/guide-form";
        }

        if (id == null) {
            guides.create(finalSlug, title.strip(), summary.strip(), body.strip(),
                    blankToNull(authorName), status);
        } else {
            guides.update(id, finalSlug, title.strip(), summary.strip(), body.strip(),
                    blankToNull(authorName), status);
        }

        return "redirect:/admin/guides";
    }

    @PostMapping("/admin/guides/{id}/delete")
    public String delete(@PathVariable UUID id) {
        guides.delete(id);
        return "redirect:/admin/guides";
    }

    /**
     * @return the problem, or null when there is none
     */
    private String validate(String slug, String title, String summary, String body, UUID id) {
        if (slug.isBlank()) {
            return "The title needs at least one letter or digit, so there is something to "
                    + "build a URL from.";
        }
        if (title.isBlank() || summary.isBlank() || body.isBlank()) {
            return "Title, summary and body are all needed. The summary is the meta "
                    + "description and the card on the index page.";
        }
        if (summary.length() > 300) {
            return "The summary is " + summary.length() + " characters. Keep it under 300 — "
                    + "Google shows about 155 of them.";
        }
        if (guides.slugTaken(slug, id)) {
            return "A guide already lives at /guides/" + slug + ". Change the URL.";
        }
        return null;
    }

    /**
     * Title to URL segment.
     *
     * <p>Same shape as {@link com.nexttenant.listing.ListingUrls}'s, kept here
     * rather than shared because the two have different futures: a listing slug
     * is decoration over a UUID and can change freely, while this IS the
     * identity of the page and changing it breaks every link to it. Sharing the
     * method would couple a cosmetic choice to a permanent one.
     */
    static String slugify(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
