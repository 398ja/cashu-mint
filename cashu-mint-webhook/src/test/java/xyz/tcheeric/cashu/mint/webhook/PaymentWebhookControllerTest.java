package xyz.tcheeric.cashu.mint.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for PaymentWebhookController.
 */
@ExtendWith(MockitoExtension.class)
class PaymentWebhookControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private QuoteStatusUpdater quoteStatusUpdater;

    @Mock
    private WebhookSignatureValidator signatureValidator;

    @BeforeEach
    void setUp() {
        PaymentWebhookController controller = new PaymentWebhookController(
                quoteStatusUpdater, signatureValidator);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void handlePaymentWebhook_shouldReturn200OnSuccess() throws Exception {
        // Given
        when(signatureValidator.validate(any(), any())).thenReturn(true);
        when(quoteStatusUpdater.markAsPaid(any())).thenReturn(true);

        PaymentNotification notification = createNotification("quote123", 1000, "preimage456");
        String json = objectMapper.writeValueAsString(notification);

        // When/Then
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Payment recorded"));

        verify(quoteStatusUpdater).markAsPaid(any());
    }

    @Test
    void handlePaymentWebhook_shouldReturn200OnDuplicate() throws Exception {
        // Given
        when(signatureValidator.validate(any(), any())).thenReturn(true);
        when(quoteStatusUpdater.markAsPaid(any())).thenReturn(false); // Duplicate

        PaymentNotification notification = createNotification("quote123", 1000, "preimage456");
        String json = objectMapper.writeValueAsString(notification);

        // When/Then
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Duplicate ignored"));
    }

    @Test
    void handlePaymentWebhook_shouldReturn401OnInvalidSignature() throws Exception {
        // Given
        when(signatureValidator.validate(any(), any())).thenReturn(false);

        PaymentNotification notification = createNotification("quote123", 1000, "preimage456");
        String json = objectMapper.writeValueAsString(notification);

        // When/Then
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", "invalid")
                        .content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Invalid signature"));

        verify(quoteStatusUpdater, never()).markAsPaid(any());
    }

    @Test
    void handlePaymentWebhook_shouldReturn500OnError() throws Exception {
        // Given
        when(signatureValidator.validate(any(), any())).thenReturn(true);
        when(quoteStatusUpdater.markAsPaid(any())).thenThrow(new RuntimeException("DB error"));

        PaymentNotification notification = createNotification("quote123", 1000, "preimage456");
        String json = objectMapper.writeValueAsString(notification);

        // When/Then
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void health_shouldReturnStatus() throws Exception {
        // Given
        when(signatureValidator.isEnabled()).thenReturn(true);
        when(quoteStatusUpdater.getCacheSize()).thenReturn(5);
        when(quoteStatusUpdater.getProcessedCount()).thenReturn(10);

        // When/Then
        mockMvc.perform(get("/webhook/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.signatureValidationEnabled").value(true))
                .andExpect(jsonPath("$.cachedQuotes").value(5))
                .andExpect(jsonPath("$.processedNotifications").value(10));
    }

    @Test
    void handlePaymentWebhook_shouldAcceptIdempotencyKeyHeader() throws Exception {
        // Given
        when(signatureValidator.validate(any(), any())).thenReturn(true);
        when(quoteStatusUpdater.markAsPaid(any())).thenReturn(true);

        PaymentNotification notification = createNotification("quote123", 1000, "preimage456");
        String json = objectMapper.writeValueAsString(notification);

        // When/Then
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "bolt11:quote123")
                        .content(json))
                .andExpect(status().isOk());
    }

    private PaymentNotification createNotification(String quoteId, int amount, String preimage) {
        return PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(amount)
                .preimage(preimage)
                .paidAt(Instant.now())
                .build();
    }
}
