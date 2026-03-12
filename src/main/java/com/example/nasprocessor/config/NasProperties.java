package com.example.nasprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "nas")
public class NasProperties {

    private Folders folders = new Folders();
    private int batchSize = 100;
    /** Max concurrent API calls per file. Set to 1 for sequential processing. */
    private int parallelBatchLimit = 5;
    private String fileExtension = ".json";
    private boolean useLockFile = true;
    /** When true, scan subfolders recursively and preserve folder structure in completed/error. */
    private boolean scanRecursive = false;

    /** Archive: move old files to nas/archive/YYYY-MM/ to manage disk space */
    private Archive archive = new Archive();

    /** Email: send final response summary when processing cycle completes */
    private Email email = new Email();

    @Data
    public static class Folders {
        private String inbound;
        private String processing;
        private String error;
        private String completed;
        private String responses;
    }

    @Data
    public static class Archive {
        private boolean enabled = false;
        private int retentionDays = 30;
        private String folder = "nas/archive";
    }

    @Data
    public static class Email {
        private boolean enabled = false;
        /** Distribution list for final response notifications */
        private List<String> distribution = new ArrayList<>();
    }
}
