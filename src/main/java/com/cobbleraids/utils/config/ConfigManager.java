package com.cobbleraids.utils.config;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the lifecycle of a configuration file, including loading, saving,
 * automatic backups, and live-reloading from disk. It handles JSON format with comments in the file header/footer.
 *
 * @param <T> The class representing the structure of the configuration data.
 */
public class ConfigManager<T extends ConfigData> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigManager.class);
    private final String currentVersion;
    private final T defaultConfig;
    private final Class<T> configClass;
    private final Path configFile;
    private final Path backupDir;
    private final ConfigMetadata metadata;
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();
    private final AtomicReference<T> configData;
    private final AtomicInteger lastSavedHash;
    private final AtomicBoolean hasUnsavedChanges = new AtomicBoolean(false);
    private final ScheduledExecutorService executor;
    private WatchService watchService;

    public ConfigManager(String currentVersion, T defaultConfig, Class<T> configClass, Path configDir, ConfigMetadata metadata) {
        this.currentVersion = currentVersion;
        this.defaultConfig = defaultConfig;
        this.configClass = configClass;
        this.metadata = metadata;
        this.configFile = configDir.resolve("config.jsonc");
        this.backupDir = configDir.resolve("backups");

        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ConfigManager-" + this.defaultConfig.getConfigId());
            t.setDaemon(true);
            return t;
        });

        this.configData = new AtomicReference<>(defaultConfig);
        this.lastSavedHash = new AtomicInteger(defaultConfig.hashCode());

        initialize();
    }


    private void initialize() {
        try {
            Files.createDirectories(configFile.getParent());
            Files.createDirectories(backupDir);

            if (Files.exists(configFile)) {
                loadConfig();
            } else {
                LOGGER.info("No config file found for '{}'. Creating a new one with default values.", defaultConfig.getConfigId());
                saveConfig(defaultConfig, true);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to initialize config directory for {}: {}", defaultConfig.getConfigId(), e.getMessage());
        }

        if (metadata.watcherSettings().enabled()) {
            setupWatcher();
        }
        if (metadata.watcherSettings().autoSaveEnabled()) {
            setupAutoSave();
        }
    }

    /**
     * The core logic for loading, migrating, and salvaging the configuration file.
     * @return true if the config was loaded or salvaged successfully, false if a critical error occurred.
     */
    private boolean loadConfig() {
        if (!Files.exists(configFile)) {
            LOGGER.warn("Attempted to load config for '{}', but file does not exist. Using defaults.", defaultConfig.getConfigId());
            return false;
        }

        String content;
        try {
            content = Files.readString(configFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Could not read config file '{}'. Restoring defaults.", configFile, e);
            createBackup("unreadable");
            this.configData.set(defaultConfig);
            saveConfig(defaultConfig, true);
            return false;
        }

        try {
            T loadedConfig = gson.fromJson(content, configClass);

            if (loadedConfig == null) {
                throw new JsonParseException("File is empty or contains only null values.");
            }

            if (!currentVersion.equals(loadedConfig.getVersion())) {
                LOGGER.warn("Config version mismatch for '{}'. Migrating from v{} to v{}.",
                        defaultConfig.getConfigId(), loadedConfig.getVersion(), currentVersion);
                createBackup("pre-migration");
                JsonObject oldConfigJson = gson.toJsonTree(loadedConfig).getAsJsonObject();
                T migratedConfig = merge(oldConfigJson);
                this.configData.set(migratedConfig);
                saveConfig(migratedConfig, true);
            } else {
                this.configData.set(loadedConfig);
                this.lastSavedHash.set(loadedConfig.hashCode());
                hasUnsavedChanges.set(false);
                LOGGER.info("Successfully loaded configuration for '{}'.", defaultConfig.getConfigId());
            }
            return true; // Success!

        } catch (JsonParseException e) {
            LOGGER.warn("Config file for '{}' is corrupt. Attempting to salvage settings... Reason: {}",
                    defaultConfig.getConfigId(), e.getMessage());
            createBackup("corrupted");

            T recoveredConfig = null;
            if (!content.isBlank()) {
                String cleanedContent = Stream.of(content.split("\n"))
                        .map(line -> line.trim().replaceAll("^,", "").trim())
                        .collect(Collectors.joining("\n"));

                try {
                    JsonReader reader = new JsonReader(new StringReader(cleanedContent));
                    reader.setLenient(true);
                    JsonElement parsedElement = JsonParser.parseReader(reader);

                    if (parsedElement.isJsonObject()) {
                        recoveredConfig = merge(parsedElement.getAsJsonObject());
                        LOGGER.info("Successfully salvaged settings for '{}' from corrupted config.", defaultConfig.getConfigId());
                    }
                } catch (Exception salvageException) {
                    LOGGER.warn("Salvage attempt for '{}' failed. The file's syntax may be too damaged. Reason: {}",
                            defaultConfig.getConfigId(), salvageException.getMessage());
                }
            }

            if (recoveredConfig != null) {
                this.configData.set(recoveredConfig);
                saveConfig(recoveredConfig, true);
                return true; // Salvage was successful!
            } else {
                LOGGER.error("Could not salvage configuration for '{}'. A new default config has been generated.", defaultConfig.getConfigId());
                this.configData.set(defaultConfig);
                saveConfig(defaultConfig, true);
                return false; // Salvage failed.
            }
        }
    }

    /**
     * Forces a reload of the configuration from the disk.
     * This is the public method that should be called by commands.
     * @return true if the reload was successful (or salvaged), false if it failed and reverted to defaults.
     */
    public boolean reload() {
        LOGGER.info("Manual reload triggered for '{}'. Loading configuration from disk...", defaultConfig.getConfigId());
        return this.loadConfig();
    }

    private T merge(JsonObject existingValues) {
        JsonObject defaultJson = gson.toJsonTree(defaultConfig).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : defaultJson.entrySet()) {
            String key = entry.getKey();
            if (existingValues.has(key) && !existingValues.get(key).isJsonNull()) {
                defaultJson.add(key, existingValues.get(key));
            }
        }
        return gson.fromJson(defaultJson, configClass);
    }
    public T getConfig() { return configData.get(); }
    public synchronized void updateConfig(T newConfig) {
        this.configData.set(newConfig);
        if (newConfig.hashCode() != lastSavedHash.get()) {
            hasUnsavedChanges.set(true);
            if (!metadata.watcherSettings().autoSaveEnabled()) {
                save();
            }
        }
    }
    public void save() { executor.submit(() -> saveConfig(configData.get(), false)); }
    private void saveConfig(T config, boolean force) {
        if (!force && !hasUnsavedChanges.getAndSet(false)) { return; }
        try {
            JsonObject configJson = gson.toJsonTree(config).getAsJsonObject();
            if (metadata.includeVersion()) { configJson.addProperty("version", currentVersion); }
            if (metadata.includeTimestamp()) { configJson.addProperty("last-updated", LocalDateTime.now().toString()); }
            String content = buildFileContent(gson.toJson(configJson));
            Files.writeString(configFile, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            lastSavedHash.set(config.hashCode());
            hasUnsavedChanges.set(false);
            LOGGER.info("Configuration for '{}' saved successfully.", defaultConfig.getConfigId());
        } catch (IOException e) {
            LOGGER.error("Failed to save config file '{}': {}", configFile, e.getMessage());
            hasUnsavedChanges.set(true);
        }
    }
    private String buildFileContent(String jsonContent) {
        StringBuilder sb = new StringBuilder();
        if (metadata.headerComments() != null && !metadata.headerComments().isEmpty()) {
            sb.append("/*\n");
            metadata.headerComments().forEach(line -> sb.append(" * ").append(line).append("\n"));
            sb.append(" */\n\n");
        }
        sb.append(jsonContent);
        if (metadata.footerComments() != null && !metadata.footerComments().isEmpty()) {
            sb.append("\n\n/*\n");
            metadata.footerComments().forEach(line -> sb.append(" * ").append(line).append("\n"));
            sb.append(" */");
        }
        return sb.toString();
    }
    private void createBackup(String reason) {
        if (!Files.exists(configFile)) return;
        try {
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
            Path backupFile = backupDir.resolve(String.format("%s_%s_%s.jsonc.bak", defaultConfig.getConfigId(), reason, timestamp));
            Files.copy(configFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Created backup for '{}' at: {}", defaultConfig.getConfigId(), backupFile);
            try (Stream<Path> stream = Files.list(backupDir)) {
                List<Path> backups = stream.filter(p -> p.toString().endsWith(".jsonc.bak"))
                        .sorted(Comparator.comparing(p -> {
                            try { return Files.readAttributes(p, BasicFileAttributes.class).creationTime(); } catch (IOException e) { return null; }
                        }, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
                if (backups.size() > 20) {
                    for (int i = 20; i < backups.size(); i++) { Files.delete(backups.get(i)); }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create backup for '{}': {}", defaultConfig.getConfigId(), e.getMessage());
        }
    }
    private void setupWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            configFile.getParent().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
            Thread watcherThread = new Thread(() -> {
                try {
                    WatchKey key;
                    while ((key = watchService.take()) != null) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (configFile.getFileName().equals(event.context())) {
                                executor.schedule(this::reload, metadata.watcherSettings().debounceMs(), TimeUnit.MILLISECONDS);
                            }
                        }
                        key.reset();
                    }
                } catch (InterruptedException | ClosedWatchServiceException e) {
                    LOGGER.info("Stopping config file watcher for '{}'.", defaultConfig.getConfigId());
                }
            }, "ConfigFileWatcher-" + defaultConfig.getConfigId());
            watcherThread.setDaemon(true);
            watcherThread.start();
        } catch (IOException e) {
            LOGGER.error("Could not set up file watcher for '{}': {}", defaultConfig.getConfigId(), e.getMessage());
        }
    }
    private void setupAutoSave() {
        executor.scheduleAtFixedRate(this::save,
                metadata.watcherSettings().autoSaveIntervalMs(),
                metadata.watcherSettings().autoSaveIntervalMs(),
                TimeUnit.MILLISECONDS);
    }
    public void shutdown() {
        try {
            if (watchService != null) { watchService.close(); }
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) { executor.shutdownNow(); }
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error during ConfigManager shutdown: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}