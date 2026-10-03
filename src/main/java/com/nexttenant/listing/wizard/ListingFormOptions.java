package com.nexttenant.listing.wizard;

import com.nexttenant.area.AreaRepository;
import com.nexttenant.listing.Configuration;
import com.nexttenant.listing.FlatType;
import com.nexttenant.listing.FlatmateGender;
import com.nexttenant.listing.FlatmateMakeup;
import com.nexttenant.listing.Furnishing;
import com.nexttenant.listing.RoomType;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;

/**
 * The choices offered on the basics screen.
 *
 * <p>Pulled out of {@code WizardController} the moment a second screen started
 * rendering the same fields. The markup for those fields now lives in one
 * fragment; this is the other half of that — the options the fragment iterates
 * over, also in one place.
 *
 * <p>Without this the duplication is invisible and lethal: a new enum value,
 * or a deliberate omission like MASTER_ROOM below, would be added to one
 * controller and not the other. The two screens would then offer different
 * answers to the same question, and nothing would fail — they would simply
 * disagree, and only for whoever used the screen that was forgotten.
 */
@Component
public class ListingFormOptions {

    private final AreaRepository areas;

    public ListingFormOptions(AreaRepository areas) {
        this.areas = areas;
    }

    /** Everything the basics fields iterate over. */
    public void addTo(Model model) {
        model.addAttribute("areaGroups", areas.findGrouped());
        model.addAttribute("configurations", Configuration.values());

        // MASTER_ROOM is deliberately absent: it exists for listings that
        // already carry it and is not worth a fifth button on the screen that
        // decides whether somebody lists at all.
        model.addAttribute("roomTypes", List.of(
                RoomType.PRIVATE_ROOM, RoomType.SHARED_ROOM,
                RoomType.HALL, RoomType.ENTIRE_FLAT));

        model.addAttribute("furnishings", Furnishing.values());
        model.addAttribute("flatTypes", FlatType.values());
        model.addAttribute("genders", FlatmateGender.values());
        model.addAttribute("makeups", FlatmateMakeup.values());
    }
}
