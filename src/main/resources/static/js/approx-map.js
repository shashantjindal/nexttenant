/*
 * The approximate-location map.
 *
 * Reads ONLY the jittered point and the published radius from data attributes.
 * The exact location is not in the page at all — ListingDetailDto has no field
 * for it — so there is nothing here that could accidentally plot the real
 * building.
 *
 * Tiles come from OpenStreetMap's public servers, which need no API key and are
 * fine for development. Their tile usage policy prohibits heavy production use,
 * so this needs a real provider (Mappls has the best Indian locality data)
 * before launch.
 */
(function () {
    'use strict';

    function init() {
        var el = document.getElementById('approx-map');
        if (!el || typeof L === 'undefined') {
            return;
        }

        var lat = parseFloat(el.getAttribute('data-lat'));
        var lng = parseFloat(el.getAttribute('data-lng'));
        var radius = parseInt(el.getAttribute('data-radius'), 10);

        if (!isFinite(lat) || !isFinite(lng) || lat === 0) {
            el.remove();
            return;
        }

        var map = L.map(el, {
            // Scroll-wheel zoom off: the map sits inside a long page, and
            // hijacking the wheel traps people trying to scroll past it.
            scrollWheelZoom: false,
            zoomControl: true
        });

        // A view MUST be set before any layer is added. Leaflet needs a centre
        // and zoom to project coordinates, and adding a layer without one
        // throws "Cannot read properties of undefined (reading
        // 'layerPointToLatLng')" — which reads like a Leaflet bug and is
        // entirely a caller error. fitBounds below then refines this.
        map.setView([lat, lng], 15);

        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 18,
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
        }).addTo(map);

        var circle = L.circle([lat, lng], {
            radius: radius,
            color: '#7A3420',
            weight: 1.5,
            fillColor: '#7A3420',
            fillOpacity: 0.12
        }).addTo(map);

        // No marker. A pin invites the reading "the flat is exactly here",
        // which is precisely what the circle exists to avoid saying.
        map.fitBounds(circle.getBounds(), { padding: [20, 20] });
    }

    // Leaflet loads with defer, so it may not be ready at DOMContentLoaded.
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
