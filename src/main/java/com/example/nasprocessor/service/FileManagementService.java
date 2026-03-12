package com.example.nasprocessor.service;

import com.example.nasprocessor.config.NasProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileManagementService {

    private final NasProperties nasProperties;
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * Scan the inbound folder for JSON files that are not locked/in-progress.
     * When scanRecursive is true, scans subfolders and preserves structure in completed/error.
     */
    public List<Path> getInboundFiles() {
        List<Path> files = new ArrayList<>();
        Path inboundDir = Path.of(nasProperties.getFolders().getInbound());

        if (!Files.exists(inboundDir)) {
            log.warn("Inbound directory does not exist: {}", inboundDir);
            return files;
        }

        try {
            if (nasProperties.isScanRecursive()) {
                try (Stream<Path> stream = Files.walk(inboundDir)
                        .filter(p -> Files.isRegularFile(p))
                        .filter(p -> p.getFileName().toString().endsWith(nasProperties.getFileExtension()))
                        .filter(p -> !isLocked(p))
                        .sorted(Comparator.comparing(Path::toString))) {
                    stream.forEach(p -> {
                        files.add(p);
                        log.debug("Found inbound file: {}", inboundDir.relativize(p));
                    });
                }
            } else {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                        inboundDir, "*" + nasProperties.getFileExtension())) {
                    for (Path entry : stream) {
                        if (Files.isRegularFile(entry) && !isLocked(entry)) {
                            files.add(entry);
                            log.debug("Found inbound file: {}", entry.getFileName());
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error scanning inbound directory: {}", inboundDir, e);
        }

        log.info("Found {} file(s) in inbound folder", files.size());
        return files;
    }

    /**
     * Copy a file from inbound to the processing folder. The original stays in inbound.
     * Creates a lock file on the inbound file to prevent duplicate processing.
     * When scanRecursive is true, preserves relative path structure (e.g. folder_a/file.json).
     * Returns the path of the copy in the processing folder.
     */
    public Path copyToProcessing(Path inboundFile) throws IOException {
        Path inboundDir = Path.of(nasProperties.getFolders().getInbound());
        Path processingDir = Path.of(nasProperties.getFolders().getProcessing());

        Path relativePath = inboundDir.relativize(inboundFile);
        Path target = processingDir.resolve(relativePath);

        // Handle name collision by appending timestamp
        if (Files.exists(target)) {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            String newName = addTimestampToFileName(relativePath.getFileName().toString(), timestamp);
            target = target.getParent().resolve(newName);
        }

        ensureDirectoryExists(target.getParent());
        Files.copy(inboundFile, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("Copied {} -> processing folder (original preserved in inbound)", relativePath);

        if (nasProperties.isUseLockFile()) {
            createLockFile(inboundFile);
        }

        return target;
    }

    /**
     * Move the original file from inbound to the completed folder (exact file, unchanged).
     * Preserves relative path structure when scanRecursive is true (e.g. folder_a/file.json).
     * Deletes the processing copy. Removes the lock from the inbound file.
     */
    public void moveInboundToCompleted(Path inboundFile, Path processingFile, String originalFileName) throws IOException {
        Path inboundDir = Path.of(nasProperties.getFolders().getInbound());
        Path completedDir = Path.of(nasProperties.getFolders().getCompleted());

        Path relativePath = inboundDir.relativize(inboundFile);
        Path target = completedDir.resolve(relativePath);

        if (Files.exists(target)) {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            String completedName = addTimestampToFileName(relativePath.getFileName().toString(), timestamp);
            target = target.getParent().resolve(completedName);
            log.info("Moved original {} -> completed as {} (timestamp added to avoid overwrite)",
                    relativePath, completedName);
        } else {
            log.info("Moved original {} -> completed as {} (exact file from inbound)",
                    relativePath, relativePath);
        }

        ensureDirectoryExists(target.getParent());
        Files.move(inboundFile, target, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(processingFile);
        deleteLockFile(inboundFile);
        deleteEmptyParentDirectories(relativePath, inboundDir, "inbound");
        deleteEmptyParentDirectories(relativePath, Path.of(nasProperties.getFolders().getProcessing()), "processing");
    }

    /**
     * Move original inbound file to the error folder on failure.
     * Preserves relative path structure when scanRecursive is true (e.g. folder_a/file.json).
     * If processingFile is different from inboundFile, deletes the processing copy.
     */
    public void moveToError(Path inboundFile, Path processingFile, String reason) {
        try {
            Path inboundDir = Path.of(nasProperties.getFolders().getInbound());
            Path errorDir = Path.of(nasProperties.getFolders().getError());

            Path relativePath = inboundDir.relativize(inboundFile);
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            String errorFileName = addTimestampToFileName(relativePath.getFileName().toString(), timestamp);

            // Preserve structure: errorDir/folder_a/file_timestamp.json
            Path errorTarget = relativePath.getParent() != null && !relativePath.getParent().toString().isEmpty()
                    ? errorDir.resolve(relativePath.getParent()).resolve(errorFileName)
                    : errorDir.resolve(errorFileName);

            ensureDirectoryExists(errorTarget.getParent());

            if (Files.exists(inboundFile)) {
                Files.move(inboundFile, errorTarget, StandardCopyOption.ATOMIC_MOVE);
                deleteLockFile(inboundFile);
            }

            // Delete processing copy if it exists and is different from inbound
            if (processingFile != null && !processingFile.equals(inboundFile) && Files.exists(processingFile)) {
                Files.delete(processingFile);
            }

            // Write an error reason sidecar file next to the moved file
            Path errorLog = errorTarget.getParent().resolve(errorFileName + ".error.txt");
            Files.writeString(errorLog,
                    "Timestamp: " + LocalDateTime.now() + "\n" +
                    "File: " + relativePath + "\n" +
                    "Reason: " + reason);

            log.warn("Moved {} -> error folder. Reason: {}", relativePath, reason);
            deleteEmptyParentDirectories(relativePath, inboundDir, "inbound");
            deleteEmptyParentDirectories(relativePath, Path.of(nasProperties.getFolders().getProcessing()), "processing");
        } catch (IOException e) {
            log.error("CRITICAL: Failed to move {} to error folder!", inboundFile.getFileName(), e);
        }
    }

    /**
     * Delete empty parent directories in baseDir after a file is moved/deleted.
     * Uses relativePath (e.g. servicer_A/file.json) to resolve dirs from base root.
     * Ignores .DS_Store and *.lock when checking if directory is empty.
     */
    private void deleteEmptyParentDirectories(Path relativePath, Path baseDir, String logContext) {
        if (relativePath == null || relativePath.getParent() == null
                || relativePath.getParent().toString().isEmpty()) {
            return;
        }
        Path dirToDelete = baseDir.resolve(relativePath.getParent()).normalize();
        try {
            if (Files.exists(dirToDelete) && Files.isDirectory(dirToDelete)) {
                boolean isEmpty = true;
                try (var stream = Files.list(dirToDelete)) {
                    for (Path entry : stream.toList()) {
                        String name = entry.getFileName().toString();
                        if (!name.equals(".DS_Store") && !name.endsWith(".lock")) {
                            isEmpty = false;
                            break;
                        }
                    }
                }
                if (isEmpty) {
                    Files.delete(dirToDelete);
                    log.info("Deleted empty {} directory: {}", logContext, dirToDelete);
                    deleteEmptyParentDirectories(relativePath.getParent(), baseDir, logContext);
                }
            }
        } catch (IOException e) {
            log.debug("Could not delete directory {}: {}", dirToDelete, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Lock file helpers
    // -------------------------------------------------------------------------

    private void createLockFile(Path file) throws IOException {
        Path lockFile = getLockFilePath(file);
        Files.writeString(lockFile, "locked by nas-processor at " + LocalDateTime.now());
        log.debug("Created lock file: {}", lockFile.getFileName());
    }

    private void deleteLockFile(Path file) {
        try {
            Path lockFile = getLockFilePath(file);
            Files.deleteIfExists(lockFile);
            log.debug("Deleted lock file: {}", lockFile.getFileName());
        } catch (IOException e) {
            log.warn("Could not delete lock file for {}", file.getFileName(), e);
        }
    }

    private boolean isLocked(Path file) {
        if (!nasProperties.isUseLockFile()) return false;
        return Files.exists(getLockFilePath(file));
    }

    private Path getLockFilePath(Path file) {
        return file.getParent().resolve(file.getFileName() + ".lock");
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private void ensureDirectoryExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("Created directory: {}", dir);
        }
    }

    private String addTimestampToFileName(String fileName, String timestamp) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1) {
            return fileName + "_" + timestamp;
        }
        return fileName.substring(0, dotIndex) + "_" + timestamp + fileName.substring(dotIndex);
    }
}
