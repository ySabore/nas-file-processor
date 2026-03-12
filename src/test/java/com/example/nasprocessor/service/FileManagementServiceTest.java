package com.example.nasprocessor.service;

import com.example.nasprocessor.config.NasProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileManagementServiceTest {

    @TempDir
    Path tempDir;

    private FileManagementService service;
    private NasProperties.Folders folders;

    @BeforeEach
    void setUp() {
        NasProperties nasProperties = new NasProperties();
        folders = new NasProperties.Folders();
        folders.setInbound(tempDir.resolve("inbound").toString());
        folders.setProcessing(tempDir.resolve("processing").toString());
        folders.setError(tempDir.resolve("error").toString());
        folders.setCompleted(tempDir.resolve("completed").toString());
        nasProperties.setFolders(folders);
        nasProperties.setFileExtension(".json");
        nasProperties.setUseLockFile(true);

        service = new FileManagementService(nasProperties);
    }

    @Test
    void getInboundFiles_shouldReturnOnlyUnlockedJsonFiles() throws IOException {
        Path inboundDir = Path.of(folders.getInbound());
        Files.createDirectories(inboundDir);

        Path file1 = inboundDir.resolve("a.json");
        Path file2 = inboundDir.resolve("b.json");
        Path otherExt = inboundDir.resolve("ignore.txt");

        Files.writeString(file1, "{}");
        Files.writeString(file2, "{}");
        Files.writeString(otherExt, "text");

        // Simulate locked file1
        Files.writeString(inboundDir.resolve("a.json.lock"), "locked");

        List<Path> inboundFiles = service.getInboundFiles();

        assertThat(inboundFiles)
                .extracting(p -> p.getFileName().toString())
                .containsExactly("b.json");
    }

    @Test
    void copyToProcessing_shouldCopyFileAndCreateLockOnInbound() throws IOException {
        Path inboundDir = Path.of(folders.getInbound());
        Files.createDirectories(inboundDir);
        Path source = inboundDir.resolve("file.json");
        Files.writeString(source, "{}");

        Path processingPath = service.copyToProcessing(source);

        assertThat(Files.exists(source)).isTrue();
        assertThat(processingPath.getParent().toString()).isEqualTo(folders.getProcessing());
        assertThat(processingPath.getFileName().toString()).isEqualTo("file.json");

        // Lock file should exist on inbound file
        Path lockFile = inboundDir.resolve("file.json.lock");
        assertThat(Files.exists(lockFile)).isTrue();
    }

    @Test
    void getInboundFiles_scanRecursive_shouldFindFilesInSubfolders() throws IOException {
        NasProperties props = createPropsWithScanRecursive();
        FileManagementService recursiveService = new FileManagementService(props);
        Path inboundDir = Path.of(folders.getInbound());
        Files.createDirectories(inboundDir.resolve("folder_a"));
        Files.createDirectories(inboundDir.resolve("folder_b/sub"));

        Files.writeString(inboundDir.resolve("root.json"), "{}");
        Files.writeString(inboundDir.resolve("folder_a/nested.json"), "{}");
        Files.writeString(inboundDir.resolve("folder_b/sub/deep.json"), "{}");

        List<Path> inboundFiles = recursiveService.getInboundFiles();

        assertThat(inboundFiles).hasSize(3);
        assertThat(inboundFiles.stream().map(p -> inboundDir.relativize(p).toString()))
                .containsExactlyInAnyOrder("root.json", "folder_a/nested.json", "folder_b/sub/deep.json");
    }

    @Test
    void copyToProcessing_scanRecursive_shouldPreserveStructure() throws IOException {
        FileManagementService recursiveService = new FileManagementService(createPropsWithScanRecursive());
        Path inboundDir = Path.of(folders.getInbound());
        Path processingDir = Path.of(folders.getProcessing());
        Files.createDirectories(inboundDir.resolve("folder_a"));

        Path source = inboundDir.resolve("folder_a/file.json");
        Files.writeString(source, "{}");

        Path processingPath = recursiveService.copyToProcessing(source);

        assertThat(Files.exists(source)).isTrue();
        assertThat(processingPath).isEqualTo(processingDir.resolve("folder_a/file.json"));
        assertThat(Files.readString(processingPath)).isEqualTo("{}");
    }

    @Test
    void moveInboundToCompleted_scanRecursive_shouldPreserveStructure() throws IOException {
        FileManagementService recursiveService = new FileManagementService(createPropsWithScanRecursive());
        Path inboundDir = Path.of(folders.getInbound());
        Path processingDir = Path.of(folders.getProcessing());
        Path completedDir = Path.of(folders.getCompleted());
        Files.createDirectories(inboundDir.resolve("folder_a"));
        Files.createDirectories(processingDir.resolve("folder_a"));

        Path inboundFile = inboundDir.resolve("folder_a/done.json");
        Path processingFile = processingDir.resolve("folder_a/done.json");
        Files.writeString(inboundFile, "{\"data\": 1}");
        Files.writeString(processingFile, "{}");

        recursiveService.moveInboundToCompleted(inboundFile, processingFile, "done.json");

        assertThat(Files.exists(inboundFile)).isFalse();
        assertThat(Files.exists(processingFile)).isFalse();
        Path completedFile = completedDir.resolve("folder_a/done.json");
        assertThat(Files.exists(completedFile)).isTrue();
        assertThat(Files.readString(completedFile)).isEqualTo("{\"data\": 1}");
    }

    @Test
    void moveInboundToCompleted_shouldDeleteEmptyInboundAndProcessingFolders() throws IOException {
        FileManagementService recursiveService = new FileManagementService(createPropsWithScanRecursive());
        Path inboundDir = Path.of(folders.getInbound());
        Path processingDir = Path.of(folders.getProcessing());
        Path completedDir = Path.of(folders.getCompleted());
        Path folderA = inboundDir.resolve("folder_a");
        Path processingFolderA = processingDir.resolve("folder_a");
        Files.createDirectories(folderA);
        Files.createDirectories(processingFolderA);

        Path inboundFile = folderA.resolve("only_file.json");
        Path processingFile = processingFolderA.resolve("only_file.json");
        Files.writeString(inboundFile, "{}");
        Files.writeString(processingFile, "{}");

        recursiveService.moveInboundToCompleted(inboundFile, processingFile, "only_file.json");

        assertThat(Files.exists(inboundFile)).isFalse();
        assertThat(Files.exists(folderA)).isFalse();
        assertThat(Files.exists(processingFile)).isFalse();
        assertThat(Files.exists(processingFolderA)).isFalse();
        assertThat(Files.exists(completedDir.resolve("folder_a/only_file.json"))).isTrue();
    }

    private NasProperties createPropsWithScanRecursive() {
        NasProperties props = new NasProperties();
        props.setFolders(folders);
        props.setFileExtension(".json");
        props.setUseLockFile(true);
        props.setScanRecursive(true);
        return props;
    }

    @Test
    void moveInboundToCompleted_shouldMoveOriginalAndDeleteProcessingCopy() throws IOException {
        Path inboundDir = Path.of(folders.getInbound());
        Path processingDir = Path.of(folders.getProcessing());
        Files.createDirectories(inboundDir);
        Files.createDirectories(processingDir);

        Path inboundFile = inboundDir.resolve("to-complete.json");
        Path processingFile = processingDir.resolve("to-complete.json");
        Files.writeString(inboundFile, "{\"original\": true}");
        Files.writeString(processingFile, "{\"copy\": true}");

        Path lockFile = inboundDir.resolve("to-complete.json.lock");
        Files.writeString(lockFile, "locked");

        service.moveInboundToCompleted(inboundFile, processingFile, "to-complete.json");

        assertThat(Files.exists(inboundFile)).isFalse();
        assertThat(Files.exists(processingFile)).isFalse();
        assertThat(Files.exists(lockFile)).isFalse();

        Path completedDir = Path.of(folders.getCompleted());
        assertThat(Files.list(completedDir)).hasSize(1);
        String completedName = Files.list(completedDir).findFirst().orElseThrow().getFileName().toString();
        assertThat(completedName).isEqualTo("to-complete.json");
        assertThat(Files.readString(completedDir.resolve(completedName))).isEqualTo("{\"original\": true}");
    }

    @Test
    void moveToError_scanRecursive_shouldPreserveStructure() throws IOException {
        FileManagementService recursiveService = new FileManagementService(createPropsWithScanRecursive());
        Path inboundDir = Path.of(folders.getInbound());
        Path errorDir = Path.of(folders.getError());
        Path processingDir = Path.of(folders.getProcessing());
        Files.createDirectories(inboundDir.resolve("folder_a"));
        Files.createDirectories(processingDir.resolve("folder_a"));

        Path inboundFile = inboundDir.resolve("folder_a/fail.json");
        Path processingFile = processingDir.resolve("folder_a/fail.json");
        Files.writeString(inboundFile, "{}");
        Files.writeString(processingFile, "{}");

        recursiveService.moveToError(inboundFile, processingFile, "test error");

        assertThat(Files.exists(inboundFile)).isFalse();
        assertThat(Files.exists(processingFile)).isFalse();
        Path errorSubfolder = errorDir.resolve("folder_a");
        assertThat(Files.exists(errorSubfolder)).isTrue();
        boolean hasJson;
        try (var stream = Files.list(errorSubfolder)) {
            hasJson = stream.anyMatch(p -> p.getFileName().toString().endsWith(".json"));
        }
        assertThat(hasJson).isTrue();
    }

    @Test
    void moveToError_shouldMoveInboundToErrorAndDeleteProcessingCopy() throws IOException {
        Path inboundDir = Path.of(folders.getInbound());
        Path processingDir = Path.of(folders.getProcessing());
        Files.createDirectories(inboundDir);
        Files.createDirectories(processingDir);

        Path inboundFile = inboundDir.resolve("to-error.json");
        Path processingFile = processingDir.resolve("to-error.json");
        Files.writeString(inboundFile, "{}");
        Files.writeString(processingFile, "{}");

        Path lockFile = inboundDir.resolve("to-error.json.lock");
        Files.writeString(lockFile, "locked");

        service.moveToError(inboundFile, processingFile, "unit test reason");

        assertThat(Files.exists(inboundFile)).isFalse();
        assertThat(Files.exists(processingFile)).isFalse();
        assertThat(Files.exists(lockFile)).isFalse();

        Path errorDir = Path.of(folders.getError());
        List<Path> errorFiles = Files.list(errorDir).toList();
        assertThat(errorFiles).isNotEmpty();

        // One JSON moved file and one .error.txt file
        assertThat(errorFiles.stream().anyMatch(p -> p.getFileName().toString().endsWith(".json"))).isTrue();
        assertThat(errorFiles.stream().anyMatch(p -> p.getFileName().toString().endsWith(".error.txt"))).isTrue();
    }
}

