package org.seedatlas.xaero.integration;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.seedatlas.xaero.compat.XaeroCompatibility;
import org.seedatlas.xaero.compat.XaeroCompatibility.ClassSource;

/**
 * Runs the runtime Xaero compatibility check against the Xaero version Seed Atlas was compiled
 * with, or against another Xaero jar ({@code -Dseedatlas.xaeroJar=...}, used by the canary
 * workflow for new Xaero releases), and proves that each kind of breaking change is detected.
 */
public final class XaeroCompatibilityTest {
    public static void main(String[] args) throws IOException {
        ClassSource installed = installedXaero();
        XaeroCompatibility.Report report = XaeroCompatibility.check(installed);
        report.problems().forEach(problem -> System.out.println("Incompatible: " + problem));
        report.warnings().forEach(warning -> System.out.println("Optional feature lost: " + warning));
        check(report.compatible(), "Seed Atlas must be compatible with this Xaero's World Map");
        if (System.getProperty("seedatlas.xaeroJar") != null) {
            System.out.println("Xaero jar " + System.getProperty("seedatlas.xaeroJar") + " is compatible");
            return;
        }
        check(report.warnings().isEmpty(), "No optional feature may be lost with the compiled Xaero version");
        everyXaeroUserIsChecked(report);

        breaks(installed, "xaero/map/gui/GuiMap", node -> method(node, "extractRenderState").localVariables
            .forEach(local -> { if (local.name.equals("flooredCameraX")) local.name = "cameraBlockX"; }), "flooredCameraX");
        breaks(installed, "xaero/map/gui/GuiMap", node -> node.fields.removeIf(f -> f.name.equals("attachedCamera")),
            "attachedCamera");
        breaks(installed, "xaero/map/gui/GuiMap", node -> method(node, "applyZoomLimits").localVariables
            .forEach(local -> { if (local.name.equals("minScale")) local.name = "minimumScale"; }), "minScale");
        breaks(installed, "xaero/map/gui/GuiMap", node -> removeRendererCalls(method(node, "extractRenderState")),
            "explored terrain's render type");
        breaks(installed, "xaero/map/region/texture/BranchTextureRenderer",
            node -> removeRendererCalls(method(node, "render")), "branch render type");
        breaks(installed, "xaero/map/region/BranchLeveledRegion",
            node -> node.methods.removeIf(m -> m.name.equals("findCacheFile")), "findCacheFile");
        breaks(installed, "xaero/map/MapProcessor", node -> node.methods.removeIf(m -> m.name.equals("getCurrentCaveLayer")),
            "getCurrentCaveLayer");
        breaks(installed, "xaero/map/element/render/ElementRenderer", node -> {
            MethodNode added = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "seedAtlasProbe", "()V", null, null);
            node.methods.add(added);
        }, "does not implement");
        breaks(installed, "xaero/map/WorldMapSession", null, "WorldMapSession");

        // The waypoint bridge guards itself: losing its Minimap hook is a warning, not a shutdown.
        String waypointOwner = waypointOwner(installed);
        XaeroCompatibility.Report optional = XaeroCompatibility.check(mutated(installed, waypointOwner,
            node -> node.methods.removeIf(m -> m.name.equals("createTempWaypoint"))));
        check(optional.compatible() && optional.warnings().stream().anyMatch(w -> w.contains("createTempWaypoint")),
            "A missing optional waypoint hook only disables that feature: " + optional);
        ExploredEdgeCachesTest.run();
        System.out.println("Xaero compatibility checks passed (" + report.checked().size() + " classes)");
    }

    private static void removeRendererCalls(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("getRenderer")) {
                method.instructions.remove(instruction);
            }
        }
    }

    /** Every compiled Seed Atlas class that references Xaero must be reached by the runtime check. */
    private static void everyXaeroUserIsChecked(XaeroCompatibility.Report report) throws IOException {
        int users = 0;
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path root = Path.of(entry);
            if (!Files.isDirectory(root.resolve("org/seedatlas/xaero"))) continue;
            try (var files = Files.walk(root.resolve("org/seedatlas/xaero"))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    byte[] bytes = Files.readAllBytes(file);
                    ClassNode node = read(new ByteArrayInputStream(bytes));
                    if (!referencesXaero(node, bytes)) continue;
                    users++;
                    check(report.checked().contains(node.name), "Runtime check misses Xaero user " + node.name
                        + "; reach it from XaeroCompatibility.ROOTS");
                }
            }
        }
        check(users > 10, "Expected the compiled Seed Atlas classes on the test class path, found " + users);
    }

    /** The constant pool names every class a class file refers to. */
    private static boolean referencesXaero(ClassNode node, byte[] bytes) {
        String constants = new String(bytes, StandardCharsets.ISO_8859_1);
        return (constants.contains("xaero/map/") || constants.contains("xaero/lib/"))
            && !node.name.startsWith("org/seedatlas/xaero/compat/")
            && !node.name.startsWith("org/seedatlas/xaero/integration/XaeroCompatibilityTest");
    }

    private static void breaks(ClassSource installed, String className, Consumer<ClassNode> change, String expected) {
        XaeroCompatibility.Report report = XaeroCompatibility.check(mutated(installed, className, change));
        check(!report.compatible() && report.problems().stream().anyMatch(problem -> problem.contains(expected)),
            "Changing " + className + " must be detected (" + expected + "): " + report.problems());
    }

    /** {@code change == null} removes the class. */
    private static ClassSource mutated(ClassSource installed, String className, Consumer<ClassNode> change) {
        Map<String, ClassNode> copies = new HashMap<>();
        return name -> {
            if (!name.equals(className)) return installed.read(name);
            if (change == null) return null;
            return copies.computeIfAbsent(name, key -> {
                ClassNode copy = new ClassNode();
                installed.read(key).accept(copy);
                change.accept(copy);
                return copy;
            });
        };
    }

    private static String waypointOwner(ClassSource source) {
        ClassNode bridge = source.read("org/seedatlas/xaero/integration/marker/SeedAtlasWaypointBridge");
        for (MethodNode method : bridge.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals("createTempWaypoint")) return call.owner;
            }
        }
        throw new AssertionError("The waypoint bridge no longer calls createTempWaypoint");
    }

    /** Classes from the test class path, with Xaero optionally replaced by another jar. */
    private static ClassSource installedXaero() throws IOException {
        ClassSource classPath = ClassSource.of(XaeroCompatibilityTest.class.getClassLoader());
        String jarPath = System.getProperty("seedatlas.xaeroJar");
        if (jarPath == null) return classPath;
        JarFile jar = new JarFile(jarPath);
        return name -> {
            // XaeroLib (xaero/lib) is a separate mod and stays as compiled.
            if (!name.startsWith("xaero/map/")) return classPath.read(name);
            var entry = jar.getEntry(name + ".class");
            if (entry == null) return null;
            try {
                return read(jar.getInputStream(entry));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        };
    }

    private static ClassNode read(InputStream stream) throws IOException {
        try (stream) {
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(m -> m.name.equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + owner.name + "." + name));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
