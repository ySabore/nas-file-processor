package com.example.nasprocessor.service;

import com.example.nasprocessor.config.NasProperties;
import com.example.nasprocessor.model.FileProcessingResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sends email notifications when a processing cycle completes.
 * Enabled/disabled via nas.email.enabled. Distribution list from nas.email.distribution.
 * Attaches full response as JSON file with structure preserved (details grouped by folder).
 */
@Slf4j
@Service
public class EmailNotificationService {

    private static final DateTimeFormatter ATTACHMENT_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private final NasProperties nasProperties;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    public EmailNotificationService(NasProperties nasProperties, ObjectMapper objectMapper) {
        this.nasProperties = nasProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Send a summary email after processing cycle completes.
     * Attaches full response as JSON file with structure preserved.
     * No-op if email is disabled or distribution list is empty.
     */
    public void sendFinalResponseSummary(List<FileProcessingResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }

        NasProperties.Email emailConfig = nasProperties.getEmail();
        if (!emailConfig.isEnabled()) {
            return;
        }

        List<String> recipients = emailConfig.getDistribution();
        if (recipients == null || recipients.isEmpty()) {
            log.warn("Email enabled but distribution list is empty - skipping notification");
            return;
        }

        if (mailSender == null) {
            log.warn("Email enabled but JavaMailSender not configured (check spring.mail.*) - skipping notification");
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(recipients.toArray(new String[0]));
            helper.setSubject(buildSubject(results));
            helper.setText(buildBody(results), true);

            String timestamp = LocalDateTime.now().format(ATTACHMENT_TS);
            String baseName = "processing-response-" + timestamp;

            // Attach JSON with structure preserved
            Map<String, Object> responsePayload = buildResponsePayload(results);
            @SuppressWarnings("unchecked")
            Map<String, List<FileProcessingResult>> detailsByFolder = (Map<String, List<FileProcessingResult>>) responsePayload.get("details");
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(responsePayload);
            helper.addAttachment(baseName + ".json", new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)), "application/json");

            // Attach HTML report with same structure
            String html = buildHtmlReport(responsePayload, detailsByFolder);
            helper.addAttachment(baseName + ".html", new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8)), "text/html");

            mailSender.send(message);
            log.info("Sent final response summary email to {} recipient(s) with JSON and HTML attachments", recipients.size());
        } catch (MessagingException e) {
            log.error("Failed to send final response email: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to build/send final response email: {}", e.getMessage(), e);
        }
    }

    /**
     * Build response payload with same structure as ProcessorController.trigger():
     * details grouped by folder (servicer_A, servicer_B, root), plus status, counts, timestamp.
     */
    private Map<String, Object> buildResponsePayload(List<FileProcessingResult> results) {
        long succeeded = results.stream()
                .filter(r -> r.getStatus() == FileProcessingResult.Status.SUCCESS)
                .count();
        long partial = results.stream()
                .filter(r -> r.getStatus() == FileProcessingResult.Status.PARTIAL_SUCCESS)
                .count();
        long failed = results.stream()
                .filter(r -> r.getStatus() == FileProcessingResult.Status.FAILED)
                .count();

        Map<String, List<FileProcessingResult>> detailsByFolder = results.stream()
                .collect(Collectors.groupingBy(
                        r -> {
                            String fn = r.getFileName();
                            int slash = fn.indexOf('/');
                            return slash > 0 ? fn.substring(0, slash) : "root";
                        },
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("details", detailsByFolder);
        payload.put("failed", (int) failed);
        payload.put("partial", (int) partial);
        payload.put("status", failed > 0 ? "COMPLETED_WITH_ERRORS" : "SUCCESS");
        payload.put("filesFound", results.size());
        payload.put("succeeded", (int) succeeded);
        payload.put("timestamp", LocalDateTime.now().toString());
        return payload;
    }

    private String buildSubject(List<FileProcessingResult> results) {
        long success = results.stream().filter(r -> r.getStatus() == FileProcessingResult.Status.SUCCESS).count();
        long failed = results.stream().filter(r -> r.getStatus() == FileProcessingResult.Status.FAILED).count();
        long partial = results.stream().filter(r -> r.getStatus() == FileProcessingResult.Status.PARTIAL_SUCCESS).count();

        if (failed > 0 || partial > 0) {
            return String.format("NAS File Processor - %d file(s) processed (%d success, %d partial, %d failed)",
                    results.size(), success, partial, failed);
        }
        return String.format("NAS File Processor - %d file(s) processed successfully", results.size());
    }

    private String buildBody(List<FileProcessingResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Processing Cycle Summary</h2>");
        sb.append("<p>").append(results.size()).append(" file(s) processed. Full response attached as JSON and HTML.</p>");
        sb.append("<table border='1' cellpadding='5' cellspacing='0' style='border-collapse: collapse;'>");
        sb.append("<tr><th>File</th><th>Status</th><th>Records</th><th>Duration</th></tr>");

        for (FileProcessingResult r : results) {
            sb.append("<tr>");
            sb.append("<td>").append(escape(r.getFileName())).append("</td>");
            sb.append("<td>").append(r.getStatus()).append("</td>");
            sb.append("<td>").append(r.getProcessedRecords()).append("/").append(r.getTotalRecords()).append("</td>");
            sb.append("<td>").append(r.getDurationMs()).append(" ms</td>");
            sb.append("</tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    /**
     * Build standalone HTML report with details grouped by folder (same structure as JSON).
     */
    private String buildHtmlReport(Map<String, Object> responsePayload, Map<String, List<FileProcessingResult>> detailsByFolder) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        sb.append("<title>NAS File Processor - Processing Response</title>");
        sb.append("<style>");
        sb.append("body{font-family:sans-serif;margin:20px;background:#f5f5f5;}");
        sb.append(".summary{background:#fff;padding:15px;margin-bottom:20px;border-radius:6px;box-shadow:0 1px 3px rgba(0,0,0,.1);}");
        sb.append(".summary table{width:auto;}");
        sb.append("h2{color:#333;border-bottom:1px solid #ddd;padding-bottom:8px;}");
        sb.append("h3{color:#555;margin-top:20px;}");
        sb.append("table{border-collapse:collapse;width:100%;background:#fff;margin-bottom:20px;border-radius:6px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.1);}");
        sb.append("th,td{border:1px solid #ddd;padding:10px;text-align:left;}");
        sb.append("th{background:#4a90d9;color:#fff;}");
        sb.append("tr:nth-child(even){background:#f9f9f9;}");
        sb.append(".status-SUCCESS{color:#2e7d32;}");
        sb.append(".status-FAILED{color:#c62828;}");
        sb.append(".status-PARTIAL_SUCCESS{color:#ef6c00;}");
        sb.append(".folder-section{background:#fff;padding:15px;margin-bottom:15px;border-radius:6px;box-shadow:0 1px 3px rgba(0,0,0,.1);}");
        sb.append("</style></head><body>");

        sb.append("<h2>NAS File Processor - Processing Response</h2>");

        // Summary section
        sb.append("<div class='summary'>");
        sb.append("<h3>Summary</h3>");
        sb.append("<table><tr><th>Status</th><th>Files Found</th><th>Succeeded</th><th>Partial</th><th>Failed</th><th>Timestamp</th></tr>");
        sb.append("<tr>");
        sb.append("<td>").append(escape(String.valueOf(responsePayload.get("status")))).append("</td>");
        sb.append("<td>").append(responsePayload.get("filesFound")).append("</td>");
        sb.append("<td>").append(responsePayload.get("succeeded")).append("</td>");
        sb.append("<td>").append(responsePayload.get("partial")).append("</td>");
        sb.append("<td>").append(responsePayload.get("failed")).append("</td>");
        sb.append("<td>").append(escape(String.valueOf(responsePayload.get("timestamp")))).append("</td>");
        sb.append("</tr></table></div>");

        // Details grouped by folder
        if (detailsByFolder != null) {
            for (Map.Entry<String, List<FileProcessingResult>> entry : detailsByFolder.entrySet()) {
                String folder = entry.getKey();
                List<FileProcessingResult> items = entry.getValue();
                sb.append("<div class='folder-section'>");
                sb.append("<h3>").append(escape(folder)).append("</h3>");
                sb.append("<table><tr><th>File</th><th>Status</th><th>Records</th><th>Duration</th><th>Start</th><th>End</th><th>Final Response Path</th></tr>");
                for (FileProcessingResult r : items) {
                    String status = r.getStatus().name();
                    sb.append("<tr>");
                    sb.append("<td>").append(escape(r.getFileName())).append("</td>");
                    sb.append("<td class='status-").append(status).append("'>").append(escape(status)).append("</td>");
                    sb.append("<td>").append(r.getProcessedRecords()).append("/").append(r.getTotalRecords()).append("</td>");
                    sb.append("<td>").append(r.getDurationMs()).append(" ms</td>");
                    sb.append("<td>").append(escape(String.valueOf(r.getStartTime()))).append("</td>");
                    sb.append("<td>").append(escape(String.valueOf(r.getEndTime()))).append("</td>");
                    sb.append("<td>").append(escape(r.getFinalResponseFilePath() != null ? r.getFinalResponseFilePath() : "-")).append("</td>");
                    sb.append("</tr>");
                }
                sb.append("</table></div>");
            }
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
