package org.seedatlas.xaero.compat;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Checks, before any mixin is applied, that the installed Xaero's World Map still has every class,
 * member, injection point and captured local Seed Atlas uses. If not, the integration stays off
 * instead of crashing the game. Pure ASM, so it never loads Minecraft or Xaero classes.
 */
public final class XaeroCompatibility {
    /** Carries the verdict from the mixin plugin to mod code, which may use another class loader. */
    public static final String PROPERTY = "seedatlas_xaero.xaeroCompatibility";
    private static final String OWN = "org/seedatlas/xaero/";
    private static final String XAERO = "xaero/";
    /** Everything that touches Xaero is reachable from these classes. */
    static final List<String> ROOTS = List.of(
        "org/seedatlas/xaero/SeedAtlasXaero",
        "org/seedatlas/xaero/command/SeedAtlasCommands",
        "org/seedatlas/xaero/mixin/GuiMapMixin",
        "org/seedatlas/xaero/mixin/BranchTextureRendererMixin",
        "org/seedatlas/xaero/mixin/BranchRegionTextureMixin",
        "org/seedatlas/xaero/mixin/BranchLeveledRegionMixin",
        "org/seedatlas/xaero/mixin/WorldMapClientOnlyMixin");
    /** Catches LinkageError itself and only loses waypoint shortcuts, so it never disables Seed Atlas. */
    private static final Set<String> OPTIONAL = Set.of(
        "org/seedatlas/xaero/integration/marker/SeedAtlasWaypointBridge");
    private static final String PROVIDER = "xaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider";
    private static final String RENDERER = "xaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRenderer";
    private static final String GET_RENDERER = "(Lnet/minecraft/client/renderer/rendertype/RenderType;)L" + RENDERER + ";";

    private static volatile Report current;

    private final ClassSource source;
    private final Map<String, Optional<ClassNode>> nodes = new HashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Set<String> checked = new LinkedHashSet<>();

    private XaeroCompatibility(ClassSource source) {
        this.source = source;
    }

    /** Reads a class in internal form ({@code xaero/map/gui/GuiMap}); null when it does not exist. */
    @FunctionalInterface
    public interface ClassSource {
        ClassNode read(String internalName);

        /** Raw class files from a class loader, e.g. for mod code when the mixin plugin did not run. */
        static ClassSource of(ClassLoader loader) {
            return name -> {
                try (var stream = loader.getResourceAsStream(name + ".class")) {
                    if (stream == null) return null;
                    ClassNode node = new ClassNode();
                    new ClassReader(stream).accept(node, 0);
                    return node;
                } catch (IOException | RuntimeException exception) {
                    return null;
                }
            };
        }
    }

    /**
     * @param problems  reasons Seed Atlas must stay off
     * @param warnings  missing pieces of optional features, which disable only themselves
     * @param checked   Seed Atlas classes whose Xaero references were verified
     */
    public record Report(List<String> problems, List<String> warnings, Set<String> checked) {
        public boolean compatible() {
            return this.problems.isEmpty();
        }

        /** First problem for chat messages; the log lists all of them. */
        public String summary() {
            return this.problems.isEmpty() ? "" : this.problems.getFirst()
                + (this.problems.size() > 1 ? " (+" + (this.problems.size() - 1) + " more)" : "");
        }
    }

    public static Report check(ClassSource source) {
        XaeroCompatibility check = new XaeroCompatibility(source);
        check.mixinTargets();
        check.linkage();
        return new Report(List.copyOf(check.problems), List.copyOf(check.warnings), Set.copyOf(check.checked));
    }

    /** Records the plugin's verdict for this game session. */
    public static void publish(Report report) {
        current = report;
        System.setProperty(PROPERTY, String.join("\n", report.problems()));
    }

    /** Verdict for this session: the mixin plugin's, or computed here if the plugin did not run. */
    public static Report current() {
        Report report = current;
        if (report != null) return report;
        synchronized (XaeroCompatibility.class) {
            if (current == null) {
                String published = System.getProperty(PROPERTY);
                current = published != null
                    ? new Report(published.isEmpty() ? List.of() : Arrays.asList(published.split("\n")), List.of(), Set.of())
                    : check(ClassSource.of(XaeroCompatibility.class.getClassLoader()));
            }
            return current;
        }
    }

    // Mixin targets: what javac cannot check for @Shadow, @Inject and @Local.

    private void mixinTargets() {
        ClassNode map = xaeroClass("xaero/map/gui/GuiMap");
        if (map != null) {
            for (String field : List.of("cameraX", "cameraZ", "scale")) {
                field(map, field, "D", false);
            }
            field(map, "shouldResetCameraPos", "Z", false);
            field(map, "attachedCamera", "Z", true);
            field(map, "settingsButton", "Lnet/minecraft/client/gui/components/Button;", false);
            method(map, "onSettingsButton", "(Lnet/minecraft/client/gui/components/Button;)V");
            for (String name : List.of("init", "tick", "removed")) {
                method(map, name, "()V");
            }
            MethodNode render = method(map, "extractRenderState",
                "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V");
            if (render != null) {
                renderInjections(render);
                require(invokes(render, PROVIDER, "getRenderer", GET_RENDERER),
                    "GuiMap.extractRenderState no longer picks the explored terrain's render type");
            }
            MethodNode zoom = method(map, "applyZoomLimits", "()V");
            if (zoom != null) {
                require(zoom.localVariables != null && zoom.localVariables.stream()
                        .filter(local -> local.name.equals("minScale") && local.desc.equals("D")).count() == 1,
                    "GuiMap.applyZoomLimits: local minScale is missing or ambiguous");
            }
        }
        ClassNode branch = xaeroClass("xaero/map/region/texture/BranchTextureRenderer");
        if (branch != null) {
            field(branch, "black4f", "Lorg/joml/Vector4f;", false);
            require(branch.methods.stream().anyMatch(m -> m.name.equals("<init>")),
                "BranchTextureRenderer has no constructor to inject into");
            require(branch.methods.stream().filter(m -> m.name.equals("render")
                    && invokes(m, PROVIDER, "getRenderer", GET_RENDERER)).count() == 1,
                "BranchTextureRenderer.render no longer picks the branch render type");
        }
        ClassNode texture = xaeroClass("xaero/map/region/texture/BranchRegionTexture");
        if (texture != null) {
            require(texture.methods.stream().filter(m -> m.name.equals("readCacheData") && m.desc.endsWith(")V")).count() == 1,
                "BranchRegionTexture.readCacheData changed");
        }
        ClassNode branchRegion = xaeroClass("xaero/map/region/BranchLeveledRegion");
        if (branchRegion != null) method(branchRegion, "findCacheFile", "(Lxaero/map/file/MapSaveLoad;)Ljava/io/File;");
        ClassNode clientOnly = xaeroClass("xaero/map/WorldMapClientOnly");
        if (clientOnly != null) method(clientOnly, "loadLaterClientRender", "()V");
    }

    /** Both GuiMapMixin injections into extractRenderState, including the locals they capture. */
    private void renderInjections(MethodNode render) {
        int providers = 0;
        int draws = 0;
        for (AbstractInsnNode instruction : render.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.owner.equals("xaero/map/MapProcessor") && call.name.equals("getMultiTextureRenderTypeRenderers")
                && call.desc.equals("()L" + PROVIDER + ";")) {
                providers++;
                capturedLocals(render, instruction);
            }
            if (call.owner.equals(PROVIDER) && call.name.equals("draw") && call.desc.equals("(L" + RENDERER + ";)V")) {
                draws++;
                if (draws == 2) capturedLocals(render, instruction.getNext());
            }
        }
        require(providers == 1, "GuiMap.extractRenderState: expected one biome background injection point, found " + providers);
        require(draws >= 2, "GuiMap.extractRenderState: the terrain flush used by biome focus is missing");
    }

    private void capturedLocals(MethodNode method, AbstractInsnNode injection) {
        if (method.localVariables == null) {
            this.problems.add("GuiMap.extractRenderState has no local variable names to capture");
            return;
        }
        int index = method.instructions.indexOf(injection);
        for (String name : List.of("matrixStack", "flooredCameraX", "flooredCameraZ")) {
            String descriptor = name.equals("matrixStack") ? "Lcom/mojang/blaze3d/vertex/PoseStack;" : "I";
            long matches = method.localVariables.stream().filter(local -> local.name.equals(name)
                && local.desc.equals(descriptor)
                && method.instructions.indexOf(local.start) <= index
                && method.instructions.indexOf(local.end) > index).count();
            require(matches == 1, "GuiMap.extractRenderState: captured local " + name + " is missing or ambiguous");
        }
    }

    private static boolean invokes(MethodNode method, String owner, String name, String descriptor) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                && call.name.equals(name) && call.desc.equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private ClassNode xaeroClass(String name) {
        ClassNode node = node(name);
        if (node == null) this.problems.add("Missing Xaero class " + name);
        return node;
    }

    private void field(ClassNode owner, String name, String descriptor, boolean isStatic) {
        require(owner.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(descriptor)
                && ((f.access & Opcodes.ACC_STATIC) != 0) == isStatic),
            "Missing field " + owner.name + "." + name + " " + descriptor);
    }

    private MethodNode method(ClassNode owner, String name, String descriptor) {
        MethodNode found = owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor))
            .findFirst().orElse(null);
        require(found != null, "Missing method " + owner.name + "." + name + descriptor);
        return found;
    }

    private void require(boolean condition, String problem) {
        if (!condition) this.problems.add(problem);
    }

    // Linkage: every Xaero class and member that Seed Atlas bytecode refers to.

    private record Member(String owner, String name, String descriptor, boolean method) { }

    private static final class References {
        private final Set<String> types = new LinkedHashSet<>();
        private final Set<Member> members = new LinkedHashSet<>();
    }

    private void linkage() {
        var queue = new ArrayDeque<>(ROOTS);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!this.checked.add(name)) continue;
            ClassNode own = node(name);
            if (own == null) {
                this.problems.add("Seed Atlas class " + name + " could not be read");
                continue;
            }
            List<String> sink = OPTIONAL.contains(name) ? this.warnings : this.problems;
            References references = references(own);
            for (String type : references.types) {
                if (type.startsWith(OWN)) {
                    queue.add(type);
                } else if (type.startsWith(XAERO) && node(type) == null) {
                    sink.add(simple(name) + " uses missing Xaero class " + type);
                }
            }
            for (Member member : references.members) {
                if (member.owner.startsWith(XAERO) && node(member.owner) != null && !declares(member)) {
                    sink.add(simple(name) + " uses missing " + (member.method ? "method " : "field ")
                        + member.owner + "." + member.name + member.descriptor);
                }
            }
            inheritance(own, sink);
        }
    }

    private static References references(ClassNode node) {
        References references = new References();
        type(references, node.superName);
        node.interfaces.forEach(name -> type(references, name));
        for (FieldNode field : node.fields) descriptor(references, field.desc);
        for (MethodNode method : node.methods) {
            descriptor(references, method.desc);
            for (AbstractInsnNode instruction : method.instructions) {
                switch (instruction) {
                    case FieldInsnNode field -> {
                        type(references, field.owner);
                        descriptor(references, field.desc);
                        references.members.add(new Member(field.owner, field.name, field.desc, false));
                    }
                    case MethodInsnNode call -> {
                        type(references, call.owner);
                        descriptor(references, call.desc);
                        references.members.add(new Member(call.owner, call.name, call.desc, true));
                    }
                    case TypeInsnNode typeInstruction -> type(references, typeInstruction.desc);
                    case MultiANewArrayInsnNode array -> descriptor(references, array.desc);
                    case LdcInsnNode constant -> constant(references, constant.cst);
                    case InvokeDynamicInsnNode dynamic -> {
                        descriptor(references, dynamic.desc);
                        for (Object argument : dynamic.bsmArgs) constant(references, argument);
                    }
                    default -> { }
                }
            }
            for (TryCatchBlockNode handler : method.tryCatchBlocks) {
                if (handler.type != null) type(references, handler.type);
            }
        }
        return references;
    }

    private static void constant(References references, Object value) {
        if (value instanceof Type type) {
            descriptor(references, type.getDescriptor());
        } else if (value instanceof Handle handle) {
            type(references, handle.getOwner());
            descriptor(references, handle.getDesc());
            boolean method = handle.getTag() > Opcodes.H_PUTSTATIC;
            references.members.add(new Member(handle.getOwner(), handle.getName(), handle.getDesc(), method));
        }
    }

    private static void type(References references, String internalName) {
        if (internalName == null) return;
        Type type = internalName.startsWith("[") ? Type.getType(internalName) : Type.getObjectType(internalName);
        while (type.getSort() == Type.ARRAY) type = type.getElementType();
        if (type.getSort() == Type.OBJECT) references.types.add(type.getInternalName());
    }

    private static void descriptor(References references, String descriptor) {
        Type type = Type.getType(descriptor);
        if (type.getSort() == Type.METHOD) {
            for (Type argument : type.getArgumentTypes()) descriptor(references, argument.getDescriptor());
            descriptor(references, type.getReturnType().getDescriptor());
            return;
        }
        while (type.getSort() == Type.ARRAY) type = type.getElementType();
        if (type.getSort() == Type.OBJECT) references.types.add(type.getInternalName());
    }

    /** Resolves like the JVM: the owner, its superclasses, then its interfaces. */
    private boolean declares(Member member) {
        return declares(member.owner, member, new HashSet<>());
    }

    private boolean declares(String owner, Member member, Set<String> visited) {
        if (!visited.add(owner)) return false;
        ClassNode node = node(owner);
        // Minecraft and the JDK are pinned by the loader; when they cannot be read, assume the member.
        if (node == null) return !owner.startsWith(XAERO);
        boolean here = member.method
            ? node.methods.stream().anyMatch(m -> m.name.equals(member.name) && m.desc.equals(member.descriptor))
            : node.fields.stream().anyMatch(f -> f.name.equals(member.name) && f.desc.equals(member.descriptor));
        if (here) return true;
        // Constructors are never inherited.
        if (member.method && member.name.equals("<init>")) return false;
        if (node.superName != null && declares(node.superName, member, visited)) return true;
        for (String face : node.interfaces) {
            if (declares(face, member, visited)) return true;
        }
        return false;
    }

    /** A Seed Atlas class extending a Xaero type must still be a valid, complete subclass. */
    private void inheritance(ClassNode own, List<String> sink) {
        boolean xaeroParent = own.superName != null && own.superName.startsWith(XAERO);
        if (!xaeroParent && own.interfaces.stream().noneMatch(name -> name.startsWith(XAERO))) return;
        if (xaeroParent) {
            ClassNode parent = node(own.superName);
            if (parent != null && (parent.access & Opcodes.ACC_FINAL) != 0) {
                sink.add(simple(own.name) + " extends the now final Xaero class " + own.superName);
            }
        }
        if ((own.access & Opcodes.ACC_ABSTRACT) != 0) return;
        for (Member required : abstractXaeroMethods(own)) {
            if (!implemented(own, required)) {
                sink.add(simple(own.name) + " does not implement " + required.owner + "." + required.name + required.descriptor);
            }
        }
    }

    private List<Member> abstractXaeroMethods(ClassNode own) {
        List<Member> result = new ArrayList<>();
        var queue = new ArrayDeque<String>();
        if (own.superName != null) queue.add(own.superName);
        queue.addAll(own.interfaces);
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!visited.add(name) || !name.startsWith(XAERO)) continue;
            ClassNode node = node(name);
            if (node == null) continue;
            for (MethodNode method : node.methods) {
                if ((method.access & Opcodes.ACC_ABSTRACT) != 0 && (method.access & Opcodes.ACC_STATIC) == 0) {
                    result.add(new Member(name, method.name, method.desc, true));
                }
            }
            if (node.superName != null) queue.add(node.superName);
            queue.addAll(node.interfaces);
        }
        return result;
    }

    /** A concrete method in the class chain, or a default method in any interface, implements it. */
    private boolean implemented(ClassNode own, Member required) {
        var queue = new ArrayDeque<String>();
        queue.add(own.name);
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!visited.add(name)) continue;
            ClassNode node = name.equals(own.name) ? own : node(name);
            if (node == null) continue;
            if (node.methods.stream().anyMatch(m -> m.name.equals(required.name) && m.desc.equals(required.descriptor)
                && (m.access & Opcodes.ACC_ABSTRACT) == 0)) {
                return true;
            }
            if (node.superName != null) queue.add(node.superName);
            queue.addAll(node.interfaces);
        }
        return false;
    }

    private ClassNode node(String internalName) {
        return this.nodes.computeIfAbsent(internalName, name -> {
            try {
                return Optional.ofNullable(this.source.read(name));
            } catch (RuntimeException | LinkageError exception) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private static String simple(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }
}
