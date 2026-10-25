package org.seedatlas.xaero.nativeapi;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class NativeLibraryLoader {
    static final String OVERRIDE_PROPERTY = "seedatlas_xaero.native.path";
    private static final int MAX_LIBRARY_BYTES = 64 * 1024 * 1024;

    private NativeLibraryLoader() {}

    static LoadedNative load() {
        Platform platform = Platform.current();
        String override = System.getProperty(OVERRIDE_PROPERTY);
        Path library;
        if (override != null && !override.isBlank()) {
            library = Path.of(override).toAbsolutePath().normalize();
            if (!Files.isRegularFile(library)) {
                throw new NativeException("Native override is not a regular file: " + library);
            }
        } else {
            library = extract(platform);
        }

        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup(library, Arena.global());
            return new LoadedNative(library, lookup, platform.resourceDirectory);
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            throw new NativeException("Could not load native Seed Atlas library from " + library, error);
        }
    }

    private static Path extract(Platform platform) {
        String resource = "/natives/" + platform.resourceDirectory + "/" + platform.fileName;
        byte[] bytes;
        try (InputStream input = NativeLibraryLoader.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new NativeException("Bundled native library is missing: " + resource);
            }
            bytes = input.readNBytes(MAX_LIBRARY_BYTES + 1);
        } catch (IOException error) {
            throw new NativeException("Could not read bundled native library " + resource, error);
        }
        if (bytes.length == 0 || bytes.length > MAX_LIBRARY_BYTES) {
            throw new NativeException("Bundled native library has an invalid size: " + resource);
        }

        String digest = sha256(bytes);
        Path cache = Path.of(System.getProperty("java.io.tmpdir"), "seedatlas-xaero-native",
            "abi-" + NativeBindings.EXPECTED_ABI, digest.substring(0, 24));
        Path target = cache.resolve(platform.fileName);
        Path lockPath = cache.resolve("extract.lock");
        try {
            Files.createDirectories(cache);
            restrictDirectory(cache);
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                if (!validExisting(target, digest)) {
                    writeAtomically(cache, target, bytes);
                }
            }
            if (!validExisting(target, digest)) {
                throw new NativeException("Extracted native library failed its SHA-256 check");
            }
            return target.toAbsolutePath().normalize();
        } catch (IOException error) {
            throw new NativeException("Could not extract native Seed Atlas library to " + target, error);
        }
    }

    private static boolean validExisting(Path target, String expectedDigest) throws IOException {
        return Files.isRegularFile(target) && !Files.isSymbolicLink(target)
            && Files.size(target) > 0 && Files.size(target) <= MAX_LIBRARY_BYTES
            && expectedDigest.equals(sha256(Files.readAllBytes(target)));
    }

    private static void writeAtomically(Path directory, Path target, byte[] bytes) throws IOException {
        Path temporary = directory.resolve(target.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            restrictFile(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrictDirectory(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and non-POSIX file systems do not expose these permissions.
        }
    }

    private static void restrictFile(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and non-POSIX file systems do not expose these permissions.
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Every Java runtime must provide SHA-256", impossible);
        }
    }

    record LoadedNative(Path path, SymbolLookup symbols, String platform) {}

    private record Platform(String resourceDirectory, String fileName) {
        static Platform current() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            boolean x64 = architecture.equals("amd64") || architecture.equals("x86_64");
            boolean arm64 = architecture.equals("aarch64") || architecture.equals("arm64");

            if (os.contains("win") && x64) {
                return new Platform("windows-x86_64", "seedatlas_xaero.dll");
            }
            if (os.contains("linux") && x64) {
                return new Platform("linux-x86_64", "seedatlas_xaero.so");
            }
            if ((os.contains("mac") || os.contains("darwin")) && x64) {
                return new Platform("macos-x86_64", "seedatlas_xaero.dylib");
            }
            if ((os.contains("mac") || os.contains("darwin")) && arm64) {
                return new Platform("macos-aarch64", "seedatlas_xaero.dylib");
            }
            throw new NativeException("Unsupported native platform: " + os + " / " + architecture);
        }
    }
}
