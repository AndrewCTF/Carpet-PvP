package carpet.logic.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordStoreTest
{
    /** Few enough rounds for a test to hash many times; the shipped count has a test of its own. */
    private static final int QUICK = 1_000;
    private static final String PASSWORD = "correct horse battery";

    @TempDir
    Path folder;

    private Path file()
    {
        return folder.resolve("carpetlogic").resolve("admins.json");
    }

    @Test
    void theRightPasswordIsTheOnlyOneThatVerifies() throws IOException
    {
        PasswordStore store = new PasswordStore(file(), QUICK);
        UUID steve = UUID.randomUUID();
        store.set(steve, "Steve", PASSWORD);

        assertEquals(steve, store.verify("Steve", PASSWORD));
        assertEquals(steve, store.verify("steve", PASSWORD), "a name is not case sensitive in Minecraft");
        assertNull(store.verify("Steve", PASSWORD + "x"));
        assertNull(store.verify("Steve", PASSWORD.substring(1)));
        assertNull(store.verify("Steve", ""));
        assertNull(store.verify("Alex", PASSWORD), "a name without a password");
    }

    @Test
    void onlyASaltedHashReachesTheDisk() throws IOException
    {
        PasswordStore store = new PasswordStore(file(), QUICK);
        store.set(UUID.randomUUID(), "Steve", PASSWORD);
        store.set(UUID.randomUUID(), "Alex", PASSWORD);

        String written = Files.readString(file(), StandardCharsets.UTF_8);
        assertFalse(written.contains(PASSWORD), "the password itself is in the file");
        assertTrue(written.contains("\"iterations\": " + QUICK), written);
        List<String> salts = written.lines().filter(line -> line.contains("\"salt\"")).toList();
        List<String> hashes = written.lines().filter(line -> line.contains("\"hash\"")).toList();
        assertEquals(2, salts.size());
        assertNotEquals(salts.get(0), salts.get(1), "each account has a salt of its own");
        assertNotEquals(hashes.get(0), hashes.get(1), "so the same password hashes differently for each");
    }

    @Test
    void theFileIsWrittenWholeAndReadBack() throws IOException
    {
        PasswordStore store = new PasswordStore(file(), QUICK);
        UUID steve = UUID.randomUUID();
        store.set(steve, "Steve", PASSWORD);

        try (Stream<Path> files = Files.list(file().getParent()))
        {
            assertEquals(List.of("admins.json"), files.map(path -> path.getFileName().toString()).toList(),
                    "nothing but the file is left behind");
        }
        if (Files.getFileStore(file()).supportsFileAttributeView("posix"))
        {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file());
            assertEquals("rw-------", PosixFilePermissions.toString(permissions), "only the server's own user reads it");
        }

        PasswordStore again = new PasswordStore(file(), QUICK);
        again.load();
        assertEquals(steve, again.verify("Steve", PASSWORD));
        assertTrue(again.has(steve));
        assertNull(again.verify("Steve", "another password"));
    }

    @Test
    void aNewPasswordReplacesTheOldOne() throws IOException
    {
        PasswordStore store = new PasswordStore(file(), QUICK);
        UUID steve = UUID.randomUUID();
        store.set(steve, "Steve", PASSWORD);
        store.set(steve, "Steve", "a different password");

        assertNull(store.verify("Steve", PASSWORD));
        assertEquals(steve, store.verify("Steve", "a different password"));

        // The same player under a new name: the old name signs nobody in.
        store.set(steve, "Stephen", PASSWORD);
        assertNull(store.verify("Steve", "a different password"));
        assertEquals(steve, store.verify("Stephen", PASSWORD));
    }

    @Test
    void aPasswordHasAShortestAndALongestLength()
    {
        assertNotNull(PasswordStore.refusal(null));
        assertNotNull(PasswordStore.refusal(""));
        assertNotNull(PasswordStore.refusal("x".repeat(PasswordStore.MIN_LENGTH - 1)));
        assertNull(PasswordStore.refusal("x".repeat(PasswordStore.MIN_LENGTH)));
        assertNull(PasswordStore.refusal("x".repeat(PasswordStore.MAX_LENGTH)));
        assertNotNull(PasswordStore.refusal("x".repeat(PasswordStore.MAX_LENGTH + 1)));
        assertTrue(PasswordStore.MIN_LENGTH >= 10);
    }

    @Test
    void aNameWithoutAPasswordCostsAHashAsWell() throws IOException
    {
        // Enough rounds for a hash to take milliseconds, so that leaving it out could not go unnoticed.
        PasswordStore store = new PasswordStore(file(), 100_000);
        store.set(UUID.randomUUID(), "Steve", PASSWORD);

        long known = Long.MAX_VALUE;
        long unknown = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++)
        {
            long start = System.nanoTime();
            store.verify("Steve", "not the password");
            known = Math.min(known, System.nanoTime() - start);
            start = System.nanoTime();
            store.verify("Nobody", "not the password");
            unknown = Math.min(unknown, System.nanoTime() - start);
        }
        assertTrue(unknown * 4 > known, "a name with a password took " + known + " ns and one without " + unknown + " ns");
    }

    @Test
    void theShippedIterationCountVerifies() throws IOException
    {
        assertEquals(600_000, PasswordStore.ITERATIONS, "the count OWASP gives for PBKDF2-HMAC-SHA256");
        PasswordStore store = new PasswordStore(file());
        UUID steve = UUID.randomUUID();
        store.set(steve, "Steve", PASSWORD);

        assertTrue(Files.readString(file()).contains("\"iterations\": 600000"));
        assertEquals(steve, store.verify("Steve", PASSWORD));
    }

    @Test
    void aFileThisServerDidNotWriteIsReported() throws IOException
    {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{\"steve\": {\"name\": \"Steve\"}}");
        PasswordStore store = new PasswordStore(file(), QUICK);

        assertThrows(IOException.class, store::load);
        assertNull(store.verify("Steve", PASSWORD));
    }
}
