package dev.sculptory.paper.devtest;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.sculptory.paper.DevExtras;
import dev.sculptory.paper.engine.PaperEditHost;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;

/** The dev jar's {@link DevExtras}: the self-test, the frame-size probe and the region benchmark. */
public final class SelfTestExtras implements DevExtras {
    @Override
    public void started(PaperEditHost host) {
        SelfTest.runAtStartup(host);
    }

    /**
     * The adapter checks, the frame-size probe and the region benchmark exist only when the JVM was started for them
     * ({@link SelfTest#enabled}), and then only for the console or a holder of {@code sculptory.selftest} (not ops).
     */
    @Override
    public void commands(LiteralArgumentBuilder<CommandSourceStack> root, Supplier<PaperEditHost> host) {
        if (!SelfTest.enabled()) return;
        root.then(literal("selftest").requires(SelfTest::allowed)
                        .executes(c -> SelfTest.run(host.get(), c.getSource(), ""))
                        .then(argument("player", EntityArgument.player())
                                .executes(c -> SelfTest.run(host.get(), c.getSource(),
                                        EntityArgument.getPlayer(c, "player").getGameProfile().getName()))))
                .then(literal("wgbench").requires(SelfTest::allowed)
                        .executes(c -> RegionBench.run(host.get(), c.getSource(), 48))
                        .then(argument("height", IntegerArgumentType.integer(1, 384))
                                .executes(c -> RegionBench.run(host.get(), c.getSource(),
                                        IntegerArgumentType.getInteger(c, "height")))))
                .then(literal("nettest").requires(SelfTest::allowed)
                        .then(argument("player", EntityArgument.player())
                                .then(argument("bytes", IntegerArgumentType.integer(1, 4 * 1024 * 1024))
                                        .then(argument("route", StringArgumentType.word())
                                                .executes(c -> NetTest.send(c.getSource(),
                                                        EntityArgument.getPlayer(c, "player"),
                                                        IntegerArgumentType.getInteger(c, "bytes"),
                                                        StringArgumentType.getString(c, "route")))))));
    }

    @Override
    public void disabling() {
        SelfTest.pluginDisabling();
    }
}
