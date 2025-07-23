package com.cobbleraids.utils.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A manager to simplify command registration with Brigadier and provide
 * integrated, fallback-safe permission checking with LuckPerms or other permission mods.
 */
public class CommandManager {
    private final Logger logger;
    private final String modId;
    private final List<CommandBuilder> commands = new ArrayList<>();

    public CommandManager(String modId) {
        this.modId = modId;
        this.logger = LoggerFactory.getLogger(modId + "-Commands");
    }

    public CommandBuilder newCommand(String name) {
        CommandBuilder builder = new CommandBuilder(name);
        this.commands.add(builder);
        return builder;
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            logger.info("Registering {} commands for mod '{}'", commands.size(), modId);
            for (CommandBuilder command : commands) {
                command.register(dispatcher);
            }
        });
    }

    public static class CommandBuilder {
        private final String name;
        private final List<String> aliases = new ArrayList<>();
        private Predicate<ServerCommandSource> requirement = s -> true;
        private final List<ArgumentBuilder<ServerCommandSource, ?>> children = new ArrayList<>();
        private Consumer<CommandContext<ServerCommandSource>> executor = null;

        private CommandBuilder(String name) { this.name = name; }

        public CommandBuilder withAlias(String alias) {
            this.aliases.add(alias);
            return this;
        }

        public CommandBuilder requires(String permission, int defaultOpLevel) {
            this.requirement = source -> hasPermission(source, permission, defaultOpLevel);
            return this;
        }

        public CommandBuilder requires(Predicate<ServerCommandSource> requirement) {
            this.requirement = requirement;
            return this;
        }

        public void executes(Consumer<CommandContext<ServerCommandSource>> executor) {
            this.executor = executor;
        }

        public CommandBuilder then(ArgumentBuilder<ServerCommandSource, ?> argument) {
            this.children.add(argument);
            return this;
        }

        public CommandBuilder subCommand(String name, Consumer<CommandBuilder> subCommand) {
            CommandBuilder subBuilder = new CommandBuilder(name);
            subCommand.accept(subBuilder);
            this.children.add(subBuilder.build());
            return this; // Now returns itself for chaining
        }

        private LiteralArgumentBuilder<ServerCommandSource> build() {
            LiteralArgumentBuilder<ServerCommandSource> builder = net.minecraft.server.command.CommandManager.literal(name).requires(requirement);
            if (executor != null) {
                builder.executes(ctx -> {
                    executor.accept(ctx);
                    return 1;
                });
            }
            children.forEach(builder::then);
            return builder;
        }

        private void register(CommandDispatcher<ServerCommandSource> dispatcher) {
            LiteralCommandNode<ServerCommandSource> mainCommandNode = dispatcher.register(build());
            for (String alias : aliases) {
                dispatcher.register(net.minecraft.server.command.CommandManager.literal(alias)
                        .requires(mainCommandNode.getRequirement())
                        .redirect(mainCommandNode)
                );
            }
        }
    }

    public static LiteralArgumentBuilder<ServerCommandSource> literal(String name) {
        return net.minecraft.server.command.CommandManager.literal(name);
    }

    public static <T> RequiredArgumentBuilder<ServerCommandSource, T> argument(String name, ArgumentType<T> type) {
        return net.minecraft.server.command.CommandManager.argument(name, type);
    }

    public static boolean hasPermission(ServerCommandSource source, String permission, int fallbackOpLevel) {
        try {
            return Permissions.check(source, permission, fallbackOpLevel);
        } catch (NoClassDefFoundError e) {
            return source.hasPermissionLevel(fallbackOpLevel);
        }
    }

    public static void sendFeedback(ServerCommandSource source, String message, boolean broadcastToOps) {
        source.sendFeedback(() -> Text.literal(message), broadcastToOps);
    }

    public static void sendError(ServerCommandSource source, String message) {
        source.sendError(Text.literal(message));
    }
}