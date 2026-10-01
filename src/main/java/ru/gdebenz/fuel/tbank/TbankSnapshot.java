package ru.gdebenz.fuel.tbank;

import ru.gdebenz.fuel.Geo;

import java.util.List;
import java.util.Optional;

/** T-Bank stations for an area, with nearest-within-threshold matching to a gdebenz station. */
public final class TbankSnapshot {

    public static final TbankSnapshot EMPTY = new TbankSnapshot(List.of(), 120.0);

    private final List<TbankStation> stations;
    private final double maxMeters;

    public TbankSnapshot(List<TbankStation> stations, double maxMeters) {
        this.stations = stations == null ? List.of() : List.copyOf(stations);
        this.maxMeters = maxMeters;
    }

    /** Nearest T-Bank station within the threshold whose brand is compatible, if any. */
    public Optional<TbankStation> match(double lat, double lon, String brand) {
        TbankStation best = null;
        double bestDistance = Double.MAX_VALUE;
        for (TbankStation station : stations) {
            double d = Geo.distanceMeters(lat, lon, station.lat(), station.lon());
            if (d <= maxMeters && d < bestDistance && brandCompatible(brand, station.brand())) {
                best = station;
                bestDistance = d;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Accept if either brand is unknown, or one normalized brand contains the other. */
    static boolean brandCompatible(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return true;
        }
        return na.contains(nb) || nb.contains(na);
    }

    static String normalize(String brand) {
        if (brand == null) {
            return "";
        }
        return brand.toLowerCase().replaceAll("[^a-zа-я0-9]", "");
    }
}
