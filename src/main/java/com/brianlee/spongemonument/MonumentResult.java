package com.brianlee.spongemonument;

public record MonumentResult(
    int x,
    int z,
    int wetSponges,
    int spongeRooms
) {
    public long distanceSq() {
        return (long) x * x + (long) z * z;
    }
}