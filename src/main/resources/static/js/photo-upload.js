/*
 * Choosing photos is the upload.
 *
 * The page it enhances works without this file: pick files, press Upload. The
 * trouble is that nothing tells anybody the button exists. People chose three
 * photos, saw "3 files" beside the picker, and read that as done — and the
 * next screen asked them to add three more, which is the one thing they
 * believed they had just finished.
 *
 * So the form sends itself when the picker closes, and the button it replaces
 * is hidden only once this script is running to do its job.
 *
 * WHY NOT A FETCH UPLOAD. An XHR with a progress bar and thumbnails appearing
 * in place would be nicer, and would mean maintaining a second rendering of
 * the photo grid in JavaScript alongside the Thymeleaf one, plus its own error
 * handling for the several ways an upload is refused. A form submit reuses
 * every bit of that, already written and already tested. The cost is a page
 * reload, which on this screen is unremarkable.
 *
 * The local previews below exist because that reload is the one moment this
 * approach is worse: the file is being read from a phone over a slow
 * connection and the screen would otherwise sit unchanged, looking like the
 * tap did nothing.
 */
(function () {
    'use strict';

    var form = document.querySelector('form[data-autosubmit]');
    if (!form) {
        return;
    }

    var input = form.querySelector('input[type="file"]');
    var fallback = form.querySelector('[data-autosubmit-fallback]');
    var busy = form.querySelector('.photo-upload__busy');
    if (!input) {
        return;
    }

    // Hidden HERE rather than in the template: without this script the button
    // is the only way to upload anything, and a page that needs JavaScript to
    // do its primary job is a page that is broken when the script fails.
    if (fallback) {
        fallback.hidden = true;
    }

    /*
     * Previews from the files themselves, before anything is sent.
     *
     * Deliberately NOT the same markup as the uploaded grid below. These are
     * pending, and showing them identically would claim the upload had
     * finished — so they are dimmed, and they carry no cover or remove
     * controls, because neither means anything until the server has the file.
     */
    var pending = document.createElement('div');
    pending.className = 'photo-grid photo-grid--pending';
    pending.hidden = true;
    form.appendChild(pending);

    function showPreviews(files) {
        pending.innerHTML = '';
        var shown = 0;

        Array.prototype.forEach.call(files, function (file) {
            if (!file.type || file.type.indexOf('image/') !== 0) {
                return;
            }
            var url = URL.createObjectURL(file);
            var img = document.createElement('img');
            img.src = url;
            img.alt = '';
            // Freed once painted. Object URLs are held until the document is
            // discarded otherwise, which on a ten-photo listing is ten full
            // images kept in memory for no reason.
            img.onload = function () {
                URL.revokeObjectURL(url);
            };

            var figure = document.createElement('figure');
            figure.className = 'photo-tile photo-tile--pending';
            figure.appendChild(img);
            pending.appendChild(figure);
            shown += 1;
        });

        pending.hidden = shown === 0;
    }

    var sent = false;

    input.addEventListener('change', function () {
        if (sent || !input.files || input.files.length === 0) {
            return;
        }

        showPreviews(input.files);

        sent = true;
        input.disabled = false;   // a disabled input is not submitted
        if (busy) {
            busy.hidden = false;
        }

        if (form.requestSubmit) {
            form.requestSubmit();
        } else {
            form.submit();
        }
    });
}());
