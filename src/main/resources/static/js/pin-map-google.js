/*
 * The draggable pin for wizard step 1, on Google Maps.
 *
 * This is the one map in the product that shows a real location, and only to
 * the person who owns it. Everywhere else the exact point is never sent to a
 * browser at all.
 *
 * The marker writes into hidden latitude/longitude inputs, which post to
 * listing_private. Saving there fires the database trigger that recomputes the
 * public jittered point — nothing here calculates it, so nothing here can get
 * it wrong.
 *
 * Google is a real improvement over OSM for exactly this screen: somebody is
 * looking for THEIR OWN BUILDING, and Mumbai society names and building
 * footprints are far better covered in Google's data. On OSM many of them are
 * simply not drawn, and the person ends up guessing at a grey rectangle.
 */
(function () {
    'use strict';

    // Central Mumbai, used when there is no pin yet and no area chosen.
    var FALLBACK = { lat: 19.0760, lng: 72.8777 };

    window.hhInitPinMap = function () {
        var el = document.getElementById('pin-map');
        if (!el || typeof google === 'undefined') {
            return;
        }

        var latInput = document.getElementById('pin-lat');
        var lngInput = document.getElementById('pin-lng');
        var status = document.getElementById('pin-status');
        var areaSelect = document.querySelector('select[name="areaName"]');

        var savedLat = parseFloat(el.getAttribute('data-lat'));
        var savedLng = parseFloat(el.getAttribute('data-lng'));
        var hasSaved = isFinite(savedLat) && isFinite(savedLng);

        var start = hasSaved ? { lat: savedLat, lng: savedLng } : FALLBACK;

        var map = new google.maps.Map(el, {
            center: start,
            // Close in when there is already a pin, so the building is
            // identifiable; wide when there is not, so they can find their
            // neighbourhood.
            zoom: hasSaved ? 18 : 12,
            gestureHandling: 'greedy',   // this map IS the task; the wheel is its
            mapTypeControl: false,
            streetViewControl: false,
            fullscreenControl: false
        });

        var marker = new google.maps.Marker({
            map: map,
            position: start,
            draggable: true,
            // A drop animation on load would be movement for its own sake, and
            // this pin may already represent a saved answer.
            title: 'Drag onto your building'
        });

        function record(position) {
            // Six decimal places is about 11cm. More is noise from a finger on
            // a touchscreen; fewer would move the pin off the building.
            latInput.value = position.lat().toFixed(6);
            lngInput.value = position.lng().toFixed(6);
            status.textContent = 'Pin placed. Only you will ever see this exact point.';
        }

        status.textContent = hasSaved
                ? 'Pin saved. Drag it if the building is wrong.'
                : 'Drag the pin onto your building, or tap the map.';

        marker.addListener('dragend', function () {
            record(marker.getPosition());
        });

        // Tapping is far easier than dragging on a phone, and most listings
        // are created on one.
        map.addListener('click', function (event) {
            marker.setPosition(event.latLng);
            record(event.latLng);
        });

        // Choosing an area jumps the map there, so nobody has to pan across
        // Mumbai to find their neighbourhood. It does NOT move a pin that has
        // already been placed: the area is a rough locality, the pin is the
        // building, and silently relocating a saved answer would be a lie.
        if (areaSelect) {
            areaSelect.addEventListener('change', function () {
                var option = areaSelect.options[areaSelect.selectedIndex];
                var lat = parseFloat(option.getAttribute('data-lat'));
                var lng = parseFloat(option.getAttribute('data-lng'));
                if (!isFinite(lat) || !isFinite(lng)) {
                    return;
                }
                map.setCenter({ lat: lat, lng: lng });
                map.setZoom(15);
                if (!latInput.value) {
                    marker.setPosition({ lat: lat, lng: lng });
                }
            });
        }
    };
}());
