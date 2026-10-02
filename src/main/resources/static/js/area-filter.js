/*
 * Area filter search box.
 *
 * Progressive enhancement. Without JavaScript every area checkbox is present
 * and usable and the filter form is a plain GET that works. This makes finding
 * one faster, and it is also what collapses the list from ~200 entries to the
 * ~84 localities — the sublocalities are in the page from the start and get
 * hidden here, so typing "diamond" can reveal Diamond Garden instantly rather
 * than waiting on a request.
 *
 * Two layers of matching:
 *   1. Instant client-side filtering of the labels already in the page — no
 *      network, no latency, correct for the common case of typing a prefix.
 *   2. When nothing matches locally, ask the server, which uses pg_trgm and so
 *      tolerates the misspellings a substring match cannot ("andhri").
 *
 * The second layer only fires when the first found nothing, so a typical
 * keystroke costs zero requests.
 *
 * A NOTE ON HIDING. Visibility here is the `hidden` attribute and nothing
 * else. That only works because app.css carries a global
 * `[hidden] { display: none !important }` — browsers put that rule in the
 * user-agent stylesheet, where it loses to any author rule setting display,
 * and `.check` sets display: flex. Without it this file runs perfectly,
 * reports the right number of matches, and hides nothing at all. That was the
 * bug.
 */
(function () {
    'use strict';

    var box = document.getElementById('area-search');
    if (!box) {
        return;
    }

    var list = document.getElementById('area-list');
    var hint = document.getElementById('area-hint');
    var labels = Array.prototype.slice.call(list.querySelectorAll('.check'));
    var areaCount = list.getAttribute('data-area-count') || labels.length;
    var pending = null;

    // The search box is hidden until enhanced, so a scripting-disabled browser
    // sees the plain checkboxes rather than a box that does nothing.
    box.parentElement.hidden = false;

    function normalise(text) {
        return (text || '').trim().toLowerCase();
    }

    function isSublocality(label) {
        return label.getAttribute('data-sub') === 'true';
    }

    function isTicked(label) {
        return label.querySelector('input').checked;
    }

    /**
     * Decides which labels are visible for a query.
     *
     * An empty query shows localities only — the list is the eighty-odd places
     * people name, not every street inside them. Anything typed searches the
     * lot.
     *
     * @return how many matched
     */
    function filterLocally(query) {
        var matches = 0;

        labels.forEach(function (label) {
            var hit;

            if (query === '') {
                hit = !isSublocality(label);
            } else {
                var name = normalise(label.getAttribute('data-name'));
                var station = normalise(label.getAttribute('data-station'));
                // Matching the parent too, so typing "chembur" brings up
                // Chembur AND the thirteen places inside it — which is the
                // question somebody typing a locality name is really asking.
                var parent = normalise(label.getAttribute('data-parent'));

                hit = name.indexOf(query) !== -1
                        || station.indexOf(query) !== -1
                        || parent.indexOf(query) !== -1;
            }

            // A ticked area stays visible whatever the query, so nobody loses
            // track of a selection they can no longer see — and so unticking
            // it does not require clearing the search first.
            label.hidden = !(hit || isTicked(label));

            if (hit) {
                matches++;
            }
        });

        return matches;
    }

    function suggest(query) {
        fetch('/flats/areas?q=' + encodeURIComponent(query))
            .then(function (response) {
                return response.ok ? response.json() : [];
            })
            .then(function (areas) {
                if (!areas.length) {
                    // The count comes from the page rather than being written
                    // here. It used to say "22 across Mumbai", which stopped
                    // being true the day the area list grew and stayed wrong
                    // because nothing connected the sentence to the data.
                    hint.textContent =
                            'No area by that name. We cover ' + areaCount + ' across Mumbai.';
                    return;
                }

                var names = areas.map(function (a) { return a.name; });
                hint.textContent = 'Did you mean: ' + names.join(', ') + '?';

                // Reveal the fuzzy matches so they can simply be ticked. Every
                // suggestion the server can return has a checkbox in the page,
                // sublocalities included — otherwise this would name places
                // and then offer no way to choose them.
                labels.forEach(function (label) {
                    if (names.indexOf(label.getAttribute('data-name')) !== -1) {
                        label.hidden = false;
                    }
                });
            })
            .catch(function () {
                // A failed suggestion is not worth an error message: the
                // checkboxes still work.
                hint.textContent = '';
            });
    }

    box.addEventListener('input', function () {
        var query = normalise(box.value);
        hint.textContent = '';

        var matches = filterLocally(query);

        // Debounced, and only when the local pass found nothing. Firing a
        // request per keystroke would send five for "bandra" and show results
        // out of order when they returned out of order.
        window.clearTimeout(pending);
        if (matches === 0 && query.length >= 2) {
            pending = window.setTimeout(function () {
                suggest(query);
            }, 200);
        }
    });

    /*
     * Ticking an area clears the search, ready for the next one.
     *
     * This filter has always been multi-select, and it did not look it. You
     * typed "chembur", ticked it, and the list stayed filtered to Chembur —
     * so adding Powai meant noticing the box still had text in it and
     * clearing it by hand. Most people stopped at one area, which on a site
     * with this little supply is the difference between some results and
     * none.
     *
     * Clearing on tick makes the next search the obvious next action. The
     * ticked area stays visible regardless (see filterLocally), so nothing
     * disappears at the moment it is chosen.
     *
     * Only on TICK. Clearing when somebody unticks would yank the list out
     * from under them mid-correction.
     */
    list.addEventListener('change', function (event) {
        var input = event.target;
        if (!input || input.name !== 'area' || !input.checked) {
            return;
        }
        if (!box.value) {
            return;
        }
        box.value = '';
        hint.textContent = '';
        filterLocally('');
        // Focus stays in the box so the next area can simply be typed.
        box.focus();
    });

    // Collapse the sublocalities on load, and honour a query the browser
    // restored on a back-navigation.
    filterLocally(normalise(box.value));
}());
