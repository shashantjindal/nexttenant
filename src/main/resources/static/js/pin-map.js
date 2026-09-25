/*
 * The draggable pin, for wizard step 1.
 *
 * This is the one map in the product that shows a real location, and only to
 * the person who owns it. Everywhere else the exact point is never sent to a
 * browser at all.
 *
 * The marker writes into hidden latitude/longitude inputs, which post to
 * listing_private. Saving there fires the database trigger that recomputes the
 * public jittered point — nothing here calculates it, so nothing here can get
 * it wrong.
 */
(function () {
    'use strict';

    // Central Mumbai, used when there is no pin yet and no area chosen.
    var FALLBACK_LAT = 19.0760;
    var FALLBACK_LNG = 72.8777;

    function init() {
        var el = document.getElementById('pin-map');
        if (!el || typeof L === 'undefined') {
            return;
        }

        var latInput = document.getElementById('pin-lat');
        var lngInput = document.getElementById('pin-lng');
        var status = document.getElementById('pin-status');
        var areaSelect = document.querySelector('select[name="areaName"]');

        var savedLat = parseFloat(el.getAttribute('data-lat'));
        var savedLng = parseFloat(el.getAttribute('data-lng'));
        var hasSaved = isFinite(savedLat) && isFinite(savedLng);

        var startLat = hasSaved ? savedLat : FALLBACK_LAT;
        var startLng = hasSaved ? savedLng : FALLBACK_LNG;

        var map = L.map(el, { scrollWheelZoom: false });
        // A view must exist before layers are added, or Leaflet throws from
        // inside its own projection code.
        map.setView([startLat, startLng], hasSaved ? 17 : 12);

        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
        }).addTo(map);

        var marker = L.marker([startLat, startLng], { draggable: true }).addTo(map);

        function record(latlng) {
            latInput.value = latlng.lat.toFixed(6);
            lngInput.value = latlng.lng.toFixed(6);
            status.textContent = 'Pin placed. Only you will ever see this exact point.';
        }

        if (hasSaved) {
            status.textContent = 'Pin saved. Drag it if the building is wrong.';
        } else {
            status.textContent = 'Drag the pin onto your building, or tap the map.';
        }

        marker.on('dragend', function () {
            record(marker.getLatLng());
        });

        // Tapping is far easier than dragging on a phone, and most listings
        // are created on one.
        map.on('click', function (event) {
            marker.setLatLng(event.latlng);
            record(event.latlng);
        });

        // Choosing an area jumps the map there, so nobody has to pan across
        // Mumbai to find their neighbourhood. It does NOT move the pin: the
        // area is a rough locality, the pin is the building.
        if (areaSelect) {
            areaSelect.addEventListener('change', function () {
                var option = areaSelect.options[areaSelect.selectedIndex];
                var lat = parseFloat(option.getAttribute('data-lat'));
                var lng = parseFloat(option.getAttribute('data-lng'));
                if (isFinite(lat) && isFinite(lng)) {
                    map.setView([lat, lng], 15);
                    if (!latInput.value) {
                        marker.setLatLng([lat, lng]);
                    }
                }
            });
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
