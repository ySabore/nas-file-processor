package com.example.nasprocessor.service;

import com.example.nasprocessor.config.NasProperties;
import com.example.nasprocessor.model.FileProcessingResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailNotificationServiceTest {

    private NasProperties nasProperties;
    private ObjectMapper objectMapper;
    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        nasProperties = new NasProperties();
        NasProperties.Email email = new NasProperties.Email();
        email.setEnabled(false);
        email.setDistribution(List.of("test@example.com"));
        nasProperties.setEmail(email);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new EmailNotificationService(nasProperties, objectMapper);
    }

    @Test
    void sendFinalResponseSummary_nullResults_shouldNotSend() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        injectMailSender(service, mailSender);

        service.sendFinalResponseSummary(null);

        verifyNoInteractions(mailSender);
    }

    @Test
    void sendFinalResponseSummary_emptyResults_shouldNotSend() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        injectMailSender(service, mailSender);

        service.sendFinalResponseSummary(List.of());

        verifyNoInteractions(mailSender);
    }

    @Test
    void sendFinalResponseSummary_emailDisabled_shouldNotSend() {
        nasProperties.getEmail().setEnabled(false);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        injectMailSender(service, mailSender);
        List<FileProcessingResult> results = List.of(
                FileProcessingResult.builder().fileName("a.json").status(FileProcessingResult.Status.SUCCESS).build()
        );

        service.sendFinalResponseSummary(results);

        verifyNoInteractions(mailSender);
    }

    @Test
    void sendFinalResponseSummary_emptyDistribution_shouldNotSend() {
        nasProperties.getEmail().setEnabled(true);
        nasProperties.getEmail().setDistribution(List.of());
        JavaMailSender mailSender = mock(JavaMailSender.class);
        injectMailSender(service, mailSender);
        List<FileProcessingResult> results = List.of(
                FileProcessingResult.builder().fileName("a.json").status(FileProcessingResult.Status.SUCCESS).build()
        );

        service.sendFinalResponseSummary(results);

        verifyNoInteractions(mailSender);
    }

    @Test
    void sendFinalResponseSummary_mailSenderNull_shouldNotThrow() {
        nasProperties.getEmail().setEnabled(true);
        nasProperties.getEmail().setDistribution(List.of("a@b.com"));
        // mailSender left null (simulating not configured)
        service.sendFinalResponseSummary(List.of(
                FileProcessingResult.builder().fileName("a.json").status(FileProcessingResult.Status.SUCCESS).build()
        ));
        // no exception
    }

    @Test
    void sendFinalResponseSummary_withResultsAndMailSender_shouldCallSend() throws Exception {
        nasProperties.getEmail().setEnabled(true);
        nasProperties.getEmail().setDistribution(List.of("team@example.com"));

        Session session = Session.getDefaultInstance(new Properties());
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));
        injectMailSender(service, mailSender);

        LocalDateTime now = LocalDateTime.now();
        List<FileProcessingResult> results = List.of(
                FileProcessingResult.builder()
                        .fileName("file1.json")
                        .status(FileProcessingResult.Status.SUCCESS)
                        .totalRecords(100)
                        .processedRecords(100)
                        .durationMs(200)
                        .startTime(now)
                        .endTime(now)
                        .finalResponseFilePath("/path/to/file1.FINAL.json")
                        .build()
        );

        service.sendFinalResponseSummary(results);

        verify(mailSender).createMimeMessage();
        var captor = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        assertThat(sent.getSubject()).contains("NAS File Processor").contains("1 file(s)");
        assertThat(sent.getRecipients(jakarta.mail.Message.RecipientType.TO)).hasSize(1);
    }

    private void injectMailSender(EmailNotificationService target, JavaMailSender mailSender) {
        try {
            var field = EmailNotificationService.class.getDeclaredField("mailSender");
            field.setAccessible(true);
            field.set(target, mailSender);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
