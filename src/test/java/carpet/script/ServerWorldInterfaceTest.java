package carpet.script;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing ever implemented {@code carpet.fakes.ServerWorldInterface}, so the casts to it in the world-data
 * helpers threw ClassCastException as soon as a Scarpet app saved or restored the world. Upstream fabric-carpet
 * for both of these Minecraft versions has neither the interface nor the helpers that cast to it, so neither
 * exists here either.
 */
class ServerWorldInterfaceTest
{
    private static final String INTERFACE = "carpet.fakes.ServerWorldInterface";
    private static final String INTERNAL_NAME = "carpet/fakes/ServerWorldInterface";

    @Test
    void theInterfaceNothingImplementsIsGone()
    {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(INTERFACE));
    }

    @Test
    void noCompiledClassCastsToItAnyMore() throws IOException
    {
        Path classes = Path.of(carpet.script.external.Vanilla.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        assertTrue(Files.isDirectory(classes), "no compiled classes at " + classes);
        List<String> offenders;
        try (Stream<Path> files = Files.walk(classes))
        {
            offenders = files.filter(path -> path.toString().endsWith(".class"))
                    .filter(ServerWorldInterfaceTest::mentionsTheInterface)
                    .map(Path::toString).toList();
        }
        assertTrue(offenders.isEmpty(), "these still refer to " + INTERFACE + ": " + offenders);
    }

    private static boolean mentionsTheInterface(Path classFile)
    {
        try
        {
            return new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1).contains(INTERNAL_NAME);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }
}