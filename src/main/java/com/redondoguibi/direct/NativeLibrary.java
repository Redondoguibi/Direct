package com.redondoguibi.direct;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import net.neoforged.fml.loading.FMLPaths;

public final class NativeLibrary {
    private static final String RESOURCE = "/META-INF/native/windows-x86_64/direct_renderer.dll";
    private static volatile boolean loaded;

    private NativeLibrary() {
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static synchronized void load() throws IOException {
        if (loaded) {
            return;
        }
        if (!supportsPlatform(System.getProperty("os.name"), System.getProperty("os.arch"))) {
            throw new IOException("The Direct native renderer requires Windows x86-64.");
        }
        byte[] bytes;
        try (InputStream input = NativeLibrary.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("Native renderer not found at " + RESOURCE
                        + "; build the mod with the Windows C++ toolchain.");
            }
            bytes = input.readAllBytes();
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable.", exception);
        }
        Path directory = FMLPaths.GAMEDIR.get().resolve(".direct").resolve("native");
        Files.createDirectories(directory);
        Path library = directory.resolve("direct_renderer-" + hash + ".dll");
        if (!Files.isRegularFile(library) || Files.size(library) != bytes.length) {
            Path temporary = Files.createTempFile(directory, "direct_renderer-", ".tmp");
            try {
                Files.write(temporary, bytes);
                Files.move(temporary, library, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                Files.deleteIfExists(temporary);
                if (!Files.isRegularFile(library)) {
                    throw exception; // outra instância pode estar usando a DLL; só falha se não existir
                }
            }
        }
        System.load(library.toAbsolutePath().toString());
        loaded = true;
    }

    static boolean supportsPlatform(String osName, String architecture) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows")
                && architecture != null
                && (architecture.equalsIgnoreCase("amd64") || architecture.equalsIgnoreCase("x86_64"));
    }
}