package uk.gov.defra.trade.imports.animals.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.defra.trade.imports.animals.notification.NotificationController.HEADER_TRACE_ID;
import static uk.gov.defra.trade.imports.animals.notification.NotificationController.HEADER_USER_ID;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignments;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignors;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.destinations;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.species;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.transporters;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;
import uk.gov.defra.trade.imports.animals.outbox.OutboxReplayService;
import uk.gov.defra.trade.imports.animals.outbox.OutboxService;

@WebMvcTest(NotificationController.class)
@TestPropertySource(properties = {
    "admin.secret=test-secret",
    "app.base-url=http://localhost:8085",
    "outbox.sns.topic-arn=arn:aws:sns:eu-west-2:000000000000:unit-test-outbox.fifo"
})
class NotificationControllerTest {

    private static final String REF_1 = "GBN-AG-26-ABC001";
    private static final String REF_2 = "GBN-AG-26-ABC002";
    private static final String REF_3 = "GBN-AG-26-ABC003";
    private static final String NONEXISTENT_REF = "GBN-AG-00-000000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private OutboxService outboxService;

    @MockitoBean
    private OutboxReplayService outboxReplayService;

    @Nested
    class ReplaceNotification {

        @Test
        void replace_shouldPassTheActorThrough_soReferencedPartiesResolveOnTheEditEvent()
            throws Exception {
            NotificationDto notificationDto = NotificationDto.builder()
                .referenceNumber(REF_1)
                .consignor(ConsignmentParty.reference("665f1c2ab3e4d51a2c9d0e77"))
                .build();
            SaveNotificationDto body = SaveNotificationDto.builder()
                .notification(notificationDto)
                .actor(ActorRequest.builder().organisationId("5900002").build())
                .build();

            NotificationAggregate replaced = new NotificationAggregate();
            replaced.setReferenceNumber(REF_1);
            when(notificationService.replace(eq(REF_1), any(NotificationDto.class), any(), any()))
                .thenReturn(replaced);

            mockMvc.perform(put("/notifications/{referenceNumber}", REF_1)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNumber").value(REF_1));

            verify(notificationService).replace(
                eq(REF_1), any(NotificationDto.class), any(),
                argThat(actor -> actor != null && "5900002".equals(actor.getOrganisationId())));
        }

        @Test
        void replace_shouldPassANullActor_whenTheBodyCarriesNone() throws Exception {
            SaveNotificationDto body = SaveNotificationDto.of(
                NotificationDto.builder().referenceNumber(REF_1).build());

            NotificationAggregate replaced = new NotificationAggregate();
            replaced.setReferenceNumber(REF_1);
            when(notificationService.replace(eq(REF_1), any(NotificationDto.class), any(), any()))
                .thenReturn(replaced);

            mockMvc.perform(put("/notifications/{referenceNumber}", REF_1)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

            verify(notificationService).replace(
                eq(REF_1), any(NotificationDto.class), any(), eq(null));
        }
    }

    @Nested
    class PostNotification {

        @Test
        void post_shouldCreateNotificationAndReturnReferenceNumber() throws Exception {
            // Given
            Origin origin = new Origin("GB", "true", "CUSTOMER-REF-123");
            Species species = species();
            CommodityComplement complement = new CommodityComplement("LIVE", 5, null, List.of(species));
            Commodity commodity = Commodity.builder()
                .name("Live bovine animals")
                .commodityComplement(List.of(complement))
                .build();
            NotificationDto notificationDto = NotificationDto.builder()
                .origin(origin)
                .commodity(commodity)
                .reasonForImport("PERMANENT")
                .consignor(consignors().getFirst())
                .destination(destinations().getFirst())
                .transport(Transport.builder().transporter(transporters().getFirst()).build())
                .consignment(consignments().getFirst())
                .build();

            NotificationAggregate savedNotification = new NotificationAggregate();
            savedNotification.setNotification(new Notification());
            savedNotification.setId("507f1f77bcf86cd799439011");
            savedNotification.setReferenceNumber(REF_1);
            savedNotification.getNotification().setOrigin(origin);
            savedNotification.getNotification().setCommodity(commodity);
            savedNotification.getNotification().setReasonForImport("PERMANENT");
            savedNotification.getNotification().setConsignor(consignors().getFirst());
            savedNotification.getNotification().setDestination(destinations().getFirst());
            savedNotification.getNotification().setTransport(Transport.builder().transporter(transporters().getFirst()).build());
            savedNotification.getNotification().setConsignment(consignments().getFirst());

            when(notificationService.saveNotification(any(NotificationDto.class), any(), any()))
                .thenReturn(savedNotification);

            // When & Then
            mockMvc.perform(post("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                        SaveNotificationDto.builder().notification(notificationDto).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("507f1f77bcf86cd799439011"))
                .andExpect(jsonPath("$.referenceNumber").value(REF_1))
                .andExpect(jsonPath("$.notification.origin.countryCode").value("GB"))
                .andExpect(jsonPath("$.notification.origin.internalReference").value("CUSTOMER-REF-123"))
                .andExpect(jsonPath("$.notification.commodity.name").value("Live bovine animals"))
                .andExpect(jsonPath("$.notification.commodity.commodityComplement[0].typeOfCommodity").value("LIVE"))
                .andExpect(jsonPath("$.notification.commodity.commodityComplement[0].species[0].value").value("BOV"))
                .andExpect(jsonPath("$.notification.commodity.commodityComplement[0].species[0].earTag").value(
                    "UK01234567890"))
                .andExpect(jsonPath("$.notification.commodity.commodityComplement[0].species[0].passport").value(
                    "UK0123456700999"))
                .andExpect(jsonPath("$.notification.reasonForImport").value("PERMANENT"))
                .andExpect(jsonPath("$.notification.consignor.name").value(consignors().getFirst().getName()))
                .andExpect(jsonPath("$.notification.consignor.address").value(consignors().getFirst().getAddress()))
                .andExpect(jsonPath("$.notification.destination.name").value(destinations().getFirst().getName()))
                .andExpect(jsonPath("$.notification.destination.address").value(destinations().getFirst().getAddress()))
                .andExpect(jsonPath("$.notification.transport.transporter.name").value(transporters().getFirst().getName()))
                .andExpect(jsonPath("$.notification.transport.transporter.address").value(transporters().getFirst().getAddress()))
                .andExpect(jsonPath("$.notification.transport.transporter.approvalNumber").value(transporters().getFirst().getApprovalNumber()))
                .andExpect(jsonPath("$.notification.transport.transporter.type").value(transporters().getFirst().getType()))
                .andExpect(jsonPath("$.notification.consignment.name")
                    .value(consignments().getFirst().getName()))
                .andExpect(jsonPath("$.notification.consignment.address.addressLine1")
                    .value(consignments().getFirst().getAddress().getAddressLine1()))
                .andExpect(jsonPath("$.notification.consignment.address.countryCode")
                    .value(consignments().getFirst().getAddress().getCountryCode()));
        }

        @Test
        void post_shouldAcceptNotificationWithAllOriginFields() throws Exception {
            // Given
            Origin origin = new Origin("FR", "false", "INTERNAL-456");
            NotificationDto notificationDto = NotificationDto.builder()
                .origin(origin)
                .build();

            NotificationAggregate savedNotification = new NotificationAggregate();
            savedNotification.setNotification(new Notification());
            savedNotification.setId("507f1f77bcf86cd799439012");
            savedNotification.setReferenceNumber(REF_2);
            savedNotification.getNotification().setOrigin(origin);

            when(notificationService.saveNotification(any(NotificationDto.class), any(), any()))
                .thenReturn(savedNotification);

            // When & Then
            mockMvc.perform(post("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                        SaveNotificationDto.builder().notification(notificationDto).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("507f1f77bcf86cd799439012"))
                .andExpect(jsonPath("$.referenceNumber").value(REF_2))
                .andExpect(jsonPath("$.notification.origin.countryCode").value("FR"))
                .andExpect(jsonPath("$.notification.origin.internalReference").value("INTERNAL-456"));
        }

        @Test
        void post_shouldAcceptNotificationWithExistingId() throws Exception {
            // Given
            String existingId = "507f1f77bcf86cd799439011";
            Origin origin = new Origin("DE", "true", "UPDATE-REF");
            NotificationDto notificationDto = NotificationDto.builder()
                .referenceNumber(REF_3)
                .origin(origin)
                .build();

            NotificationAggregate savedNotification = new NotificationAggregate();
            savedNotification.setNotification(new Notification());
            savedNotification.setId(existingId);
            savedNotification.setReferenceNumber(REF_3);
            savedNotification.getNotification().setOrigin(origin);

            when(notificationService.saveNotification(any(NotificationDto.class), any(), any()))
                .thenReturn(savedNotification);

            // When & Then
            mockMvc.perform(post("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                        SaveNotificationDto.builder().notification(notificationDto).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(existingId))
                .andExpect(jsonPath("$.referenceNumber").value(REF_3))
                .andExpect(jsonPath("$.notification.origin.countryCode").value("DE"))
                .andExpect(jsonPath("$.notification.origin.internalReference").value("UPDATE-REF"));
        }

        @Test
        void post_shouldForwardTraceIdHeader_toSaveNotification() throws Exception {
            // Given
            NotificationDto notificationDto = NotificationDto.builder()
                .origin(new Origin("GB", "true", "REF"))
                .build();
            NotificationAggregate saved = new NotificationAggregate();
            saved.setReferenceNumber(REF_1);
            when(notificationService.saveNotification(any(NotificationDto.class), any(), any()))
                .thenReturn(saved);

            // When & Then
            mockMvc.perform(post("/notifications")
                    .header(HEADER_TRACE_ID, "my-trace-id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                        SaveNotificationDto.builder().notification(notificationDto).build())))
                .andExpect(status().isOk());

            verify(notificationService).saveNotification(any(NotificationDto.class), eq("my-trace-id"), eq(null));
        }

        @Test
        void post_shouldPassActorToService_whenActorProvided() throws Exception {
            NotificationDto notificationDto = NotificationDto.builder()
                .referenceNumber(REF_1)
                .origin(new Origin("GB", "true", "REF"))
                .build();
            NotificationAggregate saved = new NotificationAggregate();
            saved.setReferenceNumber(REF_1);
            when(notificationService.saveNotification(any(NotificationDto.class), any(), any()))
                .thenReturn(saved);

            ActorRequest actorRequest = ActorRequest.builder()
                .id("contact-guid-001")
                .source("dynamics-contact")
                .userType("B2C")
                .displayName("Jane Farmer")
                .organisationId("org-001")
                .build();

            mockMvc.perform(post("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                        SaveNotificationDto.builder()
                            .notification(notificationDto)
                            .actor(actorRequest)
                            .build())))
                .andExpect(status().isOk());

            verify(notificationService).saveNotification(
                any(NotificationDto.class),
                anyString(),
                argThat(a -> a != null
                    && "contact-guid-001".equals(a.getId())
                    && "dynamics-contact".equals(a.getSource())
                    && "B2C".equals(a.getUserType())
                    && "Jane Farmer".equals(a.getDisplayName())
                    && "org-001".equals(a.getOrganisationId())));
        }

    }

    @Nested
    class CopyNotification {

        @Test
        void copy_shouldReturn200WithNewDraftNotification() throws Exception {
            // Given
            NotificationAggregate newNotification = new NotificationAggregate();
            newNotification.setId("507f1f77bcf86cd799439099");
            newNotification.setReferenceNumber(REF_2);
            newNotification.setStatus(NotificationStatus.DRAFT);

            when(notificationService.copyNotification(eq(REF_1), eq(0L), any(), any())).thenReturn(newNotification);

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/copy", REF_1)
                    .queryParam("concurrencyToken", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("507f1f77bcf86cd799439099"))
                .andExpect(jsonPath("$.referenceNumber").value(REF_2))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        @Test
        void copy_shouldReturn404_whenSourceNotFound() throws Exception {
            when(notificationService.copyNotification(eq(REF_1), eq(0L), any(), any()))
                .thenThrow(new NotFoundException("not found"));

            mockMvc.perform(post("/notifications/{referenceNumber}/copy", REF_1)
                    .queryParam("concurrencyToken", "0"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("not found"));
        }

        @Test
        void copy_shouldReturn400_whenSourceIsNotCopyable() throws Exception {
            when(notificationService.copyNotification(eq(REF_1), eq(0L), any(), any()))
                .thenThrow(new BadRequestException("not copyable"));

            mockMvc.perform(post("/notifications/{referenceNumber}/copy", REF_1)
                    .queryParam("concurrencyToken", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("not copyable"));
        }

        @Test
        void copy_shouldPassTraceIdAsCorrelationId() throws Exception {
            NotificationAggregate copied = new NotificationAggregate();
            copied.setReferenceNumber(REF_2);
            when(notificationService.copyNotification(REF_1, 0L, "trace-copy-001", null))
                .thenReturn(copied);

            mockMvc.perform(post("/notifications/{referenceNumber}/copy", REF_1)
                    .queryParam("concurrencyToken", "0")
                    .header(HEADER_TRACE_ID, "trace-copy-001"))
                .andExpect(status().isOk());

            verify(notificationService).copyNotification(REF_1, 0L, "trace-copy-001", null);
        }

        @Test
        void copy_shouldPassActorToService_whenActorBodyProvided() throws Exception {
            NotificationAggregate copied = new NotificationAggregate();
            copied.setReferenceNumber(REF_2);
            when(notificationService.copyNotification(eq(REF_1), eq(0L), any(), any()))
                .thenReturn(copied);

            String actorBody = """
                {
                    "id": "contact-guid-001",
                    "source": "dynamics-contact",
                    "userType": "B2C",
                    "displayName": "Jane Farmer",
                    "organisationId": "org-001"
                }
                """;

            mockMvc.perform(post("/notifications/{referenceNumber}/copy", REF_1)
                    .queryParam("concurrencyToken", "0")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(actorBody))
                .andExpect(status().isOk());

            verify(notificationService).copyNotification(
                eq(REF_1), eq(0L), anyString(),
                argThat(a -> a != null && "org-001".equals(a.getOrganisationId())));
        }
    }

    @Nested
    class SubmitNotification {

        @Test
        void submit_shouldReturn200WithSubmittedNotification() throws Exception {
            // Given
            NotificationAggregate submitted = new NotificationAggregate();
            submitted.setId("notif-id-001");
            submitted.setReferenceNumber(REF_1);
            submitted.setStatus(NotificationStatus.SUBMITTED);

            when(notificationService.submitNotification(eq(REF_1), anyString(), any()))
                .thenReturn(submitted);

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/submit", REF_1)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNumber").value(REF_1))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        }

        @Test
        void submit_shouldPassTraceIdAsCorrelationId() throws Exception {
            // Given
            NotificationAggregate submitted = new NotificationAggregate();
            submitted.setId("notif-id-001");
            submitted.setReferenceNumber(REF_1);
            submitted.setStatus(NotificationStatus.SUBMITTED);

            when(notificationService.submitNotification(REF_1, "trace-abc", null))
                .thenReturn(submitted);

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/submit", REF_1)
                    .header(HEADER_TRACE_ID, "trace-abc")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            verify(notificationService).submitNotification(REF_1, "trace-abc", null);
        }

        @Test
        void submit_shouldReturn404_whenReferenceNumberUnknown() throws Exception {
            // Given
            when(notificationService.submitNotification(eq(NONEXISTENT_REF), anyString(), any()))
                .thenThrow(new NotFoundException(
                    "Cannot find notification with reference number: " + NONEXISTENT_REF));

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/submit", NONEXISTENT_REF)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                    "Cannot find notification with reference number: " + NONEXISTENT_REF));
        }

        @Test
        void submit_shouldReturn400_whenNotificationNotInSubmittableState() throws Exception {
            // Given
            when(notificationService.submitNotification(eq(REF_1), anyString(), any()))
                .thenThrow(new BadRequestException(
                    "Cannot submit notification with status: DELETED"));

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/submit", REF_1)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                    "Cannot submit notification with status: DELETED"));
        }

        @Test
        void submit_shouldPassActorToService_whenActorBodyProvided() throws Exception {
            // Given
            NotificationAggregate submitted = new NotificationAggregate();
            submitted.setId("notif-id-001");
            submitted.setReferenceNumber(REF_1);
            submitted.setStatus(NotificationStatus.SUBMITTED);

            when(notificationService.submitNotification(eq(REF_1), anyString(), any()))
                .thenReturn(submitted);

            String actorBody = """
                {
                    "id": "contact-guid-001",
                    "source": "dynamics-contact",
                    "userType": "B2C",
                    "displayName": "Jane Farmer",
                    "organisationId": "org-001"
                }
                """;

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/submit", REF_1)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(actorBody))
                .andExpect(status().isOk());

            verify(notificationService).submitNotification(
                eq(REF_1), anyString(),
                argThat(a -> a != null
                    && "contact-guid-001".equals(a.getId())
                    && "dynamics-contact".equals(a.getSource())
                    && "B2C".equals(a.getUserType())
                    && "Jane Farmer".equals(a.getDisplayName())
                    && "org-001".equals(a.getOrganisationId())));
        }
    }

    @Nested
    class AmendNotification {

        @Test
        void amend_shouldReturn200WithAmendNotification() throws Exception {
            // Given
            NotificationAggregate amended = new NotificationAggregate();
            amended.setId("notif-id-001");
            amended.setReferenceNumber(REF_1);
            amended.setStatus(NotificationStatus.AMEND);

            when(notificationService.amendNotification(eq(REF_1), anyString(), any()))
                .thenReturn(amended);

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/amend", REF_1)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNumber").value(REF_1))
                .andExpect(jsonPath("$.status").value("AMEND"));
        }

        @Test
        void amend_shouldPassTraceIdAsCorrelationId() throws Exception {
            // Given
            NotificationAggregate amended = new NotificationAggregate();
            amended.setId("notif-id-001");
            amended.setReferenceNumber(REF_1);
            amended.setStatus(NotificationStatus.AMEND);

            when(notificationService.amendNotification(REF_1, "trace-xyz", null))
                .thenReturn(amended);

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/amend", REF_1)
                    .header(HEADER_TRACE_ID, "trace-xyz")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            verify(notificationService).amendNotification(REF_1, "trace-xyz", null);
        }

        @Test
        void amend_shouldReturn404_whenReferenceNumberUnknown() throws Exception {
            // Given
            when(notificationService.amendNotification(eq(NONEXISTENT_REF), anyString(), any()))
                .thenThrow(new NotFoundException(
                    "Cannot find notification with reference number: " + NONEXISTENT_REF));

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/amend", NONEXISTENT_REF)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                    "Cannot find notification with reference number: " + NONEXISTENT_REF));
        }

        @Test
        void amend_shouldReturn400_whenNotificationNotInAmendableState() throws Exception {
            // Given
            when(notificationService.amendNotification(eq(REF_1), anyString(), any()))
                .thenThrow(new BadRequestException(
                    "Cannot amend notification with status: DRAFT"));

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/amend", REF_1)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                    "Cannot amend notification with status: DRAFT"));
        }
    }

    @Nested
    class CancelAmendNotification {

        @Test
        void cancelAmend_shouldReturn200WithSubmittedNotification() throws Exception {
            // Given
            NotificationAggregate restored = new NotificationAggregate();
            restored.setId("notif-id-001");
            restored.setReferenceNumber(REF_1);
            restored.setStatus(NotificationStatus.SUBMITTED);

            when(notificationService.cancelAmendNotification(eq(REF_1), any(), any())).thenReturn(restored);

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/cancel-amend", REF_1)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNumber").value(REF_1))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

            verify(notificationService).cancelAmendNotification(eq(REF_1), any(), any());
        }

        @Test
        void cancelAmend_shouldReturn404_whenReferenceNumberUnknown() throws Exception {
            when(notificationService.cancelAmendNotification(eq(NONEXISTENT_REF), any(), any()))
                .thenThrow(new NotFoundException(
                    "Cannot find notification with reference number: " + NONEXISTENT_REF));

            mockMvc.perform(post("/notifications/{referenceNumber}/cancel-amend", NONEXISTENT_REF)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
        }

        @Test
        void cancelAmend_shouldReturn400_whenNotificationNotInAmendStatus() throws Exception {
            when(notificationService.cancelAmendNotification(eq(REF_1), any(), any()))
                .thenThrow(new BadRequestException(
                    "Cannot cancel amendment for notification with status: SUBMITTED"));

            mockMvc.perform(post("/notifications/{referenceNumber}/cancel-amend", REF_1)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                    "Cannot cancel amendment for notification with status: SUBMITTED"));
        }

        @Test
        void cancelAmend_shouldPassTraceIdAsCorrelationId() throws Exception {
            NotificationAggregate restored = new NotificationAggregate();
            restored.setReferenceNumber(REF_1);
            restored.setStatus(NotificationStatus.SUBMITTED);
            when(notificationService.cancelAmendNotification(REF_1, "trace-cancel-001", null))
                .thenReturn(restored);

            mockMvc.perform(post("/notifications/{referenceNumber}/cancel-amend", REF_1)
                    .header(HEADER_TRACE_ID, "trace-cancel-001")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            verify(notificationService).cancelAmendNotification(REF_1, "trace-cancel-001", null);
        }

        @Test
        void cancelAmend_shouldPassActorToService_whenActorBodyProvided() throws Exception {
            NotificationAggregate restored = new NotificationAggregate();
            restored.setReferenceNumber(REF_1);
            restored.setStatus(NotificationStatus.SUBMITTED);
            when(notificationService.cancelAmendNotification(eq(REF_1), any(), any()))
                .thenReturn(restored);

            String actorBody = """
                {
                    "id": "contact-guid-001",
                    "source": "dynamics-contact",
                    "userType": "B2C",
                    "displayName": "Jane Farmer",
                    "organisationId": "org-001"
                }
                """;

            mockMvc.perform(post("/notifications/{referenceNumber}/cancel-amend", REF_1)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(actorBody))
                .andExpect(status().isOk());

            verify(notificationService).cancelAmendNotification(
                eq(REF_1), anyString(),
                argThat(a -> a != null && "org-001".equals(a.getOrganisationId())));
        }
    }

    @Nested
    class FindAll {

        @Test
        void findAll_shouldReturnEmptyPage() throws Exception {
            // Given
            when(notificationService.findAll(1, null, null)).thenReturn(
                new NotificationPageResponse(Collections.emptyList(), 1, 25, 0, 0, 0));

            // When & Then
            mockMvc.perform(get("/notifications")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.numberOfElements").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
        }

        @Test
        void findAll_shouldReturnPageOfNotifications() throws Exception {
            // Given
            NotificationView notification1 = testView(REF_1, NotificationStatus.DRAFT,
                new Origin("GB", "true", "REF-GB-001"),
                Commodity.builder().name("Live cattle").build(),
                consignors().getFirst(),
                Transport.builder().transporter(transporters().getFirst()).build());

            NotificationView notification2 = testView(REF_2, NotificationStatus.SUBMITTED,
                new Origin("FR", "false", "REF-FR-002"),
                Commodity.builder().name("Live sheep").build(),
                consignors().getLast(),
                Transport.builder().transporter(transporters().getLast()).build());

            when(notificationService.findAll(1, null, null)).thenReturn(
                new NotificationPageResponse(List.of(notification1, notification2), 1, 25, 2, 2,
                    1));

            // When & Then
            mockMvc.perform(get("/notifications")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].referenceNumber").value(REF_1))
                .andExpect(jsonPath("$.content[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.content[0].origin.countryCode").value("GB"))
                .andExpect(jsonPath("$.content[0].commodity.name").value("Live cattle"))
                .andExpect(jsonPath("$.content[1].referenceNumber").value(REF_2))
                .andExpect(jsonPath("$.content[1].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.content[1].origin.countryCode").value("FR"))
                .andExpect(jsonPath("$.content[1].commodity.name").value("Live sheep"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));
        }

        private NotificationView testView(String ref, NotificationStatus status, Origin origin,
                Commodity commodity, ConsignmentParty consignor, Transport transport) {
            return new NotificationView.Data(
                ref, 0L, status, null, origin, commodity, consignor, null, transport, null);
        }

        @Test
        void findAll_shouldPassPageParam() throws Exception {
            // Given
            when(notificationService.findAll(2, null, null)).thenReturn(
                new NotificationPageResponse(Collections.emptyList(), 2, 25, 0, 120, 3));

            // When & Then
            mockMvc.perform(get("/notifications")
                    .param("page", "2")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(120))
                .andExpect(jsonPath("$.totalPages").value(3));
        }

        @Test
        void findAll_shouldPassSortParam() throws Exception {
            when(notificationService.findAll(1, "createdAt,desc", null)).thenReturn(
                new NotificationPageResponse(Collections.emptyList(), 1, 25, 0, 0, 0));

            mockMvc.perform(get("/notifications")
                    .param("sort", "createdAt,desc")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            verify(notificationService).findAll(1, "createdAt,desc", null);
        }

        @Test
        void findAll_shouldPassReferenceNumberParam() throws Exception {
            when(notificationService.findAll(1, null, REF_1)).thenReturn(
                new NotificationPageResponse(Collections.emptyList(), 1, 25, 0, 0, 0));

            mockMvc.perform(get("/notifications")
                    .param("referenceNumber", REF_1)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            verify(notificationService).findAll(1, null, REF_1);
        }

        @Test
        void findAll_shouldPassInvalidReferenceNumberToService() throws Exception {
            when(notificationService.findAll(1, null, "invalid-ref")).thenReturn(
                new NotificationPageResponse(Collections.emptyList(), 1, 25, 0, 0, 0));

            mockMvc.perform(get("/notifications")
                    .param("referenceNumber", "invalid-ref")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            verify(notificationService).findAll(1, null, "invalid-ref");
        }
    }

    @Nested
    class Delete {

        @Test
        void delete_shouldReturn204_whenAllReferenceNumbersExist() throws Exception {
            // Given
            List<String> referenceNumbers = List.of(REF_1, REF_2);
            doNothing().when(notificationService).deleteByReferenceNumbers(eq(referenceNumbers), any(AuditContext.class));

            // When & Then
            mockMvc.perform(delete("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                    .header(HEADER_TRACE_ID, "trace-abc")
                    .header(HEADER_USER_ID, "user-123")
                    .content(objectMapper.writeValueAsString(referenceNumbers)))
                .andExpect(status().isNoContent());

            verify(notificationService).deleteByReferenceNumbers(referenceNumbers, new AuditContext("trace-abc", "user-123"));
        }

        @Test
        void delete_shouldReturn404_whenReferenceNumberNotFound() throws Exception {
            // Given
            List<String> referenceNumbers = List.of(NONEXISTENT_REF);
            doThrow(new NotFoundException(
                "Cannot find notifications with reference numbers: " + NONEXISTENT_REF))
                .when(notificationService).deleteByReferenceNumbers(eq(referenceNumbers), any(AuditContext.class));

            // When & Then — also validates that NotFoundException resolves to 404 (not 500)
            // through the full Spring dispatch chain (GlobalExceptionHandler handler priority check)
            mockMvc.perform(delete("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                    .header(HEADER_TRACE_ID, "trace-abc")
                    .header(HEADER_USER_ID, "user-123")
                    .content(objectMapper.writeValueAsString(referenceNumbers)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                    "Cannot find notifications with reference numbers: " + NONEXISTENT_REF));
        }

        @Test
        void delete_shouldReturn400_whenListIsEmpty() throws Exception {
            // When & Then
            mockMvc.perform(delete("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                    .header(HEADER_TRACE_ID, "trace-abc")
                    .header(HEADER_USER_ID, "user-123")
                    .content("[]"))
                .andExpect(status().isBadRequest());

            verify(notificationService, never()).deleteByReferenceNumbers(any(), any());
        }

        @Test
        void delete_shouldReturn400_whenTraceIdHeaderIsMissing() throws Exception {
            // Given — x-cdp-request-id absent; Spring rejects with 400 before service is called
            List<String> referenceNumbers = List.of(REF_1);

            // When & Then
            mockMvc.perform(delete("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                    .header(HEADER_USER_ID, "user-123")
                    .content(objectMapper.writeValueAsString(referenceNumbers)))
                .andExpect(status().isBadRequest());

            verify(notificationService, never()).deleteByReferenceNumbers(any(), any());
        }

        @Test
        void delete_shouldReturn400_whenUserIdHeaderIsMissing() throws Exception {
            // Given — User-Id absent; Spring rejects with 400 before service is called
            List<String> referenceNumbers = List.of(REF_1);

            // When & Then
            mockMvc.perform(delete("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                    .header(HEADER_TRACE_ID, "trace-abc")
                    .content(objectMapper.writeValueAsString(referenceNumbers)))
                .andExpect(status().isBadRequest());

            verify(notificationService, never()).deleteByReferenceNumbers(any(), any());
        }
    }

    @Nested
    class FindFulfilments {

        @Test
        void findFulfilments_shouldReturn200WithProjection() throws Exception {
            // Given — a hand-rolled fulfilment view (Mockito mocks trip up Jackson via their
            // bytecode fields; the real Spring Data proxy serializes cleanly in production and E2E).
            NotificationFulfilmentsView view = new NotificationFulfilmentsView() {
                @Override public String getReferenceNumber() { return REF_1; }
                @Override public Long getConcurrencyToken() { return 0L; }
                @Override public NotificationStatus getStatus() { return NotificationStatus.SUBMITTED; }
                @Override public java.time.LocalDateTime getCreated() { return null; }
                @Override public java.time.LocalDateTime getSubmittedAt() { return null; }
                @Override public java.util.List<org.bson.Document> getFulfilments() {
                    return java.util.List.of(new org.bson.Document("obligationId", "abc"));
                }
                @Override public NotificationFulfilmentsView.FrozenParties
                    getSubmittedNotificationBaseline() {
                    return null;
                }
            };
            when(notificationService.findFulfilmentsView(REF_1)).thenReturn(view);

            // When / Then
            mockMvc.perform(get("/notifications/{referenceNumber}/fulfilments", REF_1)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNumber").value(REF_1))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.fulfilments[0].obligationId").value("abc"));
        }

        @Test
        void findFulfilments_shouldReturn404_whenReferenceNumberUnknown() throws Exception {
            // Given
            when(notificationService.findFulfilmentsView(NONEXISTENT_REF))
                .thenThrow(new NotFoundException(
                    "Cannot find notification with reference number: " + NONEXISTENT_REF));

            // When / Then
            mockMvc.perform(get("/notifications/{referenceNumber}/fulfilments", NONEXISTENT_REF)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                    "Cannot find notification with reference number: " + NONEXISTENT_REF));
        }
    }

    @Nested
    class FindAllReferenceNumbers {

        @Test
        void findAllReferenceNumbers_shouldReturnEmptyPage() throws Exception {
            // Given
            when(notificationService.findAllReferenceNumbers(0)).thenReturn(
                new ReferenceNumberPageResponse(Collections.emptyList(), 0, 25, 0, 0, 0));

            // When & Then
            mockMvc.perform(get("/notifications/reference-numbers")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.numberOfElements").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
        }

        @Test
        void findAllReferenceNumbers_shouldReturnPageOfReferenceNumbers() throws Exception {
            // Given
            when(notificationService.findAllReferenceNumbers(0)).thenReturn(
                new ReferenceNumberPageResponse(List.of(REF_1, REF_2), 0, 25, 2, 2, 1));

            // When & Then
            mockMvc.perform(get("/notifications/reference-numbers")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0]").value(REF_1))
                .andExpect(jsonPath("$.content[1]").value(REF_2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));
        }

        @Test
        void findAllReferenceNumbers_shouldPassPageParam() throws Exception {
            // Given
            when(notificationService.findAllReferenceNumbers(2)).thenReturn(
                new ReferenceNumberPageResponse(Collections.emptyList(), 2, 25, 0, 120, 5));

            // When & Then
            mockMvc.perform(get("/notifications/reference-numbers")
                    .param("page", "2")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(120))
                .andExpect(jsonPath("$.totalPages").value(5));
        }
    }

    @Nested
    class GetOutboxEvents {

        @Test
        void getOutboxEvents_shouldReturnEventsForReferenceNumber() throws Exception {
            // Given
            String referenceNumber = "GBN-AG-26-ABC123";
            List<OutboxEvent> events = List.of(
                OutboxEvent.builder().aggregateVersion(1L)
                    .eventType("uk.gov.defra.imports.notification.NotificationSubmitted").build(),
                OutboxEvent.builder().aggregateVersion(2L)
                    .eventType("uk.gov.defra.imports.notification.NotificationSubmitted").build()
            );
            when(outboxService.findByReferenceNumber(referenceNumber)).thenReturn(events);

            // When & Then
            mockMvc.perform(get("/notifications/{ref}/outbox-events", referenceNumber)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].aggregateVersion").value(1))
                .andExpect(jsonPath("$[1].aggregateVersion").value(2));
        }

        @Test
        void getOutboxEvents_shouldReturnEmptyList_whenNoEventsExist() throws Exception {
            // Given
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(outboxService.findByReferenceNumber(referenceNumber)).thenReturn(List.of());

            // When & Then
            mockMvc.perform(get("/notifications/{ref}/outbox-events", referenceNumber)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
        }
    }

    @Nested
    class SoftDelete {

        @Test
        void softDelete_shouldReturn200WithDeletedNotification() throws Exception {
            // Given
            NotificationAggregate deleted = new NotificationAggregate();
            deleted.setId("notif-id-001");
            deleted.setReferenceNumber(REF_1);
            deleted.setStatus(NotificationStatus.DELETED);

            when(notificationService.softDeleteNotification(eq(REF_1), any(), any())).thenReturn(deleted);

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/soft-delete", REF_1)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNumber").value(REF_1))
                .andExpect(jsonPath("$.status").value("DELETED"));
        }

        @Test
        void softDelete_shouldReturn404_whenReferenceNumberUnknown() throws Exception {
            // Given
            when(notificationService.softDeleteNotification(eq(NONEXISTENT_REF), any(), any()))
                .thenThrow(new NotFoundException(
                    "Cannot find notification with reference number: " + NONEXISTENT_REF));

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/soft-delete", NONEXISTENT_REF)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                    "Cannot find notification with reference number: " + NONEXISTENT_REF));
        }

        @Test
        void softDelete_shouldReturn400_whenNotificationNotInDeletableState() throws Exception {
            // Given
            when(notificationService.softDeleteNotification(eq(REF_1), any(), any()))
                .thenThrow(new BadRequestException("Cannot delete notification with status: DELETED"));

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/soft-delete", REF_1)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                    "Cannot delete notification with status: DELETED"));
        }

        @Test
        void softDelete_shouldPassTraceIdAsCorrelationId() throws Exception {
            NotificationAggregate deleted = new NotificationAggregate();
            deleted.setReferenceNumber(REF_1);
            deleted.setStatus(NotificationStatus.DELETED);
            when(notificationService.softDeleteNotification(REF_1, "trace-delete-001", null))
                .thenReturn(deleted);

            mockMvc.perform(post("/notifications/{referenceNumber}/soft-delete", REF_1)
                    .header(HEADER_TRACE_ID, "trace-delete-001")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            verify(notificationService).softDeleteNotification(REF_1, "trace-delete-001", null);
        }

        @Test
        void softDelete_shouldPassActorToService_whenActorBodyProvided() throws Exception {
            NotificationAggregate deleted = new NotificationAggregate();
            deleted.setReferenceNumber(REF_1);
            deleted.setStatus(NotificationStatus.DELETED);
            when(notificationService.softDeleteNotification(eq(REF_1), any(), any()))
                .thenReturn(deleted);

            String actorBody = """
                {
                    "id": "contact-guid-001",
                    "source": "dynamics-contact",
                    "userType": "B2C",
                    "displayName": "Jane Farmer",
                    "organisationId": "org-001"
                }
                """;

            mockMvc.perform(post("/notifications/{referenceNumber}/soft-delete", REF_1)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(actorBody))
                .andExpect(status().isOk());

            verify(notificationService).softDeleteNotification(
                eq(REF_1), anyString(),
                argThat(a -> a != null && "org-001".equals(a.getOrganisationId())));
        }
    }

    @Nested
    class ReplayOutboxEvents {

        @Test
        void replay_shouldReturn200WithEventCount_whenEventsExist() throws Exception {
            when(outboxReplayService.replay(eq(REF_1), any(AuditContext.class))).thenReturn(2);

            mockMvc.perform(post("/notifications/{referenceNumber}/replay", REF_1)
                    .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                    .header(HEADER_TRACE_ID, "trace-abc")
                    .header(HEADER_USER_ID, "user-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventsReplayed").value(2));

            verify(outboxReplayService).replay(REF_1, new AuditContext("trace-abc", "user-123"));
        }

        @Test
        void replay_shouldReturn404_whenNoEventsFound() throws Exception {
            when(outboxReplayService.replay(eq(REF_1), any(AuditContext.class)))
                .thenThrow(new NotFoundException("No outbox events found for: " + REF_1));

            mockMvc.perform(post("/notifications/{referenceNumber}/replay", REF_1)
                    .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                    .header(HEADER_TRACE_ID, "trace-abc")
                    .header(HEADER_USER_ID, "user-123"))
                .andExpect(status().isNotFound());
        }

        @Test
        void replay_shouldReturn401_whenAdminSecretIsMissing() throws Exception {
            mockMvc.perform(post("/notifications/{referenceNumber}/replay", REF_1)
                    .header(HEADER_TRACE_ID, "trace-abc")
                    .header(HEADER_USER_ID, "user-123"))
                .andExpect(status().isUnauthorized());

            verify(outboxReplayService, never()).replay(any(), any());
        }

        @Test
        void replay_shouldReturn400_whenUserIdHeaderIsMissing() throws Exception {
            mockMvc.perform(post("/notifications/{referenceNumber}/replay", REF_1)
                    .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                    .header(HEADER_TRACE_ID, "trace-abc"))
                .andExpect(status().isBadRequest());

            verify(outboxReplayService, never()).replay(any(), any());
        }
    }
}
