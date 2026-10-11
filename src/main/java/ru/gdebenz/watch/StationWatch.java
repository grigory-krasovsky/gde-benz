package ru.gdebenz.watch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Last seen state of a polled favorite station, shared by everyone who starred it. */
@Entity
@Table(name = "station_watch")
public class StationWatch {

    @Id
    @Column(name = "osm_id", length = 64)
    private String osmId;

    /** Time of the latest report we have processed (gdebenz {@code updated}). */
    @Column(name = "last_updated")
    private Instant lastUpdated;

    @Column(name = "status", length = 16)
    private String status;

    /** Normalized grade set, e.g. "92,95,ДТ". */
    @Column(name = "fuels", length = 128)
    private String fuels;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    protected StationWatch() {
        // for JPA
    }

    public StationWatch(String osmId, Instant lastUpdated, String status, String fuels, Instant now) {
        this.osmId = osmId;
        this.lastUpdated = lastUpdated;
        this.status = status;
        this.fuels = fuels;
        this.checkedAt = now;
    }

    public void update(Instant lastUpdated, String status, String fuels, Instant now) {
        this.lastUpdated = lastUpdated;
        this.status = status;
        this.fuels = fuels;
        this.checkedAt = now;
    }

    public void touch(Instant now) {
        this.checkedAt = now;
    }

    public String getOsmId() {
        return osmId;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public String getStatus() {
        return status;
    }

    public String getFuels() {
        return fuels;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}
