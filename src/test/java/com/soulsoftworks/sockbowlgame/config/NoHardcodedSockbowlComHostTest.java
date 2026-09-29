package com.soulsoftworks.sockbowlgame.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H1 (plan m7-deploy §2, §3.4, WP-G1): sockbowl.com is a parked domain owned
 * by someone else, so a default that still points there is a live footgun
 * (and, for the old Keycloak client redirect URIs, an auth-code exfiltration
 * path - see docker's H12/H13). No {@code application*.properties} may carry
 * it as a literal default; every host is env-driven, defaulting to
 * {@code localhost}.
 */
class NoHardcodedSockbowlComHostTest {

    @Test
    void noApplicationPropertiesFileHardcodesTheSockbowlComHost() throws IOException {
        List<Path> propertiesFiles = new ArrayList<>();
        try (DirectoryStream<Path> mainResources = Files.newDirectoryStream(
                Path.of("src/main/resources"), "application*.properties")) {
            mainResources.forEach(propertiesFiles::add);
        }
        try (DirectoryStream<Path> testResources = Files.newDirectoryStream(
                Path.of("src/test/resources"), "application*.properties")) {
            testResources.forEach(propertiesFiles::add);
        }

        // Fails loudly, rather than silently passing, if the glob above ever
        // stops matching anything (e.g. the resources move).
        assertThat(propertiesFiles).hasSizeGreaterThanOrEqualTo(2);

        for (Path file : propertiesFiles) {
            List<String> offendingLines = Files.readAllLines(file).stream()
                    .filter(line -> line.contains("sockbowl.com"))
                    .toList();
            assertThat(offendingLines)
                    .as("%s must not hardcode the parked sockbowl.com host", file)
                    .isEmpty();
        }
    }
}
