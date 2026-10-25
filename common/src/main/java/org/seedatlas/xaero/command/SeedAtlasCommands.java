package org.seedatlas.xaero.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.function.Function;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import org.seedatlas.xaero.SeedAtlasXaero;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.config.SeedAtlasConfig;
import org.seedatlas.xaero.nativeapi.Dimension;
import org.seedatlas.xaero.nativeapi.GameVersion;
import org.seedatlas.xaero.nativeapi.NativeStatus;
import org.seedatlas.xaero.nativeapi.SeedAtlasNative;

/** Registers the entirely client-side {@code /seedatlas} command tree. */
public final class SeedAtlasCommands {
	private static final String AUTO_VERSION = "auto";

	private SeedAtlasCommands() {
	}

	public static <S> void register(CommandDispatcher<S> dispatcher, Function<S, SeedAtlasCommandSource> source) {
		dispatcher.register(LiteralArgumentBuilder.<S>literal("seedatlas")
			.executes(context -> status(source.apply(context.getSource())))
			.then(LiteralArgumentBuilder.<S>literal("seed")
				.requires(value -> source.apply(value).attended())
				.then(RequiredArgumentBuilder.<S, String>argument("seed", StringArgumentType.greedyString())
					.executes(context -> setSeed(source.apply(context.getSource()), StringArgumentType.getString(context, "seed")))))
			.then(LiteralArgumentBuilder.<S>literal("dimension-seed")
				.requires(value -> source.apply(value).attended())
				.then(dimensionSeed("nether", Dimension.NETHER, source))
				.then(dimensionSeed("end", Dimension.END, source)))
			.then(LiteralArgumentBuilder.<S>literal("clear")
				.requires(value -> source.apply(value).attended())
				.executes(context -> clearSeed(source.apply(context.getSource())))
				.then(LiteralArgumentBuilder.<S>literal("nether")
					.executes(context -> clearDimensionSeed(source.apply(context.getSource()), Dimension.NETHER)))
				.then(LiteralArgumentBuilder.<S>literal("end")
					.executes(context -> clearDimensionSeed(source.apply(context.getSource()), Dimension.END))))
			.then(LiteralArgumentBuilder.<S>literal("version")
				.requires(value -> source.apply(value).attended())
				.executes(context -> showGameVersion(source.apply(context.getSource())))
				.then(RequiredArgumentBuilder.<S, String>argument("version", StringArgumentType.word())
					.suggests((context, builder) -> {
						builder.suggest(AUTO_VERSION);
						GameVersion.ALL.forEach(version -> builder.suggest(version.name()));
						return builder.buildFuture();
					})
					.executes(context -> setGameVersion(source.apply(context.getSource()),
						StringArgumentType.getString(context, "version")))))
			.then(LiteralArgumentBuilder.<S>literal("status")
				.executes(context -> status(source.apply(context.getSource()))))
			.then(LiteralArgumentBuilder.<S>literal("settings")
				.requires(value -> source.apply(value).attended())
				.executes(context -> openSettings(source.apply(context.getSource()))))
		);
	}

	/**
	 * {@code dimension-seed <name> <seed>} gives one dimension its own seed; {@code clear <name>}
	 * removes it. Clearing is not a child here, where it would be ambiguous with a text seed.
	 */
	private static <S> LiteralArgumentBuilder<S> dimensionSeed(
		final String name, final Dimension dimension, final Function<S, SeedAtlasCommandSource> source
	) {
		return LiteralArgumentBuilder.<S>literal(name)
			.then(RequiredArgumentBuilder.<S, String>argument("seed", StringArgumentType.greedyString())
				.executes(context -> setDimensionSeed(source.apply(context.getSource()), dimension,
					StringArgumentType.getString(context, "seed"))));
	}

	private static int setDimensionSeed(final SeedAtlasCommandSource source, final Dimension dimension, final String input) {
		try {
			long seed = SeedAtlasClientState.setDimensionSeed(dimension, input);
			source.sendFeedback(Component.translatable(
				"command.seedatlas_xaero.dimension_seed_set",
				dimensionName(dimension),
				input.trim(),
				seed,
				SeedAtlasClientState.currentContextLabel()
			));
			return 1;
		} catch (IllegalArgumentException exception) {
			source.sendError(Component.translatable("command.seedatlas_xaero.seed_blank"));
			return 0;
		} catch (IllegalStateException exception) {
			source.sendError(Component.translatable("command.seedatlas_xaero.no_context"));
			return 0;
		}
	}

	private static int clearDimensionSeed(final SeedAtlasCommandSource source, final Dimension dimension) {
		SeedAtlasClientState.refreshContext(source.getClient());
		if (SeedAtlasClientState.currentContextKey().isEmpty()) {
			source.sendError(Component.translatable("command.seedatlas_xaero.no_context"));
			return 0;
		}
		if (SeedAtlasClientState.clearDimensionSeed(dimension)) {
			source.sendFeedback(Component.translatable("command.seedatlas_xaero.dimension_seed_cleared",
				dimensionName(dimension)));
			return 1;
		}
		source.sendFeedback(Component.translatable("command.seedatlas_xaero.no_dimension_seed", dimensionName(dimension)));
		return 0;
	}

	public static Component dimensionName(final Dimension dimension) {
		return Component.translatable("dimension.seedatlas_xaero." + dimension.name().toLowerCase(Locale.ROOT));
	}

	private static int setSeed(final SeedAtlasCommandSource source, String input) {
		try {
			long seed = SeedAtlasClientState.setSeed(input);
			source.sendFeedback(Component.translatable(
				"command.seedatlas_xaero.seed_set",
				input.trim(),
				seed,
				SeedAtlasClientState.currentContextLabel()
			));
			return 1;
		} catch (IllegalArgumentException exception) {
			source.sendError(Component.translatable("command.seedatlas_xaero.seed_blank"));
			return 0;
		} catch (IllegalStateException exception) {
			source.sendError(Component.translatable("command.seedatlas_xaero.no_context"));
			return 0;
		}
	}

	/** {@code version <name>} for servers on another release than the client; {@code auto} follows the client. */
	private static int setGameVersion(final SeedAtlasCommandSource source, final String input) {
		GameVersion version = null;
		if (!AUTO_VERSION.equalsIgnoreCase(input.trim())) {
			version = GameVersion.parse(input).orElse(null);
			if (version == null) {
				source.sendError(Component.translatable("command.seedatlas_xaero.game_version_unknown", input.trim(),
					GameVersion.ALL.getLast().first(), GameVersion.NEWEST.name()));
				return 0;
			}
			if (!SeedAtlasNative.supports(version)) {
				source.sendError(Component.translatable("command.seedatlas_xaero.game_version_unsupported", version.name()));
				return 0;
			}
		}
		try {
			SeedAtlasClientState.setGameVersion(version);
		} catch (IllegalStateException exception) {
			source.sendError(Component.translatable("command.seedatlas_xaero.no_context"));
			return 0;
		}
		return showGameVersion(source);
	}

	private static int showGameVersion(final SeedAtlasCommandSource source) {
		SeedAtlasClientState.refreshContext(source.getClient());
		if (SeedAtlasClientState.currentContextKey().isEmpty()) {
			source.sendError(Component.translatable("command.seedatlas_xaero.no_context"));
			return 0;
		}
		source.sendFeedback(Component.translatable("command.seedatlas_xaero.status.game_version", gameVersionName()));
		return 1;
	}

	/** "1.21.5 – 26.1.2", or "Auto (26.3)" while the context follows the running game. */
	public static Component gameVersionName() {
		GameVersion version = SeedAtlasClientState.activeGameVersion();
		return SeedAtlasClientState.gameVersionAutomatic()
			? Component.translatable("options.seedatlas_xaero.game_version.auto", version.name())
			: Component.literal(version.label());
	}

	private static int clearSeed(final SeedAtlasCommandSource source) {
		SeedAtlasClientState.refreshContext(source.getClient());
		if (SeedAtlasClientState.currentContextKey().isEmpty()) {
			source.sendError(Component.translatable("command.seedatlas_xaero.no_context"));
			return 0;
		}
		if (SeedAtlasClientState.clearSeed()) {
			source.sendFeedback(Component.translatable("command.seedatlas_xaero.seed_cleared"));
			return 1;
		}
		source.sendFeedback(Component.translatable("command.seedatlas_xaero.no_seed_to_clear"));
		return 0;
	}

	private static int status(final SeedAtlasCommandSource source) {
		SeedAtlasClientState.refreshContext(source.getClient());
		String contextLabel = SeedAtlasClientState.currentContextLabel();
		if (SeedAtlasClientState.currentContextKey().isEmpty()) {
			source.sendError(Component.translatable("command.seedatlas_xaero.no_context"));
			return 0;
		}

		if (!SeedAtlasXaero.xaeroCompatible()) {
			source.sendError(SeedAtlasXaero.incompatibleMessage());
		}
		source.sendFeedback(Component.translatable("command.seedatlas_xaero.status.context", contextLabel));
		SeedAtlasConfig.SeedProfile profile = SeedAtlasClientState.activeProfile().orElse(null);
		if (profile == null) {
			source.sendFeedback(Component.translatable("command.seedatlas_xaero.status.no_seed"));
		} else {
			if (profile.base() != null) {
				source.sendFeedback(Component.translatable("command.seedatlas_xaero.status.seed",
					profile.base().input(), profile.base().seed()));
			} else {
				source.sendFeedback(Component.translatable("command.seedatlas_xaero.status.no_base_seed"));
			}
			for (Dimension dimension : new Dimension[]{Dimension.NETHER, Dimension.END}) {
				SeedAtlasConfig.SeedValue own = profile.ownSeed(dimension);
				if (own != null) {
					source.sendFeedback(Component.translatable("command.seedatlas_xaero.status.dimension_seed",
						dimensionName(dimension), own.input(), own.seed()));
				}
			}
		}

		SeedAtlasConfig config = SeedAtlasClientState.config();
		source.sendFeedback(Component.translatable(
			"command.seedatlas_xaero.status.layer",
			onOff(config.layerEnabled()),
			Math.round(config.opacityFraction() * 100.0F)
		));
		source.sendFeedback(Component.translatable(
			"command.seedatlas_xaero.status.world_type",
			Component.translatable(
				config.largeBiomes()
					? "options.seedatlas_xaero.world_type.large"
					: "options.seedatlas_xaero.world_type.normal"
			)
		));
		source.sendFeedback(Component.translatable("command.seedatlas_xaero.status.game_version", gameVersionName()));
		source.sendFeedback(Component.translatable(
			"command.seedatlas_xaero.status.structures",
			onOff(config.structuresEnabled())
		));
		Component workerThreads = config.workerThreads() == 0
			? Component.translatable("options.seedatlas_xaero.worker_threads.auto")
			: Component.literal(Integer.toString(config.workerThreads()));
		source.sendFeedback(Component.translatable(
			"command.seedatlas_xaero.status.performance",
			Component.translatable("options.seedatlas_xaero.biome_resolution." + config.biomeResolution()),
			workerThreads,
			Component.translatable("options.seedatlas_xaero.prefetch_radius." + config.prefetchRadius())
		));
		NativeStatus nativeStatus = SeedAtlasNative.status();
		if (nativeStatus.available()) {
			source.sendFeedback(Component.translatable(
				"command.seedatlas_xaero.status.native",
				nativeStatus.engineVersion(),
				nativeStatus.platform()
			));
		} else {
			source.sendError(Component.translatable(
				"command.seedatlas_xaero.status.native_unavailable",
				nativeStatus.message()
			));
		}
		return 1;
	}

	private static Component onOff(final boolean enabled) {
		return Component.translatable(enabled ? "options.seedatlas_xaero.on" : "options.seedatlas_xaero.off");
	}

	private static int openSettings(final SeedAtlasCommandSource source) {
		// ChatScreen closes after command execution, so queue this for the next client task.
		source.getClient().schedule(() -> SeedAtlasClientState.openSettings(null));
		return 1;
	}
}
