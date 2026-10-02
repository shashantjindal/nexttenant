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

        /*
         * Then tidy up by group, which per-label matching cannot do.
         *
         * Two things need fixing once the labels have decided for themselves.
         * A group where nothing matched should disappear entirely rather than
         * leave an empty heading behind. And a group where only CHILDREN
         * matched — typing "chakala" — needs its parent shown anyway, because
         * a sublocality floating with no heading above it is the flat list
         * this change exists to replace.
         */
        Array.prototype.forEach.call(
                list.querySelectorAll('.area-group'), function (group) {
            var parent = group.querySelector('.area-group__parent');
            var children = Array.prototype.slice.call(
                    group.querySelectorAll('.area-group__child'));

            var anyChildVisible = children.some(function (c) {
                return !c.hidden;
            });

            if (anyChildVisible && parent) {
                parent.hidden = false;
            }
            group.hidden = (!parent || parent.hidden) && !anyChildVisible;
        });

        return matches;
    }

    /**
     * Ticking a locality ticks everything inside it.
     *
     * <p>The server has always treated a locality as all of its sublocalities
     * — see expandWithChildren — so this changes nothing about the results.
     * What it changes is that you can SEE what you selected, instead of
     * having to know a rule nothing on the page states.
     *
     * <p>The reverse matters as much: untick one sublocality and the locality
     * stops being ticked, because "all of Andheri East" is no longer what you
     * asked for. The box goes indeterminate rather than empty, which says
     * "some of these" — the one honest answer when four of nine are chosen.
     */
    function syncGroup(group) {
        var parent = group.querySelector('.area-group__parent input');
        var children = Array.prototype.slice.call(
                group.querySelectorAll('.area-group__child input'));
        if (!parent || children.length === 0) {
            return;
        }

        var ticked = children.filter(function (c) {
            return c.checked;
        }).length;

        parent.checked = ticked === children.length;
        parent.indeterminate = ticked > 0 && ticked < children.length;
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
        if (!input || input.name !== 'area') {
            return;
        }

        var group = input.closest('.area-group');
        if (group) {
            if (input.hasAttribute('data-parent-box')) {
                // Ticking the locality ticks all of it; unticking clears it.
                // Done here rather than left to the server so the page shows
                // what was chosen.
                Array.prototype.forEach.call(
                        group.querySelectorAll('.area-group__child input'),
                        function (child) {
                            child.checked = input.checked;
                        });
                input.indeterminate = false;
            } else {
                syncGroup(group);
            }
        }

        if (!input.checked) {
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

    // Reflect any selection restored on a back-navigation before the first
    // paint: without this a group with four of nine children ticked shows an
    // empty parent box, which reads as "none of these".
    Array.prototype.forEach.call(list.querySelectorAll(".area-group"), syncGroup);

    // Collapse the sublocalities on load, and honour a query the browser
    // restored on a back-navigation.
    filterLocally(normalise(box.value));
}());

/*
 * Chips for what is currently picked.
 *
 * The area list scrolls two hundred rows inside 220px, so a tick made three
 * screens up is invisible by the time you have found the next one — and a
 * filter you cannot see is one you forget you set, then blame the site for
 * showing you four results.
 *
 * Built from the ticked boxes every time anything changes, so it cannot
 * disagree with them. A chip's X unticks the box it came from, which means
 * there is one source of truth and the chips are a view of it.
 *
 * A locality whose children are all ticked shows as ONE chip, not ten: that
 * is what the person chose, and ten chips for one click reads as a mistake.
 */
(function () {
    'use strict';

    var list = document.getElementById('area-list');
    var chips = document.getElementById('area-chips');
    if (!list || !chips) {
        return;
    }

    function render() {
        chips.innerHTML = '';
        var shown = 0;

        Array.prototype.forEach.call(
                list.querySelectorAll('.area-group'), function (group) {
            var parentBox = group.querySelector('.area-group__parent input');
            var childBoxes = Array.prototype.slice.call(
                    group.querySelectorAll('.area-group__child input'));

            // Whole locality: one chip, and skip its children entirely.
            if (parentBox && parentBox.checked) {
                shown += add(parentBox, parentBox.value);
                return;
            }
            childBoxes.forEach(function (box) {
                if (box.checked) {
                    shown += add(box, box.value);
                }
            });
        });

        chips.hidden = shown === 0;
    }

    function add(box, label) {
        var chip = document.createElement('button');
        chip.type = 'button';           // never submits the form
        chip.className = 'area-chip';
        chip.textContent = label;
        chip.setAttribute('aria-label', 'Remove ' + label);

        var x = document.createElement('span');
        x.className = 'area-chip__x';
        x.textContent = '×';
        chip.appendChild(x);

        chip.addEventListener('click', function () {
            box.checked = false;
            // Dispatched rather than set silently, so the group sync and the
            // chip rebuild both run exactly as they would on a real click.
            box.dispatchEvent(new Event('change', { bubbles: true }));
        });

        chips.appendChild(chip);
        return 1;
    }

    list.addEventListener('change', render);
    render();
}());
