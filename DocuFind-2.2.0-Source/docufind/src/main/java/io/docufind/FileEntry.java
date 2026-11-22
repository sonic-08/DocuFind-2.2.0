package io.docufind;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

record FileEntry(Path path, boolean folder, long size, long modified) {
    static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    static FileEntry from(Path path, BasicFileAttributes attributes) {
        return new FileEntry(path, attributes.isDirectory(), attributes.size(),
                attributes.lastModifiedTime().toMillis());
    }

    String name() {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    static String fold(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    static String searchPath(String text) {
        return fold(text).replace('\\', '/');
    }

    static String pathKey(Path path) {
        String text = path.toAbsolutePath().normalize().toString();
        return WINDOWS ? searchPath(text) : text;
    }

    static String childPrefix(Path parent) {
        String key = pathKey(parent);
        return key.endsWith("/") ? key : key + "/";
    }

    static String afterChildren(String prefix) {
        return prefix.substring(0, prefix.length() - 1) + "0";
    }
}
