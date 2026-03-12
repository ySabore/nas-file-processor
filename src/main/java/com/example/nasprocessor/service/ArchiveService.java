package com.example.nasprocessor.service;

import com.example.nasprocessor.config.NasProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Archives old files from completed, error, and responses folders to manage disk space.
 * Recursively scans and preserves folder structure (e.g. servicer_A/file.json -> archive/.../completed/servicer_A/file.json).
 * Deletes empty folders from completed, error, and responses after archiving.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nas.archive.enabled", havingValue = "true")
public class ArchiveService {

    private final NasProperties nasProperties;
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * Archive files older than retentionDays from completed, error, and responses.
     * Preserves structure and deletes empty folders after archiving.
     */
    public ArchiveResult runArchive() {
        if (!nasProperties.getArchive().isEnabled()) {
            return ArchiveResult.skipped();
        }

        int retentionDays = nasProperties.getArchive().getRetentionDays();
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 24L * 3600);
        Path archiveBase = Path.of(nasProperties.getArchive().getFolder());
        String monthFolder = LocalDate.now().format(MONTH_FMT);

        int completedCount = 0;
        int errorCount = 0;
        int responsesCount = 0;

        try {
            Path completedDir = Path.of(nasProperties.getFolders().getCompleted());
            Path errorDir = Path.of(nasProperties.getFolders().getError());

            completedCount = archiveFolderRecursive(
                    completedDir,
                    archiveBase.resolve(monthFolder).resolve("completed"),
                    cutoff, false);
            deleteEmptyFolders(completedDir);

            errorCount = archiveFolderRecursive(
                    errorDir,
                    archiveBase.resolve(monthFolder).resolve("error"),
                    cutoff, true);
            deleteEmptyFolders(errorDir);

            String responsesPath = nasProperties.getFolders().getResponses();
            if (responsesPath != null && !responsesPath.isBlank()) {
                Path responsesDir = Path.of(responsesPath);
                responsesCount = archiveResponsesFolderRecursive(
                        responsesDir,
                        archiveBase.resolve(monthFolder).resolve("responses"),
                        cutoff);
                deleteEmptyFolders(responsesDir);
            }
        } catch (IOException e) {
            log.error("Archive failed: {}", e.getMessage(), e);
            return ArchiveResult.failed(e.getMessage());
        }

        int total = completedCount + errorCount + responsesCount;
        if (total > 0) {
            log.info("Archived {} items (completed: {}, error: {}, responses: {})",
                    total, completedCount, errorCount, responsesCount);
        }
        return ArchiveResult.success(completedCount, errorCount, responsesCount);
    }

    /**
     * Recursively archive files from sourceDir to targetDir, preserving folder structure.
     * For error folder, also moves .error.txt sidecar files with their .json files.
     */
    private int archiveFolderRecursive(Path sourceDir, Path targetDir, Instant cutoff, boolean moveErrorSidecars) throws IOException {
        if (!Files.exists(sourceDir)) return 0;
        int count = 0;
        List<Path> filesToArchive = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sourceDir)
                .filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().endsWith(".lock"))
                .filter(p -> !p.getFileName().toString().endsWith(".error.txt"))
                .sorted(Comparator.comparing(Path::toString))) {
            stream.forEach(filesToArchive::add);
        }
        for (Path file : filesToArchive) {
            if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                Path relativePath = sourceDir.relativize(file);
                Path target = targetDir.resolve(relativePath);
                Files.createDirectories(target.getParent());
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                count++;
                log.debug("Archived: {} -> {}", relativePath, target);
                if (moveErrorSidecars && file.getFileName().toString().endsWith(".json")) {
                    Path sidecar = file.getParent().resolve(file.getFileName().toString() + ".error.txt");
                    if (Files.exists(sidecar)) {
                        Path sidecarTarget = target.getParent().resolve(sidecar.getFileName().toString());
                        Files.move(sidecar, sidecarTarget, StandardCopyOption.REPLACE_EXISTING);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Recursively find response folders (contain batch_* or *.FINAL.json) and archive old ones, preserving structure.
     */
    private int archiveResponsesFolderRecursive(Path sourceDir, Path targetDir, Instant cutoff) throws IOException {
        if (!Files.exists(sourceDir)) return 0;
        List<Path> responseFoldersToArchive = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sourceDir)
                .filter(Files::isDirectory)
                .filter(p -> !p.equals(sourceDir))
                .sorted(Comparator.comparing(Path::toString).reversed())) {
            for (Path dir : stream.toList()) {
                if (isResponseFolder(dir) && Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff)) {
                    responseFoldersToArchive.add(dir);
                }
            }
        }
        int count = 0;
        for (Path responseFolder : responseFoldersToArchive) {
            Path relativePath = sourceDir.relativize(responseFolder);
            Path target = targetDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            moveRecursive(responseFolder, target);
            count++;
            log.debug("Archived responses folder: {} -> {}", relativePath, target);
        }
        return count;
    }

    private boolean isResponseFolder(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith("batch_") || name.endsWith(".FINAL.json")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void moveRecursive(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.isDirectory(source)) {
            Files.createDirectories(target);
            try (Stream<Path> stream = Files.list(source)) {
                for (Path child : stream.toList()) {
                    moveRecursive(source.resolve(child.getFileName()), target.resolve(child.getFileName()));
                }
            }
            Files.delete(source);
        } else {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /**
     * Delete empty subdirectories from baseDir. Ignores .DS_Store and *.lock when checking emptiness.
     */
    private void deleteEmptyFolders(Path baseDir) throws IOException {
        if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) return;
        List<Path> dirsToCheck = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(baseDir)
                .filter(Files::isDirectory)
                .filter(p -> !p.equals(baseDir))
                .sorted(Comparator.comparing(Path::toString).reversed())) {
            stream.forEach(dirsToCheck::add);
        }
        for (Path dir : dirsToCheck) {
            if (Files.exists(dir) && isEmptyExceptIgnored(dir)) {
                Files.delete(dir);
                log.debug("Deleted empty archived folder: {}", dir);
            }
        }
    }

    private boolean isEmptyExceptIgnored(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path entry : stream.toList()) {
                String name = entry.getFileName().toString();
                if (!name.equals(".DS_Store") && !name.endsWith(".lock")) {
                    return false;
                }
            }
        }
        return true;
    }

    @lombok.Data
    @lombok.Builder
    public static class ArchiveResult {
        private boolean ran;
        private boolean success;
        private String errorMessage;
        private int completedCount;
        private int errorCount;
        private int responsesCount;

        public static ArchiveResult skipped() {
            return ArchiveResult.builder().ran(false).success(true).build();
        }

        public static ArchiveResult success(int completed, int error, int responses) {
            return ArchiveResult.builder()
                    .ran(true).success(true)
                    .completedCount(completed).errorCount(error).responsesCount(responses)
                    .build();
        }

        public static ArchiveResult failed(String message) {
            return ArchiveResult.builder().ran(true).success(false).errorMessage(message).build();
        }
    }
}
