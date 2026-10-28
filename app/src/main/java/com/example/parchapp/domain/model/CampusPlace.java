package com.example.parchapp.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/* A document of campusAreas in Firestore: where an activity can happen. */
public class CampusPlace {
    private final String id;
    private final String name;
    private final Double lat;
    private final Double lng;
    private final List<String> aliases;

    public CampusPlace(String id, String name, Double lat, Double lng, List<String> aliases) {
        this.id = id;
        this.name = name;
        this.lat = lat;
        this.lng = lng;
        this.aliases = new ArrayList<>(aliases);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /* Outside-campus areas have no single point. */
    public boolean hasCoordinates() {
        return lat != null && lng != null;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public List<String> getAliases() {
        return Collections.unmodifiableList(aliases);
    }

    /* True when a free-text location (from activities created before areas existed) names this place. */
    public boolean matches(String location) {
        String text = location == null ? "" : location.toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return false;
        }
        if (text.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (String alias : aliases) {
            if (!alias.trim().isEmpty() && text.contains(alias.toLowerCase(Locale.ROOT).trim())) {
                return true;
            }
        }
        return false;
    }
}
