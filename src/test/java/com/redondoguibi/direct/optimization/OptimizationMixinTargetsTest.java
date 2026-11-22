package com.redondoguibi.direct.optimization;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Mixins are not applied in unit tests, so their targets are checked against the real Minecraft
 * 1.21.1 / NeoForge 21.1 bytecode on the test classpath instead: every method, field and invoked
 * instruction named by an optimization mixin must exist with the exact descriptor and owner. A
 * renamed or patched target fails here instead of silently skipping the optimization in game.
 */
class OptimizationMixinTargetsTest {
    private static ClassNode load(String internalName) throws IOException {
        try (InputStream stream = OptimizationMixinTargetsTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(stream, "target class missing from the classpath: " + internalName);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst()
                .orElseThrow(() -> new AssertionError(owner.name + "." + name + descriptor + " not found"));
    }

    private static void field(ClassNode owner, String name, String descriptor, boolean expectFinal) {
        var field = owner.fields.stream().filter(f -> f.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(owner.name + "." + name + " not found"));
        assertEquals(descriptor, field.desc, owner.name + "." + name);
        assertEquals(expectFinal, (field.access & Opcodes.ACC_FINAL) != 0, owner.name + "." + name + " finality (@Final/@Mutable)");
    }

    private static long invocations(MethodNode method, int opcode, String owner, String name, String descriptor) {
        long count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)) count++;
        }
        return count;
    }

    @Test
    void biomeTemperature() throws IOException {
        ClassNode biome = load("net/minecraft/world/level/biome/Biome");
        method(biome, "getTemperature", "(Lnet/minecraft/core/BlockPos;)F");
        method(biome, "getHeightAdjustedTemperature", "(Lnet/minecraft/core/BlockPos;)F");
    }

    @Test
    void classInstanceMultiMap() throws IOException {
        ClassNode map = load("net/minecraft/util/ClassInstanceMultiMap");
        method(map, "<init>", "(Ljava/lang/Class;)V");
        method(map, "find", "(Ljava/lang/Class;)Ljava/util/Collection;");
        field(map, "byClass", "Ljava/util/Map;", true);
    }

    @Test
    void trackedEntity() throws IOException {
        ClassNode tracked = load("net/minecraft/server/level/ChunkMap$TrackedEntity");
        assertTrue(tracked.methods.stream().anyMatch(m -> m.name.equals("<init>")));
        field(tracked, "seenBy", "Ljava/util/Set;", true);
    }

    @Test
    void threadPriorities() throws IOException {
        MethodNode makeExecutor = method(load("net/minecraft/Util"), "makeExecutor", "(Ljava/lang/String;)Ljava/util/concurrent/ExecutorService;");
        assertEquals(1, invocations(makeExecutor, Opcodes.INVOKESPECIAL, "java/util/concurrent/ForkJoinPool", "<init>",
                "(ILjava/util/concurrent/ForkJoinPool$ForkJoinWorkerThreadFactory;Ljava/lang/Thread$UncaughtExceptionHandler;Z)V"));
        assertTrue(load("net/minecraft/client/server/IntegratedServer").methods.stream().anyMatch(m -> m.name.equals("<init>")));
        method(load("net/minecraft/server/MinecraftServer"), "getRunningThread", "()Ljava/lang/Thread;");
    }

    @Test
    void blockStateCacheArrays() throws IOException {
        ClassNode cache = load("net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase$Cache");
        MethodNode constructor = cache.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
        assertTrue(invocations(constructor, Opcodes.INVOKESTATIC, "net/minecraft/world/level/block/SupportType", "values",
                "()[Lnet/minecraft/world/level/block/SupportType;") >= 1);
        assertEquals(1, invocations(constructor, Opcodes.INVOKESTATIC, "net/minecraft/core/Direction$Axis", "values",
                "()[Lnet/minecraft/core/Direction$Axis;"));
    }

    @Test
    void stateHolderNeighbours() throws IOException {
        ClassNode holder = load("net/minecraft/world/level/block/state/StateHolder");
        method(holder, "populateNeighbours", "(Ljava/util/Map;)V");
        field(holder, "neighbours", "Lcom/google/common/collect/Table;", false);
    }

    @Test
    void multipartDeduplication() throws IOException {
        method(load("net/minecraft/client/renderer/block/model/multipart/Selector"), "getPredicate",
                "(Lnet/minecraft/world/level/block/state/StateDefinition;)Ljava/util/function/Predicate;");
        MethodNode build = method(load("net/minecraft/client/resources/model/MultiPartBakedModel$Builder"), "build",
                "()Lnet/minecraft/client/resources/model/BakedModel;");
        boolean news = false;
        for (AbstractInsnNode instruction : build.instructions)
            news |= instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("net/minecraft/client/resources/model/MultiPartBakedModel");
        assertTrue(news, "Builder.build must construct MultiPartBakedModel directly");
        assertEquals(1, invocations(build, Opcodes.INVOKESPECIAL, "net/minecraft/client/resources/model/MultiPartBakedModel", "<init>", "(Ljava/util/List;)V"));
        ClassNode model = load("net/minecraft/client/resources/model/MultiPartBakedModel");
        method(model, "getSelectors", "(Lnet/minecraft/world/level/block/state/BlockState;)Ljava/util/BitSet;");
        field(model, "selectors", "Ljava/util/List;", true);
        method(load("net/minecraft/client/resources/model/ModelBakery"), "bakeModels",
                "(Lnet/minecraft/client/resources/model/ModelBakery$TextureGetter;)V");
    }
}
