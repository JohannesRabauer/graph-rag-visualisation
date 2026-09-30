package com.graphraglens.web.help;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift guards for the contextual help system. Plain file scans, no Spring
 * context and no browser: (1) every {@code data-help} topic referenced by the
 * page, the scripts or the articles has a topic file, and every topic file is
 * referenced; (2) every class an article names in {@code <code data-class>}
 * still exists under some module's {@code src/main}.
 */
class HelpRegistryGuardTest {

    private static final Pattern DATA_HELP_ATTRIBUTE = Pattern.compile("data-help=\\\\?[\"']([a-z0-9-]+)\\\\?[\"']");
    private static final Pattern DATASET_HELP = Pattern.compile("dataset\\.help\\s*=\\s*'([a-z0-9-]+)'");
    private static final Pattern DATA_CLASS = Pattern.compile("data-class=\"([A-Za-z0-9_]+)\"");

    private static Path moduleDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        return Files.isDirectory(cwd.resolve("src/main/resources/static/help")) ? cwd : cwd.resolve("graphrag-web");
    }

    private static Set<String> collect(Pattern pattern, Path file, Set<String> into) throws IOException {
        Matcher m = pattern.matcher(Files.readString(file));
        while (m.find()) {
            into.add(m.group(1));
        }
        return into;
    }

    private static Set<String> topicFiles() throws IOException {
        Set<String> topics = new TreeSet<>();
        try (Stream<Path> files = Files.list(moduleDir().resolve("src/main/resources/static/help"))) {
            files.filter(p -> p.getFileName().toString().endsWith(".html")).forEach(p -> {
                String name = p.getFileName().toString();
                topics.add(name.substring(0, name.length() - ".html".length()));
            });
        }
        return topics;
    }

    private static Set<String> referencedTopics() throws IOException {
        Path resources = moduleDir().resolve("src/main/resources");
        Set<String> referenced = new TreeSet<>();
        collect(DATA_HELP_ATTRIBUTE, resources.resolve("templates/index.html"), referenced);
        try (Stream<Path> scripts = Files.list(resources.resolve("static/js"))) {
            for (Path js : (Iterable<Path>) scripts.filter(p -> p.toString().endsWith(".js"))::iterator) {
                collect(DATA_HELP_ATTRIBUTE, js, referenced);
                collect(DATASET_HELP, js, referenced);
            }
        }
        try (Stream<Path> articles = Files.list(resources.resolve("static/help"))) {
            for (Path html : (Iterable<Path>) articles.filter(p -> p.toString().endsWith(".html"))::iterator) {
                collect(DATA_HELP_ATTRIBUTE, html, referenced);
            }
        }
        return referenced;
    }

    @Test
    void everyReferencedTopicHasAFileAndEveryFileIsReferenced() throws IOException {
        Set<String> files = topicFiles();
        Set<String> referenced = referencedTopics();

        Set<String> missingFiles = new TreeSet<>(referenced);
        missingFiles.removeAll(files);
        Set<String> orphanFiles = new TreeSet<>(files);
        orphanFiles.removeAll(referenced);

        assertThat(files).as("topic files").hasSize(17);
        assertThat(missingFiles).as("data-help values without a topic file").isEmpty();
        assertThat(orphanFiles).as("topic files nobody references").isEmpty();
    }

    @Test
    void everyClassNamedInAnArticleStillExists() throws IOException {
        Path repoRoot = moduleDir().getParent();
        Set<String> existing = new TreeSet<>();
        try (Stream<Path> dirs = Files.list(repoRoot)) {
            for (Path module : (Iterable<Path>) dirs.filter(p -> Files.isDirectory(p.resolve("src/main/java")))::iterator) {
                try (Stream<Path> sources = Files.walk(module.resolve("src/main/java"))) {
                    sources.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                        String name = p.getFileName().toString();
                        existing.add(name.substring(0, name.length() - ".java".length()));
                    });
                }
            }
        }

        Set<String> cited = new TreeSet<>();
        try (Stream<Path> articles = Files.list(moduleDir().resolve("src/main/resources/static/help"))) {
            for (Path html : (Iterable<Path>) articles.filter(p -> p.toString().endsWith(".html"))::iterator) {
                collect(DATA_CLASS, html, cited);
            }
        }

        assertThat(cited).as("classes cited by help articles").isNotEmpty();
        Set<String> gone = new TreeSet<>(cited);
        gone.removeAll(existing);
        assertThat(gone).as("cited classes that no longer exist under any src/main").isEmpty();
    }
}
