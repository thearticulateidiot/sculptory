package dev.sculptory.server.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.sculptory.protocol.v2.AssetAccess;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One folder's per-asset access on disk: the hidden file
 * {@value #NAME} beside the entries, {@code {"version": 1, "entries": {"<name>": {"mode": "listed", "players":
 * [{"uuid": "...", "name": "..."}]}}}}. Only restricted entries are written; a folder whose entries are all open has
 * no file. Its name starts with a dot, so no {@link LibraryPath} can name it, listings never show it and the quotas
 * never count it.
 *
 * <p>Reading is strict and <b>fails closed</b>: a file that is not valid JSON of this shape (an unknown version or
 * mode, a bad UUID, a name that is not a library file name, a player listed twice, over the caps) is
 * {@link Loaded#corrupt}; the library then treats every entry of the folder as readable by admins only, and refuses
 * to change or rewrite it, until an admin fixes it on disk. A missing file is {@link Loaded#MISSING}: everyone.
 *
 * <p>Pure file code; {@link Library} calls it under its lock.
 */
public final class AccessFile {
    public static final String NAME = ".access.json";
    private static final int VERSION = 1;
    private static final String MODE_LISTED = "listed";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private AccessFile() {}

    /**
     * A folder's access as read: the restricted entries by file name, or {@code corrupt}; {@code size} and
     * {@code mtime} identify the file read (both 0 when there is none).
     */
    public record Loaded(Map<String, AssetAccess> entries, boolean corrupt, long size, long mtime) {
        public static final Loaded MISSING = new Loaded(Map.of(), false, 0, 0);

        public Loaded {
            entries = Collections.unmodifiableMap(new TreeMap<>(entries));
        }

        /** The access of {@code name}: for everyone unless listed (a corrupt file is asked through the library). */
        public AssetAccess of(String name) {
            return entries.getOrDefault(name, AssetAccess.EVERYONE);
        }

        /** Whether the file on disk (by size and time) is still the one read. */
        boolean matches(BasicFileAttributes attrs) {
            return attrs != null && attrs.size() == size && attrs.lastModifiedTime().toMillis() == mtime;
        }

        /** Read when the folder had no file (everyone). */
        boolean missing() {
            return !corrupt && entries.isEmpty() && size == 0 && mtime == 0;
        }
    }

    /** The attributes of the folder's file, or {@code null} when there is none (a link or folder counts as none). */
    static BasicFileAttributes attributes(Path folder) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(folder.resolve(NAME), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return attrs.isRegularFile() ? attrs : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Reads the folder's file; a missing one is {@link Loaded#MISSING}, an unreadable or invalid one is corrupt. */
    static Loaded read(Path folder) {
        Path file = folder.resolve(NAME);
        BasicFileAttributes attrs = attributes(folder);
        if (attrs == null) {
            return Files.exists(file, LinkOption.NOFOLLOW_LINKS) ? corrupt(attrs) : Loaded.MISSING;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            FileDto dto = GSON.fromJson(reader, FileDto.class);
            Map<String, AssetAccess> entries = decode(dto);
            return entries == null ? corrupt(attrs) : new Loaded(entries, false, attrs.size(), attrs.lastModifiedTime().toMillis());
        } catch (NoSuchFileException e) {
            return Loaded.MISSING;
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return corrupt(attrs);
        }
    }

    private static Loaded corrupt(BasicFileAttributes attrs) {
        return new Loaded(Map.of(), true, attrs == null ? -1 : attrs.size(), attrs == null ? -1 : attrs.lastModifiedTime().toMillis());
    }

    /** The entries of a parsed file, or {@code null} when anything in it is not as written by this class. */
    private static Map<String, AssetAccess> decode(FileDto dto) {
        if (dto == null || dto.version != VERSION || dto.entries == null) return null;
        Map<String, AssetAccess> entries = new TreeMap<>();
        for (Map.Entry<String, EntryDto> entry : dto.entries.entrySet()) {
            String name = entry.getKey();
            EntryDto value = entry.getValue();
            if (name == null || !LibraryPath.validName(name) || LibraryPath.Kind.of(name) == null || value == null) return null;
            if (!MODE_LISTED.equals(value.mode) || value.players == null || value.players.isEmpty()
                    || value.players.size() > AssetAccess.MAX_PLAYERS) {
                return null;
            }
            List<AssetAccess.Grantee> players = new ArrayList<>(value.players.size());
            for (PlayerDto player : value.players) {
                if (player == null || player.uuid == null || player.name == null || player.name.isEmpty()) return null;
                UUID uuid;
                try {
                    uuid = UUID.fromString(player.uuid);
                } catch (IllegalArgumentException e) {
                    return null;
                }
                if (!uuid.toString().equals(player.uuid.toLowerCase(Locale.ROOT))) return null;
                try {
                    players.add(new AssetAccess.Grantee(uuid, player.name));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
            try {
                entries.put(name, AssetAccess.listed(players));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return entries;
    }

    /**
     * Writes {@code entries} (the restricted ones; open entries are left out) as the folder's file, atomically, or
     * deletes the file when nothing is restricted. The folder must exist.
     */
    static void write(Path folder, Map<String, AssetAccess> entries) throws IOException {
        Path file = folder.resolve(NAME);
        FileDto dto = new FileDto();
        for (Map.Entry<String, AssetAccess> entry : entries.entrySet()) {
            AssetAccess access = entry.getValue();
            if (!access.restricted()) continue;
            EntryDto value = new EntryDto();
            value.mode = MODE_LISTED;
            value.players = new ArrayList<>(access.players().size());
            for (AssetAccess.Grantee grantee : access.players()) {
                PlayerDto player = new PlayerDto();
                player.uuid = Objects.requireNonNull(grantee.uuid(), "a stored grantee has a UUID").toString();
                player.name = grantee.name();
                value.players.add(player);
            }
            dto.entries.put(entry.getKey(), value);
        }
        if (dto.entries.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Path temp = folder.resolve("." + UUID.randomUUID() + ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                GSON.toJson(dto, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Gson form. */
    private static final class FileDto {
        int version = VERSION;
        TreeMap<String, EntryDto> entries = new TreeMap<>();
    }

    private static final class EntryDto {
        String mode;
        List<PlayerDto> players;
    }

    private static final class PlayerDto {
        String uuid;
        String name;
    }
}
