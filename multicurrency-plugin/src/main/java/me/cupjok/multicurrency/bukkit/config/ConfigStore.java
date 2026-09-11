package me.cupjok.multicurrency.bukkit.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Safe access to {@code config.yml} for code that writes it (config updater, in-game editor).
 *
 * <ul>
 *   <li>Every write first copies the current file to {@code backups/} and keeps the newest backups only.</li>
 *   <li>The new content is written to a temporary file, flushed to disk and moved over the original,
 *       so a crash leaves either the old or the new file, never a half-written one.</li>
 *   <li>The hash of the last content that was loaded is remembered. A writer can check it to refuse
 *       overwriting manual edits that were saved but not reloaded yet.</li>
 * </ul>
 */
public final class ConfigStore {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT);

    private final Path file;
    private final Path backupDir;
    private final int keepBackups;
    private String loadedHash;

    public ConfigStore(Path file, Path backupDir, int keepBackups) {
        this.file = file;
        this.backupDir = backupDir;
        this.keepBackups = Math.max(1, keepBackups);
    }

    public Path file() {
        return file;
    }

    public String read() throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public static String hash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records {@code content} as the configuration that is currently active. */
    public synchronized void markLoaded(String content) {
        this.loadedHash = hash(content);
    }

    /** {@code true} when {@code content} (the file as it is now) is what was last loaded or written. */
    public synchronized boolean isLoaded(String content) {
        return loadedHash != null && loadedHash.equals(hash(content));
    }

    /**
     * Backs up the current file, then replaces it atomically with {@code content}.
     *
     * @param reason short tag for the backup file name, e.g. {@code editor} or {@code update}
     * @return the backup file, or {@code null} if there was no file to back up
     */
    public synchronized Path write(String content, String reason) throws IOException {
        Path backup = null;
        if (Files.exists(file)) {
            Files.createDirectories(backupDir);
            String tag = reason == null ? "backup" : reason.replaceAll("[^A-Za-z0-9_-]", "");
            backup = backupDir.resolve("config-" + LocalDateTime.now().format(STAMP) + "-" + tag + ".yml");
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, "config-", ".yml.tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(java.nio.ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
                ch.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        markLoaded(content);
        pruneBackups();
        return backup;
    }

    private void pruneBackups() throws IOException {
        if (!Files.isDirectory(backupDir)) {
            return;
        }
        List<Path> backups = new ArrayList<>();
        try (Stream<Path> s = Files.list(backupDir)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("config-") && n.endsWith(".yml");
            }).forEach(backups::add);
        }
        // Names start with a sortable timestamp: oldest first.
        backups.sort(null);
        for (int i = 0; i < backups.size() - keepBackups; i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }
}
