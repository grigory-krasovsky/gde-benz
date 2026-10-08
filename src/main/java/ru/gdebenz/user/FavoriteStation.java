package ru.gdebenz.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A station a user starred. Keeps a snapshot of brand/address/coordinates from the moment it was
 * starred, since gdebenz has no per-station lookup and the station may be absent from a later answer.
 */
@Entity
@Table(name = "favorite_stations")
public class FavoriteStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "osm_id", nullable = false, length = 64)
    private String osmId;

    @Column(name = "brand")
    private String brand;

    @Column(name = "addr", length = 512)
    private String addr;

    @Column(name = "lat", nullable = false)
    private double lat;

    @Column(name = "lon", nullable = false)
    private double lon;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FavoriteStation() {
        // for JPA
    }

    public FavoriteStation(Long userId, String osmId, String brand, String addr,
                           double lat, double lon, Instant now) {
        this.userId = userId;
        this.osmId = osmId;
        this.brand = brand;
        this.addr = addr;
        this.lat = lat;
        this.lon = lon;
        this.createdAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getOsmId() {
        return osmId;
    }

    public String getBrand() {
        return brand;
    }

    public String getAddr() {
        return addr;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
