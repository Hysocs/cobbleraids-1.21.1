package com.cobbleraids.utils.config;

import java.util.List;
import java.util.Map;

/**
 * Defines metadata for the configuration file, such as header/footer comments.
 */
public record ConfigMetadata(
        List<String> headerComments,
        List<String> footerComments,
        Map<String, String> sectionComments,
        boolean includeTimestamp,
        boolean includeVersion,
        WatcherSettings watcherSettings
) {
    public static ConfigMetadata defaultFor(String configId) {
        return new ConfigMetadata(
                List.of(
                        "Configuration file for " + configId,
                        "This file is automatically managed. Comments outside of value fields will not be preserved."
                ),
                List.of(),
                Map.of(),
                true,
                true,
                new WatcherSettings()
        );
    }
}
