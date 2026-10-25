package org.seedatlas.xaero.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Exercises shared command registration with a source type supplied by a loader. */
public final class CommandRegistrationTest {
    public static void main(String[] args) throws Exception {
        CommandDispatcher<Boolean> dispatcher = new CommandDispatcher<>();
        SeedAtlasCommands.register(dispatcher, Source::new);
        for (String command : new String[]{"seedatlas", "seedatlas status", "seedatlas clear", "seedatlas settings"}) {
            var parsed = dispatcher.parse(command, true);
            check(!parsed.getReader().canRead() && parsed.getContext().getCommand() != null,
                "Expected executable client command: " + command);
        }
        for (String seed : new String[]{"-9223372036854775808", "some seed with spaces", "123"}) {
            var parsed = dispatcher.parse("seedatlas seed " + seed, true);
            check(!parsed.getReader().canRead(), "Seed input must be fully consumed");
            check(StringArgumentType.getString(parsed.getContext().build("seedatlas seed " + seed), "seed").equals(seed),
                "Seed text must survive the loader adapter");
        }
        check(!dispatcher.parse("seedatlas status", false).getReader().canRead(), "Status is always available");
        for (String command : new String[]{"seedatlas clear", "seedatlas settings", "seedatlas seed 42"}) {
            check(dispatcher.parse(command, false).getReader().canRead(),
                "World-changing commands need an attended client: " + command);
        }
        check(dispatcher.parse("seedatlas seed", true).getContext().getCommand() == null,
            "Setting a seed requires an argument");
        // Issue #4: own seeds for the Nether and End.
        for (String dimension : new String[]{"nether", "end"}) {
            for (String seed : new String[]{"-42", "text seed with spaces"}) {
                String command = "seedatlas dimension-seed " + dimension + " " + seed;
                var parsed = dispatcher.parse(command, true);
                check(!parsed.getReader().canRead() && parsed.getContext().getCommand() != null,
                    "Expected executable dimension seed command: " + command);
                check(StringArgumentType.getString(parsed.getContext().build(command), "seed").equals(seed),
                    "Dimension seed text must survive the loader adapter");
                check(dispatcher.parse(command, false).getReader().canRead(),
                    "Changing a dimension seed needs an attended client");
            }
            var clear = dispatcher.parse("seedatlas clear " + dimension, true);
            check(!clear.getReader().canRead() && clear.getContext().getCommand() != null,
                "clear <dimension> removes only that dimension's seed");
            check(dispatcher.parse("seedatlas clear " + dimension, false).getReader().canRead(),
                "Clearing a dimension seed needs an attended client");
            check(dispatcher.parse("seedatlas dimension-seed " + dimension, true).getContext().getCommand() == null,
                "A dimension seed requires a value");
        }
        check(dispatcher.parse("seedatlas dimension-seed overworld 1", true).getReader().canRead(),
            "The Overworld uses the base seed command");
        // Servers on another Minecraft release than the client.
        for (String command : new String[]{"seedatlas version", "seedatlas version 26.2", "seedatlas version 1.21.11",
                "seedatlas version auto"}) {
            var parsed = dispatcher.parse(command, true);
            check(!parsed.getReader().canRead() && parsed.getContext().getCommand() != null,
                "Expected executable version command: " + command);
            check(dispatcher.parse(command, false).getReader().canRead(),
                "Changing the version needs an attended client: " + command);
        }
        check(StringArgumentType.getString(dispatcher.parse("seedatlas version 1.21.11", true).getContext()
            .build("seedatlas version 1.21.11"), "version").equals("1.21.11"), "Dotted versions are one word");
        // Minecraft logs every ambiguity at startup; the tree must have none.
        var ambiguities = new ArrayList<String>();
        dispatcher.findAmbiguities((parent, child, sibling, inputs) ->
            ambiguities.add(child.getName() + " / " + sibling.getName() + " " + inputs));
        check(ambiguities.isEmpty(), "Ambiguous client commands: " + ambiguities);
        System.out.println("Shared client command registration tests passed");
    }

    private record Source(boolean attended) implements SeedAtlasCommandSource {
        @Override public Minecraft getClient() { throw new AssertionError("Parsing must not access Minecraft"); }
        @Override public void sendFeedback(Component message) { throw new AssertionError("Parsing must not send chat"); }
        @Override public void sendError(Component message) { throw new AssertionError("Parsing must not send chat"); }
    }
    private static void check(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
}
