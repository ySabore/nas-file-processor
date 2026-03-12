package com.example.nasprocessor.service;

import com.example.nasprocessor.config.NasProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveServiceTest {

    @TempDir
    Path tempDir;

    private ArchiveService archiveService;
    private Path completedDir;
    private Path errorDir;
    private Path responsesDir;
    private Path archiveDir;

    @BeforeEach
    void setUp() {
        completedDir = tempDir.resolve("completed");
        errorDir = tempDir.resolve("error");
        responsesDir = tempDir.resolve("responses");
        archiveDir = tempDir.resolve("archive");

        NasProperties nasProperties = new NasProperties();
        NasProperties.Folders folders = new NasProperties.Folders();
        folders.setCompleted(completedDir.toString());
        folders.setError(errorDir.toString());
        folders.setResponses(responsesDir.toString());
        nasProperties.setFolders(folders);

        NasProperties.Archive archive = new NasProperties.Archive();
        archive.setEnabled(true);
        archive.setRetentionDays(30);
        archive.setFolder(archiveDir.toString());
        nasProperties.setArchive(archive);

        archiveService = new ArchiveService(nasProperties);
    }

    @Test
    void runArchive_shouldMoveOldFilesFromCompletedToArchive() throws IOException {
        Files.createDirectories(completedDir);
        Path oldFile = completedDir.resolve("old_completed.json");
        Files.writeString(oldFile, "{}");
        setLastModified(oldFile, 35);

        Path recentFile = completedDir.resolve("recent.json");
        Files.writeString(recentFile, "{}");
        setLastModified(recentFile, 1);

        var result = archiveService.runArchive();

        assertThat(result.isRan()).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCompletedCount()).isEqualTo(1);

        String monthFolder = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        Path archived = archiveDir.resolve(monthFolder).resolve("completed").resolve("old_completed.json");
        assertThat(Files.exists(archived)).isTrue();
        assertThat(Files.exists(completedDir.resolve("recent.json"))).isTrue();
        assertThat(Files.exists(completedDir.resolve("old_completed.json"))).isFalse();
    }

    @Test
    void runArchive_shouldMoveOldErrorFilesAndSidecar() throws IOException {
        Files.createDirectories(errorDir);
        Path oldJson = errorDir.resolve("old_error.json");
        Path oldSidecar = errorDir.resolve("old_error.json.error.txt");
        Files.writeString(oldJson, "{}");
        Files.writeString(oldSidecar, "Read error: test");
        setLastModified(oldJson, 35);
        setLastModified(oldSidecar, 35);

        var result = archiveService.runArchive();

        assertThat(result.isRan()).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getErrorCount()).isEqualTo(2);

        String monthFolder = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        Path archiveErrorDir = archiveDir.resolve(monthFolder).resolve("error");
        assertThat(Files.exists(archiveErrorDir.resolve("old_error.json"))).isTrue();
        assertThat(Files.exists(archiveErrorDir.resolve("old_error.json.error.txt"))).isTrue();
        assertThat(Files.exists(errorDir.resolve("old_error.json"))).isFalse();
        assertThat(Files.exists(errorDir.resolve("old_error.json.error.txt"))).isFalse();
    }

    @Test
    void runArchive_shouldMoveOldResponseSubfolders() throws IOException {
        Files.createDirectories(responsesDir);
        Path oldSubfolder = responsesDir.resolve("file_20240101_120000");
        Files.createDirectories(oldSubfolder);
        Path batchDir = oldSubfolder.resolve("batch_001_of_001");
        Files.createDirectories(batchDir);
        Files.writeString(batchDir.resolve("request.json"), "{}");
        Files.writeString(batchDir.resolve("response.json"), "{}");
        setLastModified(oldSubfolder, 35);

        Path recentSubfolder = responsesDir.resolve("file_20250301_120000");
        Files.createDirectories(recentSubfolder);
        Files.writeString(recentSubfolder.resolve("file.FINAL.json"), "{}");
        setLastModified(recentSubfolder, 1);

        var result = archiveService.runArchive();

        assertThat(result.isRan()).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponsesCount()).isEqualTo(1);

        String monthFolder = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        Path archivedSubfolder = archiveDir.resolve(monthFolder).resolve("responses").resolve("file_20240101_120000");
        assertThat(Files.exists(archivedSubfolder)).isTrue();
        assertThat(Files.exists(archivedSubfolder.resolve("batch_001_of_001").resolve("request.json"))).isTrue();
        assertThat(Files.exists(responsesDir.resolve("file_20250301_120000"))).isTrue();
        assertThat(Files.exists(responsesDir.resolve("file_20240101_120000"))).isFalse();
    }

    @Test
    void runArchive_shouldSkipWhenNoOldFiles() throws IOException {
        Files.createDirectories(completedDir);
        Path recentFile = completedDir.resolve("recent.json");
        Files.writeString(recentFile, "{}");
        setLastModified(recentFile, 1);

        var result = archiveService.runArchive();

        assertThat(result.isRan()).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCompletedCount()).isZero();
        assertThat(Files.exists(completedDir.resolve("recent.json"))).isTrue();
    }

    @Test
    void runArchive_shouldSkipWhenArchiveDisabled() {
        archiveService = new ArchiveService(createPropsWithArchiveDisabled());

        var result = archiveService.runArchive();

        assertThat(result.isRan()).isFalse();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void runArchive_shouldPreserveNestedStructureInCompleted() throws IOException {
        Files.createDirectories(completedDir);
        Path nestedDir = completedDir.resolve("servicer_A");
        Files.createDirectories(nestedDir);
        Path oldFile = nestedDir.resolve("old_file.json");
        Files.writeString(oldFile, "{}");
        setLastModified(oldFile, 35);

        var result = archiveService.runArchive();

        assertThat(result.isRan()).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCompletedCount()).isEqualTo(1);

        String monthFolder = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        Path archived = archiveDir.resolve(monthFolder).resolve("completed").resolve("servicer_A").resolve("old_file.json");
        assertThat(Files.exists(archived)).isTrue();
        assertThat(Files.exists(completedDir.resolve("servicer_A").resolve("old_file.json"))).isFalse();
    }

    @Test
    void runArchive_shouldPreserveNestedStructureInErrorAndMoveSidecar() throws IOException {
        Files.createDirectories(errorDir);
        Path nestedDir = errorDir.resolve("servicer_B");
        Files.createDirectories(nestedDir);
        Path oldJson = nestedDir.resolve("old_error.json");
        Path oldSidecar = nestedDir.resolve("old_error.json.error.txt");
        Files.writeString(oldJson, "{}");
        Files.writeString(oldSidecar, "Read error: test");
        setLastModified(oldJson, 35);
        setLastModified(oldSidecar, 35);

        var result = archiveService.runArchive();

        assertThat(result.isRan()).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getErrorCount()).isEqualTo(2);

        String monthFolder = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        Path archiveErrorDir = archiveDir.resolve(monthFolder).resolve("error").resolve("servicer_B");
        assertThat(Files.exists(archiveErrorDir.resolve("old_error.json"))).isTrue();
        assertThat(Files.exists(archiveErrorDir.resolve("old_error.json.error.txt"))).isTrue();
        assertThat(Files.exists(errorDir.resolve("servicer_B"))).isFalse();
    }

    @Test
    void runArchive_shouldDeleteEmptyFoldersAfterArchiving() throws IOException {
        Files.createDirectories(completedDir);
        Path nestedDir = completedDir.resolve("servicer_A");
        Files.createDirectories(nestedDir);
        Path oldFile = nestedDir.resolve("only_file.json");
        Files.writeString(oldFile, "{}");
        setLastModified(oldFile, 35);

        var result = archiveService.runArchive();

        assertThat(result.isRan()).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCompletedCount()).isEqualTo(1);
        assertThat(Files.exists(completedDir.resolve("servicer_A"))).isFalse();
    }

    @Test
    void runArchive_shouldArchiveLargeResponseFolderWithManyBatches() throws IOException {
        // Simulate a large response folder (e.g. from processing a 100k-record file with 100 batches)
        Files.createDirectories(responsesDir);
        Path largeResponseFolder = responsesDir.resolve("large_file_20240101_120000");
        Files.createDirectories(largeResponseFolder);

        // Create ~1MB content per file (repeated string)
        String largeContent = "x".repeat(1024 * 1024);

        // 10 batch folders with large request/response files
        for (int i = 1; i <= 10; i++) {
            Path batchDir = largeResponseFolder.resolve(String.format("batch_%03d_of_010", i));
            Files.createDirectories(batchDir);
            Files.writeString(batchDir.resolve("request.json"), largeContent);
            Files.writeString(batchDir.resolve("response.json"), "{\"status\":\"SUCCESS\"}");
        }
        Files.writeString(largeResponseFolder.resolve("large_file.FINAL.json"), "{\"overallStatus\":\"SUCCESS\"}");
        setLastModified(largeResponseFolder, 35);

        var result = archiveService.runArchive();

        assertThat(result.isRan()).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponsesCount()).isEqualTo(1);

        String monthFolder = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        Path archived = archiveDir.resolve(monthFolder).resolve("responses").resolve("large_file_20240101_120000");
        assertThat(Files.exists(archived)).isTrue();
        assertThat(Files.exists(archived.resolve("large_file.FINAL.json"))).isTrue();
        for (int i = 1; i <= 10; i++) {
            Path batchDir = archived.resolve(String.format("batch_%03d_of_010", i));
            assertThat(Files.exists(batchDir.resolve("request.json"))).isTrue();
            assertThat(Files.exists(batchDir.resolve("response.json"))).isTrue();
            assertThat(Files.size(batchDir.resolve("request.json"))).isEqualTo(1024 * 1024);
        }
        assertThat(Files.exists(responsesDir.resolve("large_file_20240101_120000"))).isFalse();
    }

    @Test
    void runArchive_shouldPreserveNestedStructureInResponsesAndDeleteEmptyFolders() throws IOException {
        Files.createDirectories(responsesDir);
        Path nestedDir = responsesDir.resolve("servicer_C");
        Files.createDirectories(nestedDir);
        Path oldSubfolder = nestedDir.resolve("file_20240101_120000");
        Files.createDirectories(oldSubfolder);
        Path batchDir = oldSubfolder.resolve("batch_001_of_001");
        Files.createDirectories(batchDir);
        Files.writeString(batchDir.resolve("request.json"), "{}");
        Files.writeString(batchDir.resolve("response.json"), "{}");
        setLastModified(oldSubfolder, 35);

        var result = archiveService.runArchive();

        assertThat(result.isRan()).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponsesCount()).isEqualTo(1);

        String monthFolder = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        Path archivedSubfolder = archiveDir.resolve(monthFolder).resolve("responses").resolve("servicer_C").resolve("file_20240101_120000");
        assertThat(Files.exists(archivedSubfolder)).isTrue();
        assertThat(Files.exists(archivedSubfolder.resolve("batch_001_of_001").resolve("request.json"))).isTrue();
        assertThat(Files.exists(responsesDir.resolve("servicer_C"))).isFalse();
    }

    private void setLastModified(Path path, int daysAgo) throws IOException {
        FileTime time = FileTime.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
        Files.setLastModifiedTime(path, time);
    }

    private NasProperties createPropsWithArchiveDisabled() {
        NasProperties p = new NasProperties();
        p.setFolders(new NasProperties.Folders());
        NasProperties.Archive archive = new NasProperties.Archive();
        archive.setEnabled(false);
        p.setArchive(archive);
        return p;
    }
}
