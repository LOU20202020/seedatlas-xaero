package org.seedatlas.xaero.nativeapi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A span of Minecraft releases with the same world generation, as the engine distinguishes them.
 * {@code name} is the newest release of the span and what the engine is asked to generate;
 * {@code first} is the oldest. Spans start at 1.1, like the Seed Atlas app's regular version list.
 * Before 1.18 the world generation has no noise-based terrain, so the terrain look is unavailable.
 */
public record GameVersion(String name, String first) {
    /** Newest first. Every boundary is a version where the engine's generation changes. */
    public static final List<GameVersion> ALL = List.of(
        new GameVersion("26.3", "26.3"),
        new GameVersion("26.2", "26.2"),
        new GameVersion("26.1.2", "1.21.5"),
        new GameVersion("1.21.4", "1.21.4"),
        new GameVersion("1.21.3", "1.21.2"),
        new GameVersion("1.21.1", "1.21"),
        new GameVersion("1.20.6", "1.20"),
        new GameVersion("1.19.4", "1.19.3"),
        new GameVersion("1.19.2", "1.19"),
        new GameVersion("1.18.2", "1.18"),
        new GameVersion("1.17.1", "1.17"),
        new GameVersion("1.16.5", "1.16.2"),
        new GameVersion("1.16.1", "1.16"),
        new GameVersion("1.15.2", "1.15"),
        new GameVersion("1.14.4", "1.14"),
        new GameVersion("1.13.2", "1.13"),
        new GameVersion("1.12.2", "1.11"),
        new GameVersion("1.10.2", "1.10"),
        new GameVersion("1.9.4", "1.9"),
        new GameVersion("1.8.9", "1.8"),
        new GameVersion("1.7.10", "1.7"),
        new GameVersion("1.6.4", "1.4"),
        new GameVersion("1.3.2", "1.3"),
        new GameVersion("1.2.5", "1.2"),
        new GameVersion("1.1", "1.1")
    );
    /** The version this build of the mod runs on, and the engine's newest. */
    public static final GameVersion NEWEST = ALL.getFirst();
    private static final int[] TERRAIN = {1, 18};

    public GameVersion {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(first, "first");
    }

    public boolean isNewest() {
        return this.equals(NEWEST);
    }

    /** "1.21.5 – 26.1.2", or the single release. */
    public String label() {
        return this.first.equals(this.name) ? this.name : this.first + " – " + this.name;
    }

    /** Terrain heights, Nether floors and ore veins need the noise-based generation of 1.18+. */
    public boolean hasTerrain() {
        return compare(parts(this.first), TERRAIN) >= 0;
    }

    /** The span containing a release such as "26.1", "1.20.1" or a span's own name. */
    public static Optional<GameVersion> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        int[] version = parts(input.trim());
        if (version == null || compare(version, parts(NEWEST.name)) > 0) {
            return Optional.empty();
        }
        for (GameVersion span : ALL) {
            if (compare(version, parts(span.first)) >= 0) {
                return Optional.of(span);
            }
        }
        return Optional.empty();
    }

    private static int[] parts(String version) {
        String[] split = version.split("\\.");
        if (split.length < 2 || split.length > 3) {
            return null;
        }
        int[] result = new int[3];
        try {
            for (int i = 0; i < split.length; i++) {
                result[i] = Integer.parseInt(split[i]);
                if (result[i] < 0) return null;
            }
        } catch (NumberFormatException exception) {
            return null;
        }
        return result;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return Integer.compare(a.length, b.length);
    }

    @Override
    public String toString() {
        return this.name;
    }
}
