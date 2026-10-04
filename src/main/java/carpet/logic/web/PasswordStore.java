package carpet.logic.web;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The web passwords of the editor's admins. What is kept of a password is a salted PBKDF2 hash, in one file
 * under the world folder; the password itself is never stored, and neither it nor its hash is ever logged.
 */
public class PasswordStore
{
    /**
     * Rounds of PBKDF2-HMAC-SHA256, the figure OWASP's password storage guidance gives for it. It is what a
     * guess costs somebody who got hold of the file, and what one sign-in costs the server: about a tenth of
     * a second of one core on a current desktop processor.
     */
    static final int ITERATIONS = 600_000;
    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private record Entry(UUID id, String name, byte[] salt, byte[] hash, int iterations)
    {
    }

    private final Path file;
    private final int iterations;
    private final SecureRandom random = new SecureRandom();
    // Keyed by the name in lower case, which is what a sign-in gives.
    private final Map<String, Entry> entries = new HashMap<>();
    // What a name without a password is checked against, so that it costs the same as one that has one.
    private final byte[] nobodySalt = new byte[SALT_BYTES];
    private final byte[] nobodyHash = new byte[HASH_BITS / 8];

    public PasswordStore(Path file)
    {
        this(file, ITERATIONS);
    }

    PasswordStore(Path file, int iterations)
    {
        this.file = file;
        this.iterations = iterations;
        random.nextBytes(nobodySalt);
        random.nextBytes(nobodyHash);
    }

    /**
     * @return why the password cannot be used, or null when it can
     */
    public static String refusal(String password)
    {
        if (password == null || password.length() < MIN_LENGTH)
        {
            return "A password needs at least " + MIN_LENGTH + " characters";
        }
        if (password.length() > MAX_LENGTH)
        {
            return "A password can have at most " + MAX_LENGTH + " characters";
        }
        return null;
    }

    public synchronized void load() throws IOException
    {
        entries.clear();
        if (!Files.exists(file))
        {
            return;
        }
        try
        {
            JsonObject accounts = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Base64.Decoder base64 = Base64.getDecoder();
            for (Map.Entry<String, JsonElement> account : accounts.entrySet())
            {
                JsonObject stored = account.getValue().getAsJsonObject();
                entries.put(account.getKey(), new Entry(UUID.fromString(stored.get("id").getAsString()),
                        stored.get("name").getAsString(), base64.decode(stored.get("salt").getAsString()),
                        base64.decode(stored.get("hash").getAsString()), stored.get("iterations").getAsInt()));
            }
        }
        catch (JsonParseException | IllegalStateException | IllegalArgumentException | NullPointerException e)
        {
            entries.clear();
            throw new IOException(file + " is not a password file this server wrote", e);
        }
    }

    /**
     * Gives an account a password, replacing the one it had.
     */
    public void set(UUID id, String name, String password) throws IOException
    {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        Entry entry = new Entry(id, name, salt, derive(password, salt, iterations), iterations);
        synchronized (this)
        {
            // An account is one player, whatever it was called when it last set a password.
            entries.values().removeIf(old -> old.id().equals(id));
            entries.put(key(name), entry);
            save();
        }
    }

    /**
     * Checks a name and password. One hash is computed whether or not the name has a password, and the
     * comparison takes the same time whatever the result.
     *
     * @return the account they belong to, or null
     */
    public UUID verify(String name, String password)
    {
        Entry entry;
        synchronized (this)
        {
            entry = entries.get(key(name));
        }
        byte[] derived = derive(password, entry == null ? nobodySalt : entry.salt(), entry == null ? iterations : entry.iterations());
        boolean matches = MessageDigest.isEqual(derived, entry == null ? nobodyHash : entry.hash());
        return entry != null && matches ? entry.id() : null;
    }

    public synchronized boolean has(UUID id)
    {
        return entries.values().stream().anyMatch(entry -> entry.id().equals(id));
    }

    // Written beside the file and moved over it, so a crash half way leaves the old file, not half of a new one.
    private void save() throws IOException
    {
        JsonObject accounts = new JsonObject();
        Base64.Encoder base64 = Base64.getEncoder();
        for (Map.Entry<String, Entry> account : entries.entrySet())
        {
            Entry entry = account.getValue();
            JsonObject stored = new JsonObject();
            stored.addProperty("id", entry.id().toString());
            stored.addProperty("name", entry.name());
            stored.addProperty("salt", base64.encodeToString(entry.salt()));
            stored.addProperty("hash", base64.encodeToString(entry.hash()));
            stored.addProperty("iterations", entry.iterations());
            accounts.add(account.getKey(), stored);
        }
        Files.createDirectories(file.getParent());
        // A temporary file is created readable by its owner only, where the file system knows about owners.
        Path fresh = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try
        {
            Files.writeString(fresh, new GsonBuilder().setPrettyPrinting().create().toJson(accounts), StandardCharsets.UTF_8);
            try
            {
                Files.move(fresh, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(fresh, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            Files.deleteIfExists(fresh);
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations)
    {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try
        {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        }
        catch (GeneralSecurityException e)
        {
            throw new IllegalStateException(e);
        }
        finally
        {
            spec.clearPassword();
        }
    }

    private static String key(String name)
    {
        return name.toLowerCase(Locale.ROOT);
    }
}
