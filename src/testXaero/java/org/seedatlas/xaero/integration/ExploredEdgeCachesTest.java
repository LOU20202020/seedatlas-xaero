package org.seedatlas.xaero.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;

/** Checks migration against disposable files, including caches rebuilt before the current session. */
final class ExploredEdgeCachesTest {
    static void run() throws IOException {
        Path root = Files.createTempDirectory("seedatlas-explored-cache-test-");
        try {
            Path folder = Files.createDirectories(root.resolve("first/cache/1"));
            Path old = Files.writeString(folder.resolve("0_0.xwmc"), "old texture");
            Files.setLastModifiedTime(old, FileTime.fromMillis(1000));
            Path region = Files.writeString(root.resolve("first/0_0.zip"), "explored region");
            ExploredEdgeCaches.dropIfOutdated(old.toFile());
            check(!Files.exists(old), "Old zoom cache must be rebuilt");
            Path marker = folder.getParent().resolve("seedatlas_explored_edges");
            FileTime cutoff = Files.getLastModifiedTime(marker);

            Files.writeString(old, "rebuilt texture");
            Files.setLastModifiedTime(old, FileTime.fromMillis(cutoff.toMillis() + 2000));
            ExploredEdgeCaches.dropIfOutdated(old.toFile());
            check(Files.readString(old).equals("rebuilt texture"), "Rebuilt cache must survive later access");
            check(Files.getLastModifiedTime(marker).equals(cutoff), "The cutoff must remain stable");
            check(Files.readString(region).equals("explored region"), "Source map data must remain untouched");

            // A pre-existing marker models a map opened in a later game session.
            Path later = Files.createDirectories(root.resolve("later/cache/2"));
            Path existingMarker = Files.writeString(later.getParent().resolve("seedatlas_explored_edges"), "existing");
            Files.setLastModifiedTime(existingMarker, FileTime.fromMillis(10000));
            Path stale = Files.writeString(later.resolve("1_0.xwmc"), "stale texture");
            Files.setLastModifiedTime(stale, FileTime.fromMillis(5000));
            Path fresh = Files.writeString(later.resolve("2_0.xwmc"), "fresh texture");
            Files.setLastModifiedTime(fresh, FileTime.fromMillis(15000));
            ExploredEdgeCaches.dropIfOutdated(stale.toFile());
            ExploredEdgeCaches.dropIfOutdated(fresh.toFile());
            ExploredEdgeCaches.dropIfOutdated(later.resolve("missing.xwmc").toFile());
            ExploredEdgeCaches.dropIfOutdated(null);
            check(!Files.exists(stale), "Unvisited old caches must still be rebuilt in a later session");
            check(Files.readString(fresh).equals("fresh texture"), "New caches must survive a later session");
            check(Files.readString(existingMarker).equals("existing"), "An existing marker must not be overwritten");
            check(Files.getLastModifiedTime(existingMarker).toMillis() == 10000, "Preserve the stored cutoff");
            System.out.println("Explored map cache migration tests passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
