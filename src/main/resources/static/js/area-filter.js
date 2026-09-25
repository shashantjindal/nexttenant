/*
 * Area filter search box.
 *
 * Progressive enhancement. Without JavaScript the 22 area checkboxes are
 * present and usable, and the filter form is a plain GET that works. This only
 * makes finding one faster.
 *
 * Two layers:
 *   1. Instant client-side filtering of the visible labels — no network, no
 *      latency, correct for the common case of typing a prefix.
 *   2. When nothing matches locally, ask the server, which uses pg_trgm and so
 *      tolerates the misspellings a substring match cannot ("andhri").
 *
 * The second layer only fires when the first found nothing, so a typical
 * keystroke costs zero requests.
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
    var pending = null;

    // The list is hidden until enhanced, so a scripting-disabled browser sees
    // the plain checkboxes rather than a search box that does nothing.
    box.parentElement.hidden = false;

    function normalise(text) {
        return text.trim().toLowerCase();
    }

    function filterLocally(query) {
        var matches = 0;
        labels.forEach(function (label) {
            var name = normalise(label.getAttribute('data-name'));
            var station = normalise(label.getAttribute('data-station'));
            var hit = query === '' || name.indexOf(query) !== -1 || station.indexOf(query) !== -1;
            label.hidden = !hit;
            // A ticked area stays visible even when filtered out, so nobody
            // loses track of a selection they cannot see.
            if (!hit && label.querySelector('input').checked) {
                label.hidden = false;
            }
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
                    hint.textContent = 'No area by that name. We cover 22 across Mumbai.';
                    return;
                }
                var names = areas.map(function (a) { return a.name; });
                hint.textContent = 'Did you mean: ' + names.join(', ') + '?';
                // Reveal the fuzzy matches so they can simply be ticked.
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
})();
