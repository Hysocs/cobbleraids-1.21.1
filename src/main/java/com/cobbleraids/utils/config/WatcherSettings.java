package com.cobbleraids.utils.config;

/**
 * Configuration for the file watcher and auto-save behavior.
 * Using a record for an immutable data carrier.
 */
public record WatcherSettings(
        boolean enabled,
        long debounceMs,
        boolean autoSaveEnabled,
        long autoSaveIntervalMs
) {
    // Default constructor for default settings
    public WatcherSettings() {
        this(false, 1000L, false, 30_000L);
    }
}