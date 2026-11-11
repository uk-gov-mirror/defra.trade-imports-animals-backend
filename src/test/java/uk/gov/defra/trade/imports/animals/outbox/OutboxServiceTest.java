package uk.gov.defra.trade.imports.animals.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;
import uk.gov.defra.trade.imports.animals.exceptions.OutboxWriteException;
import uk.gov.defra.trade.imports.animals.notification.AdditionalDetails;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.Transport;
import uk.gov.defra.trade.imports.animals.outbox.gbnag.GbnAgEventDataMapper;
import uk.gov.defra.trade.imports.animals.utils.NotificationTestData;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private AccompanyingDocumentRepository accompanyingDocumentRepository;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        outboxService = new OutboxService(
            outboxEventRepository, objectMapper, new GbnAgEventDataMapper(), accompanyingDocumentRepository);
    }

    private NotificationAggregate aggregateFor(String referenceNumber, NotificationStatus status) {
        return aggregateFor(referenceNumber, status, Notification.builder().build());
    }

    private NotificationAggregate aggregateFor(
        String referenceNumber, NotificationStatus status, Notification notification) {
        return NotificationAggregate.builder()
            .referenceNumber(referenceNumber)
            .status(status)
            .organisationId("5900001")
            .notification(notification)
            .build();
    }

    private void stubNoPriorEvents(String referenceNumber) {
        when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
            OutboxService.buildAggregateId(referenceNumber)))
            .thenReturn(Optional.empty());
    }

    private void stubLatestPriorEvent(OutboxEvent latestEvent) {
        when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
            latestEvent.getAggregateId()))
            .thenReturn(Optional.of(latestEvent));
    }

    private void stubSaveEchoesItsArgument() {
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private OutboxEvent savedEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    class AppendEvent {

        @Test
        void appendEvent_shouldWriteEventWithVersionOne_whenNoExistingEvents() {
            NotificationAggregate notificationAggregate =
                aggregateFor("GBN-AG-26-ABC123", NotificationStatus.SUBMITTED);
            stubNoPriorEvents("GBN-AG-26-ABC123");
            stubSaveEchoesItsArgument();

            outboxService.appendEvent(notificationAggregate, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-001", null);

            OutboxEvent saved = savedEvent();
            assertThat(saved.getAggregateVersion()).isEqualTo(1L);
            assertThat(saved.getAggregateId()).isEqualTo("Imports.Notification.GBN-AG.GBN-AG-26-ABC123");
            assertThat(saved.getAggregateType()).isEqualTo("Notification");
            assertThat(saved.getSubType()).isEqualTo("GBN-AG");
            assertThat(saved.getEventType()).isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
            assertThat(saved.getMetadata().getCorrelationId()).isEqualTo("trace-001");
            assertThat(saved.getMetadata().getSchemaVersion()).isEqualTo("1");
            assertThat(saved.getMetadata().getSchemaUrl()).isEqualTo(OutboxEventType.NOTIFICATION_SUBMITTED.schemaUrl());
            assertThat(saved.getEventId()).isNotNull();
            assertThat(saved.getTimestamp()).isNotNull();
            assertThat(saved.getActor()).isNull();
            assertThat(saved.getStatusChanges()).hasSize(1);
            assertThat(saved.getStatusChanges().getFirst().getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
            assertThat(saved.getStatusChanges().getFirst().getActor()).isNull();
        }

        @Test
        void appendEvent_shouldIncrementVersion_whenPriorEventsExist() {
            NotificationAggregate notificationAggregate =
                aggregateFor("GBN-AG-26-ABC123", NotificationStatus.SUBMITTED);
            stubLatestPriorEvent(OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-ABC123")
                .aggregateVersion(3L)
                .build());
            stubSaveEchoesItsArgument();

            outboxService.appendEvent(notificationAggregate, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-002", null);

            assertThat(savedEvent().getAggregateVersion()).isEqualTo(4L);
        }

        @Test
        @SuppressWarnings("unchecked")
        void appendEvent_shouldStoreGbnAgPayloadInDataField() {
            Origin origin = Origin.builder()
                .countryCode("GB")
                .requiresRegionCode("true")
                .internalReference("REF123")
                .build();
            List<Commodity> commodities = List.of(
                Commodity.builder().name("Live bovine animals").build());
            AdditionalDetails additionalDetails = new AdditionalDetails("HUMAN_CONSUMPTION", "true");
            Transport transport = Transport.builder()
                .portOfEntry("GBFXT")
                .arrivalDate(LocalDate.parse("2026-04-22"))
                .build();

            NotificationAggregate notificationAggregate = aggregateFor(
                "GBN-AG-26-ABC123",
                NotificationStatus.SUBMITTED,
                Notification.builder()
                    .origin(origin)
                    .commodities(commodities)
                    .reasonForImport("PERMANENT")
                    .additionalDetails(additionalDetails)
                    .cphNumber("12/345/6789")
                    .transport(transport)
                    .consignor(NotificationTestData.consignors().getFirst())
                    .destination(NotificationTestData.destinations().getFirst())
                    .build());
            stubNoPriorEvents("GBN-AG-26-ABC123");
            stubSaveEchoesItsArgument();

            outboxService.appendEvent(notificationAggregate, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-001", null);

            Map<String, Object> data = savedEvent().getData();
            assertThat(data)
                .containsKeys("$model", "$type", "exchangedDocument", "specifiedConsignment")
                .containsEntry("$type", "gbn-ag")
                .doesNotContainKey("referenceNumber");

            Map<String, Object> exchangedDocument = (Map<String, Object>) data.get("exchangedDocument");
            assertThat(exchangedDocument)
                .containsEntry("identifier", "GBN-AG-26-ABC123")
                .containsEntry("notificationStatusCode", "SUBMITTED")
                .doesNotContainKey("referenceDocument");

            Map<String, Object> specifiedConsignment =
                (Map<String, Object>) data.get("specifiedConsignment");
            assertThat(specifiedConsignment).containsKeys("consignorParty", "deliveryParty");
        }

        @Test
        @SuppressWarnings("unchecked")
        void appendEvent_shouldReferenceOnlyTheScannedDocumentsTheJourneyClaimed() {
            NotificationAggregate notificationAggregate =
                aggregateFor("GBN-AG-26-ABC123", NotificationStatus.SUBMITTED);
            stubNoPriorEvents("GBN-AG-26-ABC123");
            when(accompanyingDocumentRepository
                .findAllByNotificationReferenceNumberAndScanStatusAndClaimedIsTrueOrderByCreatedAsc(
                    "GBN-AG-26-ABC123", ScanStatus.COMPLETE))
                .thenReturn(List.of(AccompanyingDocument.builder()
                    .documentType(DocumentType.VETERINARY_HEALTH_CERTIFICATE)
                    .documentReference("GBHC1234567890")
                    .dateOfIssue(LocalDate.parse("2026-09-10"))
                    .scanStatus(ScanStatus.COMPLETE)
                    .claimed(true)
                    .build()));
            stubSaveEchoesItsArgument();

            outboxService.appendEvent(notificationAggregate, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-001", null);

            Map<String, Object> exchangedDocument =
                (Map<String, Object>) savedEvent().getData().get("exchangedDocument");
            assertThat((List<Map<String, Object>>) exchangedDocument.get("referenceDocument"))
                .containsExactly(Map.of(
                    "typeCode", "853",
                    "urlId", "https://refdata.tbc.defra.gov.uk/gbn-ag-document-types",
                    "identifier", "GBHC1234567890",
                    "issueDateTime", "2026-09-10"));
        }

        @Test
        void appendEvent_shouldStoreEventTypeFromArgument_whenAmendType() {
            NotificationAggregate notificationAggregate =
                aggregateFor("GBN-AG-26-AMD009", NotificationStatus.AMEND);
            stubNoPriorEvents("GBN-AG-26-AMD009");
            stubSaveEchoesItsArgument();

            outboxService.appendEvent(
                notificationAggregate, OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED, "trace-amd-9", null);

            assertThat(savedEvent().getEventType())
                .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmissionAmended");
        }

        @Test
        void appendEvent_shouldIncrementFromHighestVersion_whenPriorEventsExistForAmendedNotification() {
            NotificationAggregate notificationAggregate =
                aggregateFor("GBN-AG-26-AMD007", NotificationStatus.SUBMITTED);
            stubLatestPriorEvent(OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-AMD007")
                .aggregateVersion(2L)
                .build());
            stubSaveEchoesItsArgument();

            outboxService.appendEvent(notificationAggregate, OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED, "trace-amd-7", null);

            assertThat(savedEvent().getAggregateVersion()).isEqualTo(3L);
        }

        @Test
        void appendEvent_shouldStampOwningOrganisation_fromAggregate() {
            NotificationAggregate notificationAggregate =
                aggregateFor("GBN-AG-26-ORG001", NotificationStatus.SUBMITTED);
            stubNoPriorEvents("GBN-AG-26-ORG001");
            stubSaveEchoesItsArgument();

            outboxService.appendEvent(
                notificationAggregate, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-org-1", null);

            assertThat(savedEvent().getOrganisationId()).isEqualTo("5900001");
        }

        @Test
        void appendEvent_shouldStampActorOnEvent_whenActorProvided() {
            NotificationAggregate notificationAggregate =
                aggregateFor("GBN-AG-26-ACT001", NotificationStatus.SUBMITTED);
            Actor actor = Actor.builder()
                .id("contact-guid-001")
                .source("dynamics-contact")
                .userType("B2C")
                .displayName("Jane Farmer")
                .organisationId("org-001")
                .build();
            stubNoPriorEvents("GBN-AG-26-ACT001");
            stubSaveEchoesItsArgument();

            outboxService.appendEvent(notificationAggregate, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-act-1", actor);

            OutboxEvent saved = savedEvent();
            assertThat(saved.getActor()).isEqualTo(actor);
            assertThat(saved.getStatusChanges()).hasSize(1);
            StatusChange change = saved.getStatusChanges().getFirst();
            assertThat(change.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
            assertThat(change.getDateChanged()).isNotNull();
            assertThat(change.getActor()).isEqualTo(actor);
        }

        @Test
        void appendEvent_shouldBuildCumulativeStatusChanges_fromLatestPriorEvent() {
            NotificationAggregate notificationAggregate =
                aggregateFor("GBN-AG-26-ACT002", NotificationStatus.AMEND);
            Actor submitActor = Actor.builder()
                .id("contact-guid-001")
                .source("dynamics-contact")
                .userType("B2C")
                .displayName("Jane Farmer")
                .organisationId("org-001")
                .build();
            Actor amendActor = Actor.builder()
                .id("contact-guid-002")
                .source("dynamics-contact")
                .userType("B2C")
                .displayName("John Agent")
                .organisationId("org-002")
                .build();
            StatusChange priorChange = StatusChange.builder()
                .status(NotificationStatus.SUBMITTED)
                .dateChanged(Instant.parse("2026-01-01T10:00:00Z"))
                .actor(submitActor)
                .build();
            stubLatestPriorEvent(OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-ACT002")
                .aggregateVersion(1L)
                .statusChanges(List.of(priorChange))
                .build());
            stubSaveEchoesItsArgument();

            outboxService.appendEvent(
                notificationAggregate, OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED, "trace-act-2", amendActor);

            OutboxEvent saved = savedEvent();
            assertThat(saved.getStatusChanges()).hasSize(2);
            assertThat(saved.getStatusChanges().get(0).getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
            assertThat(saved.getStatusChanges().get(0).getActor()).isEqualTo(submitActor);
            assertThat(saved.getStatusChanges().get(1).getStatus()).isEqualTo(NotificationStatus.AMEND);
            assertThat(saved.getStatusChanges().get(1).getActor()).isEqualTo(amendActor);
        }

        @Test
        void appendEvent_shouldAppendDraftStatusChange_forFirstPageSave() {
            NotificationAggregate notificationAggregate =
                aggregateFor("GBN-AG-26-EDIT01", NotificationStatus.DRAFT);
            stubNoPriorEvents("GBN-AG-26-EDIT01");
            stubSaveEchoesItsArgument();

            outboxService.appendEvent(
                notificationAggregate, OutboxEventType.NOTIFICATION_EDITED, "trace-edit-1", null);

            OutboxEvent saved = savedEvent();
            assertThat(saved.getStatusChanges()).hasSize(1);
            assertThat(saved.getStatusChanges().getFirst().getStatus()).isEqualTo(NotificationStatus.DRAFT);
        }

        @Test
        void appendEvent_shouldNotDuplicateStatusChange_forConsecutivePageSavesWithSameStatus() {
            NotificationAggregate notificationAggregate =
                aggregateFor("GBN-AG-26-EDIT02", NotificationStatus.DRAFT);
            StatusChange priorChange = StatusChange.builder()
                .status(NotificationStatus.DRAFT)
                .dateChanged(Instant.parse("2026-01-01T10:00:00Z"))
                .actor(null)
                .build();
            stubLatestPriorEvent(OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-EDIT02")
                .aggregateVersion(1L)
                .statusChanges(List.of(priorChange))
                .build());
            stubSaveEchoesItsArgument();

            outboxService.appendEvent(
                notificationAggregate, OutboxEventType.NOTIFICATION_EDITED, "trace-edit-2", null);

            OutboxEvent saved = savedEvent();
            assertThat(saved.getStatusChanges()).hasSize(1);
            assertThat(saved.getStatusChanges().getFirst().getStatus()).isEqualTo(NotificationStatus.DRAFT);
        }

        @Test
        void appendEvent_shouldNotDuplicateStatusChange_forAmendPhasePageSave() {
            NotificationAggregate notificationAggregate =
                aggregateFor("GBN-AG-26-EDIT03", NotificationStatus.AMEND);
            StatusChange priorChange = StatusChange.builder()
                .status(NotificationStatus.AMEND)
                .dateChanged(Instant.parse("2026-01-01T11:00:00Z"))
                .actor(null)
                .build();
            stubLatestPriorEvent(OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-EDIT03")
                .aggregateVersion(2L)
                .statusChanges(List.of(priorChange))
                .build());
            stubSaveEchoesItsArgument();

            outboxService.appendEvent(
                notificationAggregate, OutboxEventType.NOTIFICATION_EDITED, "trace-edit-3", null);

            OutboxEvent saved = savedEvent();
            assertThat(saved.getStatusChanges()).hasSize(1);
            assertThat(saved.getStatusChanges().getFirst().getStatus()).isEqualTo(NotificationStatus.AMEND);
        }

        @Test
        void appendEvent_shouldThrowOutboxWriteException_onDuplicateKey() {
            NotificationAggregate notificationAggregate =
                aggregateFor("GBN-AG-26-ABC123", NotificationStatus.SUBMITTED);
            stubNoPriorEvents("GBN-AG-26-ABC123");
            when(outboxEventRepository.save(any()))
                .thenThrow(new DuplicateKeyException("duplicate key"));

            assertThatThrownBy(() -> outboxService.appendEvent(notificationAggregate, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-001", null))
                .isInstanceOf(OutboxWriteException.class)
                .satisfies(ex -> {
                    OutboxWriteException owe = (OutboxWriteException) ex;
                    assertThat(owe.getAggregateId())
                        .isEqualTo("Imports.Notification.GBN-AG.GBN-AG-26-ABC123");
                    assertThat(owe.getAggregateVersion()).isEqualTo(1L);
                    assertThat(owe.getCorrelationId()).isEqualTo("trace-001");
                });
        }
    }

    @Nested
    @SuppressWarnings("unchecked")
    class VersionId {

        private static final String AGG_ID = "Imports.Notification.GBN-AG.GBN-AG-26-VID001";
        private static final String REF = "GBN-AG-26-VID001";

        private void stubSubmissionCount(long count) {
            when(outboxEventRepository.countByAggregateIdAndEventTypeIn(eq(AGG_ID), any()))
                .thenReturn(count);
        }

        private Map<String, Object> exchangedDocumentFor(OutboxEventType eventType, NotificationStatus status) {
            NotificationAggregate aggregate = aggregateFor(REF, status);
            stubNoPriorEvents(REF);
            stubSaveEchoesItsArgument();

            outboxService.appendEvent(aggregate, eventType, "trace", null);

            return (Map<String, Object>) savedEvent().getData().get("exchangedDocument");
        }

        @Test
        void versionId_shouldBeAbsent_forNotificationCreated() {
            Map<String, Object> doc = exchangedDocumentFor(OutboxEventType.NOTIFICATION_CREATED, NotificationStatus.DRAFT);
            assertThat(doc).doesNotContainKey("versionId");
        }

        @Test
        void versionId_shouldBeAbsent_forNotificationDeleted() {
            Map<String, Object> doc = exchangedDocumentFor(OutboxEventType.NOTIFICATION_DELETED, NotificationStatus.DELETED);
            assertThat(doc).doesNotContainKey("versionId");
        }

        @Test
        void versionId_shouldBeOne_forFirstNotificationSubmitted() {
            stubSubmissionCount(0);
            Map<String, Object> doc = exchangedDocumentFor(OutboxEventType.NOTIFICATION_SUBMITTED, NotificationStatus.SUBMITTED);
            assertThat(doc.get("versionId")).isEqualTo(1);
        }

        @Test
        void versionId_shouldIncrementForNotificationSubmissionAmended() {
            stubSubmissionCount(1);
            Map<String, Object> doc = exchangedDocumentFor(OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED, NotificationStatus.SUBMITTED);
            assertThat(doc.get("versionId")).isEqualTo(2);
        }

        @Test
        void versionId_shouldCarryForward_forNotificationAmendmentRequested() {
            stubSubmissionCount(1);
            Map<String, Object> doc = exchangedDocumentFor(OutboxEventType.NOTIFICATION_AMENDMENT_REQUESTED, NotificationStatus.AMEND);
            assertThat(doc.get("versionId")).isEqualTo(1);
        }

        @Test
        void versionId_shouldCarryForward_forNotificationAmendmentCancelled() {
            stubSubmissionCount(1);
            Map<String, Object> doc = exchangedDocumentFor(OutboxEventType.NOTIFICATION_AMENDMENT_CANCELLED, NotificationStatus.SUBMITTED);
            assertThat(doc.get("versionId")).isEqualTo(1);
        }

        @Test
        void versionId_shouldCarryForward_forNotificationSubmissionDeleted() {
            stubSubmissionCount(2);
            Map<String, Object> doc = exchangedDocumentFor(OutboxEventType.NOTIFICATION_SUBMISSION_DELETED, NotificationStatus.DELETED);
            assertThat(doc.get("versionId")).isEqualTo(2);
        }
    }

    @Nested
    class FindByReferenceNumber {

        @Test
        void findByReferenceNumber_shouldReturnEventsInAggregateVersionOrder() {
            OutboxEvent v1 = OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-ABC123")
                .aggregateVersion(1L)
                .build();
            OutboxEvent v2 = OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-ABC123")
                .aggregateVersion(2L)
                .build();

            when(outboxEventRepository.findAllByAggregateIdOrderByAggregateVersionAsc(
                "Imports.Notification.GBN-AG.GBN-AG-26-ABC123"))
                .thenReturn(List.of(v1, v2));

            List<OutboxEvent> result = outboxService.findByReferenceNumber("GBN-AG-26-ABC123");

            assertThat(result).containsExactly(v1, v2);
        }

        @Test
        void findByReferenceNumber_shouldReturnEmptyList_whenNoEventsExist() {
            when(outboxEventRepository.findAllByAggregateIdOrderByAggregateVersionAsc(
                "Imports.Notification.GBN-AG.GBN-AG-26-ABSENT"))
                .thenReturn(List.of());

            List<OutboxEvent> result = outboxService.findByReferenceNumber("GBN-AG-26-ABSENT");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class BuildAggregateId {

        @Test
        void buildAggregateId_shouldPrefixReferenceNumber() {
            assertThat(OutboxService.buildAggregateId("GBN-AG-26-ABC123"))
                .isEqualTo("Imports.Notification.GBN-AG.GBN-AG-26-ABC123");
        }
    }
}
