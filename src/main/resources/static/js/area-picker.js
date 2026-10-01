/*
 * A search box over the area list.
 *
 * The wizard's area field is a <select> with around two hundred options. On a
 * phone that opens a scroll wheel, and finding "Powai" means spinning past
 * every locality in Mumbai — the first thing the listing flow asked anybody to
 * do, on the screen that decides whether they bother at all. The search page
 * has had a proper filter for ages; the side of the marketplace that is
 * actually scarce had the worse one.
 *
 * PROGRESSIVE ENHANCEMENT, and it matters here more than usual. The <select>
 * is what posts the value, what a keyboard drives, and what works when this
 * file fails to load. This script hides it and drives it; it never replaces
 * it. If anything below throws, the page falls back to a long dropdown — ugly,
 * and still completely usable.
 *
 * Filtering happens in the browser against options already in the page rather
 * than against /flats/areas. The whole list is here, it is small, and a
 * network round trip per keystroke would be slower and would stop working on
 * a bad connection — which, on a phone in Mumbai, is the case that matters.
 */
(function () {
    'use strict';

    var wrap = document.querySelector('[data-area-picker]');
    if (!wrap) {
        return;
    }

    var input = wrap.querySelector('.area-picker__input');
    var select = wrap.querySelector('.area-picker__select');
    if (!input || !select) {
        return;
    }

    /* Flatten the <optgroup> structure into something searchable. */
    var options = [];
    Array.prototype.forEach.call(select.options, function (opt) {
        if (!opt.value) {
            return;
        }
        var group = opt.parentNode.tagName === 'OPTGROUP' ? opt.parentNode.label : '';
        options.push({
            value: opt.value,
            // The visible text carries " · Bandra station" for top-level
            // areas, which is worth searching: people know the station.
            text: opt.textContent.trim(),
            group: group,
            haystack: (opt.textContent + ' ' + group).toLowerCase()
        });
    });

    var list = document.createElement('ul');
    list.className = 'area-picker__results';
    list.hidden = true;
    wrap.appendChild(list);

    var chosen = document.createElement('p');
    chosen.className = 'area-picker__chosen';
    chosen.hidden = true;
    wrap.appendChild(chosen);

    // Hidden HERE, not in the template, so the select is present and usable
    // for anybody this script never reached.
    select.hidden = true;
    select.setAttribute('tabindex', '-1');

    function showChosen(value) {
        chosen.textContent = value ? 'Selected: ' + value : '';
        chosen.hidden = !value;
    }

    function choose(value, text) {
        select.value = value;
        // Dispatched so anything else listening to the select — validation,
        // or a future handler — sees a change it would have seen from a real
        // interaction.
        select.dispatchEvent(new Event('change', { bubbles: true }));
        input.value = text;
        list.hidden = true;
        showChosen(text);
    }

    function render(matches) {
        list.innerHTML = '';
        if (matches.length === 0) {
            list.hidden = true;
            return;
        }
        matches.slice(0, 8).forEach(function (opt) {
            var li = document.createElement('li');
            var button = document.createElement('button');
            button.type = 'button';           // never submits the form
            button.className = 'area-picker__result';
            button.textContent = opt.text;
            if (opt.group) {
                var small = document.createElement('span');
                small.className = 'area-picker__group';
                small.textContent = opt.group;
                button.appendChild(small);
            }
            button.addEventListener('click', function () {
                choose(opt.value, opt.text);
            });
            li.appendChild(button);
            list.appendChild(li);
        });
        list.hidden = false;
    }

    input.addEventListener('input', function () {
        var query = input.value.trim().toLowerCase();
        if (query.length < 2) {
            list.hidden = true;
            return;
        }
        render(options.filter(function (opt) {
            return opt.haystack.indexOf(query) !== -1;
        }));
    });

    /* Enter picks the first match rather than submitting a half-filled form. */
    input.addEventListener('keydown', function (event) {
        if (event.key !== 'Enter') {
            return;
        }
        event.preventDefault();
        var first = list.querySelector('.area-picker__result');
        if (first) {
            first.click();
        }
    });

    // Clicking away closes the list. Not on blur: blur fires before the click
    // on a result registers, so the list would vanish out from under the tap.
    document.addEventListener('click', function (event) {
        if (!wrap.contains(event.target)) {
            list.hidden = true;
        }
    });

    /* Resuming a draft: show what is already chosen. */
    if (select.value) {
        var current = options.filter(function (o) {
            return o.value === select.value;
        })[0];
        if (current) {
            input.value = current.text;
            showChosen(current.text);
        }
    }
}());
