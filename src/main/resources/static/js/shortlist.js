/*
 * The heart, without the page reload.
 *
 * Progressive enhancement, and the distinction matters here more than usual.
 * The markup is a real <form method="post"> that works on its own: it posts,
 * the server toggles, and it redirects back to the page you came from. That is
 * why hearts worked before this file existed — but a redirect reloads the
 * document, which throws away the scroll position, so hearting the ninth card
 * on a search page bounced you to the top.
 *
 * This intercepts the submit and does the same POST with fetch, asking for
 * JSON instead of HTML. The server has two handlers on that one path and picks
 * by Accept header; see DashboardController. Nothing moves, nothing reloads.
 *
 * If this script fails to load, the forms still submit the old way. Saving a
 * room is not a thing that should depend on JavaScript.
 */
(function () {
    'use strict';

    function icon(form) {
        return form.querySelector('button');
    }

    /** Reflects the new state on the button, for sight and for screen readers. */
    function paint(form, saved) {
        var button = icon(form);
        if (!button) {
            return;
        }

        button.classList.toggle('listing-card__save-btn--on', saved);
        button.setAttribute('aria-pressed', saved ? 'true' : 'false');
        button.title = saved ? 'Remove from wishlist' : 'Save to wishlist';

        // aria-pressed alone is not enough: a button whose accessible name
        // still says "Save" while it is pressed reads as a contradiction.
        var label = button.querySelector('.visually-hidden');
        if (label) {
            label.textContent = saved ? 'Remove from wishlist' : 'Save to wishlist';
        }

        // The detail page's button is a wide one with visible text rather than
        // an icon, so it carries the two labels as data attributes.
        if (form.hasAttribute('data-shortlist-text')) {
            button.textContent = saved
                    ? form.getAttribute('data-saved-label')
                    : form.getAttribute('data-unsaved-label');
        }
    }

    /** Keeps the header count honest without refetching the page. */
    function paintCount(total) {
        var badge = document.querySelector('[data-wishlist-count]');
        if (!badge) {
            return;
        }
        badge.textContent = total > 0 ? total : '';
        badge.hidden = total === 0;
    }

    function submit(form) {
        var button = icon(form);
        if (button) {
            // Guards against a double tap firing two toggles, which would
            // cancel each other out and look like the heart did nothing.
            if (button.disabled) {
                return;
            }
            button.disabled = true;
        }

        fetch(form.action, {
            method: 'POST',
            // The CSRF token is already a hidden input in the form, so sending
            // the form itself carries it. Reading it out of a meta tag would be
            // a second place for it to be wrong.
            body: new FormData(form),
            headers: { 'Accept': 'application/json' },
            // Sends the session cookie. Same-origin is the default in modern
            // browsers, but stating it makes the requirement visible.
            credentials: 'same-origin'
        }).then(function (response) {
            if (response.status === 401 || response.status === 403 || response.redirected) {
                // The session expired, or they were never signed in. Fall back
                // to the ordinary form post, which ends at the login page with
                // a sensible message rather than silently doing nothing.
                form.submit();
                return null;
            }
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            return response.json();
        }).then(function (state) {
            if (!state) {
                return;
            }
            paint(form, state.saved);
            paintCount(state.total);

            // On the wishlist page itself, un-hearting means the card no longer
            // belongs on the page. Leaving it there until a refresh makes the
            // click look like it failed.
            if (!state.saved && document.body.hasAttribute('data-wishlist-page')) {
                var card = form.closest('.listing-card');
                if (card) {
                    card.classList.add('listing-card--leaving');
                    window.setTimeout(function () { card.remove(); }, 200);
                }
            }
        }).catch(function () {
            // Offline, or the server is down. Let the browser do it the old
            // way rather than swallow the click.
            form.submit();
        }).finally(function () {
            if (button) {
                button.disabled = false;
            }
        });
    }

    // Delegated from the document, so cards added later — "Load more" on
    // search — are covered without rebinding anything.
    document.addEventListener('submit', function (event) {
        var form = event.target;
        if (form.matches && form.matches('[data-shortlist]')) {
            event.preventDefault();
            submit(form);
        }
    });
}());
