package jp.takanawamenntai.chmate;

import app.morphe.patcher.patch.*;
import app.morphe.patcher.util.proxy.mutableTypes.MutableField;
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipFile;
import kotlin.Unit;

public final class ProcessingLogPatch {
    private static final String EXT = "Lapp/morphe/extension/chmate/";
    private static final String ADDED = "/processing-log-new.dex";
    private static final String BEFORE = "/processing-log-before.dex";
    private static final String AFTER = "/processing-log-after.dex";
    private static final Opcodes OPCODES = Opcodes.forApi(21);

    private static final BytecodePatch PATCH = PatchKt.bytecodePatch(
            "処理ログ（MEGA）", "Haiagaru 1.45.0の処理ログをMEGAに保存し、選んだログを端末へダウンロードします。",
            true, builder -> {
                builder.compatibleWith(new Compatibility("jp.co.airfront.android.a2chMate", "ChMate", null,
                        ApkFileType.APK, Integer.valueOf(0x303030), Set.of(),
                        List.of(new AppTarget("0.8.10.191 dev", Map.of(), false, null, null)), null));
                builder.extendWith(() -> ProcessingLogPatch.class.getResourceAsStream(ADDED));
                // Finalizers run in reverse name order. Install before the existing NG and continuous reload
                // finalizers so both see the same bytecode as in the validated log build.
                builder.finalize(context -> {
                    try {
                        install(context);
                    } catch (IOException error) {
                        throw new IllegalStateException("Cannot read the MEGA processing log overlay", error);
                    }
                    return Unit.INSTANCE;
                });
                return Unit.INSTANCE;
            });

    public static BytecodePatch getProcessingLogPatch() {
        return PATCH;
    }

    private static void install(BytecodePatchContext context) throws IOException {
        var baseline = byKey(readResource(BEFORE).getClasses(), ClassDef::getType);
        var changes = readResource(AFTER).getClasses();
        // Verify every replaced method before changing anything. Missing Haiagaru
        // or a conflicting patch fails explicitly instead of guessing at bytecode.
        for (var changed : changes) {
            var original = byKey(baseline.get(changed.getType()).getMethods(), Method::toString);
            var target = context.classDefByOrNull(changed.getType());
            if (target == null) {
                throw new IllegalStateException("Select Haiagaru 1.45.0 and MEGAバックアップ・同期");
            }
            var active = byKey(target.getMethods(), Method::toString);
            for (var method : changed.getMethods()) {
                var previous = original.get(method.toString());
                var current = active.get(method.toString());
                if (previous == null ? current != null
                        : current == null || !Arrays.equals(methodBody(previous), methodBody(current))) {
                    throw new IllegalStateException("Incompatible Haiagaru 1.45.0 method: " + method);
                }
            }
        }
        for (var changed : changes) {
            var target = context.mutableClassDefBy(changed.getType());
            for (var field : changed.getFields()) {
                target.getFields().add(new MutableField(field));
            }
            for (var method : changed.getMethods()) {
                var replacement = new MutableMethod(method);
                target.getMethods().remove(replacement);
                target.getMethods().add(replacement);
            }
        }
    }

    // Build-time entry point. The overlay is computed from the exact published
    // Haiagaru release and the verified CI build, with debug line data ignored.
    public static void main(String[] args) throws IOException {
        var original = readMpp(Path.of(args[0]));
        var patched = readMpp(Path.of(args[1]));
        var destination = Path.of(args[2]);
        var before = new ArrayList<ClassDef>();
        var after = new ArrayList<ClassDef>();
        var added = new ArrayList<ClassDef>();
        for (var entry : patched.entrySet()) {
            var next = entry.getValue();
            if (!isLogClass(next.getType())) continue;
            var previous = original.get(next.getType());
            if (previous == null) {
                added.add(next);
                continue;
            }
            var oldMethods = byKey(previous.getMethods(), Method::toString);
            var oldFields = new HashSet<String>();
            previous.getFields().forEach(field -> oldFields.add(field.toString()));
            var previousBodies = new ArrayList<Method>();
            var nextBodies = new ArrayList<Method>();
            var fields = new ArrayList<com.android.tools.smali.dexlib2.iface.Field>();
            for (var method : next.getMethods()) {
                var old = oldMethods.get(method.toString());
                if (old != null && Arrays.equals(methodBody(old), methodBody(method))) continue;
                if (old != null) previousBodies.add(old);
                nextBodies.add(method);
            }
            next.getFields().forEach(field -> {
                if (!oldFields.contains(field.toString())) fields.add(field);
            });
            if (!nextBodies.isEmpty() || !fields.isEmpty()) {
                before.add(subset(previous, List.of(), previousBodies));
                after.add(subset(next, fields, nextBodies));
            }
        }
        for (var required : List.of("HaiagaruMegaSync;", "HaiagaruMegaClient;")) {
            if (after.stream().noneMatch(def -> def.getType().equals(EXT + required))) {
                throw new IOException("Missing log changes for " + required);
            }
        }
        if (added.stream().noneMatch(def -> def.getType().equals(EXT + "ProcessingLog;"))) {
            throw new IOException("Missing ProcessingLog runtime");
        }
        Files.createDirectories(destination);
        Files.write(destination.resolve(ADDED.substring(1)), toDex(added));
        Files.write(destination.resolve(BEFORE.substring(1)), toDex(before));
        Files.write(destination.resolve(AFTER.substring(1)), toDex(after));
        System.out.printf("MEGA log overlay: %d added classes, %d patched classes%n", added.size(), after.size());
    }

    private static boolean isLogClass(String name) {
        for (var root : List.of("ProcessingLog", "HaiagaruMegaSync", "HaiagaruMegaClient")) {
            var type = EXT + root;
            if (name.equals(type + ";") || name.startsWith(type + "$")) return true;
        }
        return false;
    }

    private static Map<String, ClassDef> readMpp(Path path) throws IOException {
        var classes = new TreeMap<String, ClassDef>();
        try (var zip = new ZipFile(path.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().matches("extensions/chmate(-[0-9]+)?[.]mpe")) continue;
                try (var stream = zip.getInputStream(entry)) {
                    for (var def : readDex(stream).getClasses()) {
                        if (isLogClass(def.getType())) classes.put(def.getType(), def);
                    }
                }
            }
        }
        return classes;
    }

    private static DexBackedDexFile readResource(String name) throws IOException {
        try (var input = ProcessingLogPatch.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing resource " + name);
            return readDex(input);
        }
    }

    private static DexBackedDexFile readDex(InputStream input) throws IOException {
        return DexBackedDexFile.fromInputStream(OPCODES, new BufferedInputStream(input));
    }

    private static <T> Map<String, T> byKey(Iterable<? extends T> entries, Function<T, String> key) {
        var result = new HashMap<String, T>();
        for (var entry : entries) result.put(key.apply(entry), entry);
        return result;
    }

    private static ClassDef subset(ClassDef def,
            Iterable<? extends com.android.tools.smali.dexlib2.iface.Field> fields,
            Iterable<? extends Method> methods) {
        return new ImmutableClassDef(def.getType(), def.getAccessFlags(), def.getSuperclass(),
                def.getInterfaces(), null, null, fields, methods);
    }

    private static byte[] methodBody(Method method) throws IOException {
        var code = method.getImplementation();
        var clean = code == null ? null : new ImmutableMethodImplementation(
                code.getRegisterCount(), code.getInstructions(), code.getTryBlocks(), null);
        var signature = new ImmutableMethod(method.getDefiningClass(), method.getName(),
                method.getParameters().stream().map(p -> new ImmutableMethodParameter(p.getType(), null, null)).toList(),
                method.getReturnType(), method.getAccessFlags(), null, null, clean);
        return toDex(List.of(new ImmutableClassDef(method.getDefiningClass(), 1,
                "Ljava/lang/Object;", null, null, null, null, List.of(signature))));
    }

    private static byte[] toDex(Iterable<? extends ClassDef> classes) throws IOException {
        var pool = new DexPool(OPCODES);
        classes.forEach(pool::internClass);
        var output = new MemoryDataStore();
        try {
            pool.writeTo(output);
            return output.getData();
        } finally {
            output.close();
        }
    }
}
