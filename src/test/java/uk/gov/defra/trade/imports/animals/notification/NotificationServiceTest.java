package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.defra.trade.imports.animals.notification.NotificationStatus.AMEND;
import static uk.gov.defra.trade.imports.animals.notification.NotificationStatus.DELETED;
import static uk.gov.defra.trade.imports.animals.notification.NotificationStatus.DRAFT;
import static uk.gov.defra.trade.imports.animals.notification.NotificationStatus.SUBMITTED;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignments;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignors;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.destinations;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.species;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.transporters;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort.Direction;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentService;
import uk.gov.defra.trade.imports.animals.addressbook.AddressBookClient;
import uk.gov.defra.trade.imports.animals.addressbook.AddressBookRecord;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.configuration.NotificationTtlConfig;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.exceptions.OutboxWriteException;
import uk.gov.defra.trade.imports.animals.outbox.Actor;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventType;
import uk.gov.defra.trade.imports.animals.outbox.OutboxService;
import uk.gov.defra.trade.imports.animals.utils.NotificationTestData;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String TEST_TRACE_ID = "test-trace-id";
    private static final String TEST_USER_ID = "test-user-id";
    /** The submitting actor's organisation, whose address book the outbox resolve reads. */
    private static final String ORG_ID = "5900002";

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private AuditRepository auditRepository;

    @Mock
    private DocumentService documentService;

    @Mock
    private OutboxService outboxService;

    @Mock
    private LockProvider lockProvider;

    @Mock
    private ReferenceNumberGenerator referenceNumberGenerator;

    @Mock
    private AddressBookClient addressBookClient;

    private NotificationService notificationService;

    // Real executor (its executeWithLock must actually run the locked task) built from the
    // lockProvider mock; @InjectMocks does the wiring so we don't hand-construct it from a mock.
    @InjectMocks
    private DefaultLockingTaskExecutor lockingTaskExecutor;

    @BeforeEach
    void setUp() {
        // Default: TTL unconfigured (days null) so create tests keep their original behaviour and
        // stamp no expireAt. Expiry-specific tests rebuild the service with a bespoke config.
        notificationService = buildService(new NotificationTtlConfig(null, "local", sweep(false)));
    }

    private static AddressBookRecord addressBookRecord(String addressId, boolean deleted) {
        return new AddressBookRecord(addressId, "Astra Rosales", "43 East Hague Extension", null,
            "Vernier", "Soleure", "30055", "CH", "+41 22 000 0000", "astra@example.com", deleted);
    }

    private NotificationService buildService(NotificationTtlConfig ttlConfig) {
        return new NotificationService(notificationRepository, auditRepository,
            documentService, outboxService, lockingTaskExecutor,
            new NotificationCopyMapper(),
            Mappers.getMapper(NotificationContentMapper.class),
            new ConsignmentPartyResolver(addressBookClient),
            referenceNumberGenerator, ttlConfig,
            Duration.ZERO, 54, 50);
    }

    private static NotificationTtlConfig.Sweep sweep(boolean enabled) {
        return new NotificationTtlConfig.Sweep(
            enabled, 3_600_000, 10, Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    @Nested
    class SaveNotification {

        @BeforeEach
        void setUp() {
            lenient().when(lockProvider.lock(any()))
                .thenReturn(Optional.of(mock(SimpleLock.class)));
        }

        @Test
        void saveNotification_shouldCreateNotificationWithGeneratedReferenceNumber() {
            // Given - new notification without referenceNumber
            Origin origin = new Origin("GB", "true", "REF123");
            NotificationDto notificationDto = NotificationDto.builder()
                .origin(origin)
                .build();

            String generatedId = "507f1f77bcf86cd799439011";
            String expectedRef = "GBN-AG-26-ABC123";

            NotificationAggregate saved = new NotificationAggregate();
            saved.setNotification(new Notification());
            saved.setId(generatedId);
            saved.getNotification().setOrigin(origin);
            saved.setReferenceNumber(expectedRef);

            when(referenceNumberGenerator.generate()).thenReturn(expectedRef);
            when(notificationRepository.save(any(NotificationAggregate.class))).thenReturn(saved);

            // When
            NotificationAggregate result = notificationService.saveNotification(notificationDto, "", null);

            // Then
            assertThat(result.getReferenceNumber()).isEqualTo(expectedRef);
            assertThat(result.getId()).isEqualTo(generatedId);
            assertThat(result.getNotification().getOrigin()).isEqualTo(origin);
            verify(referenceNumberGenerator).generate();
            verify(notificationRepository, times(1)).save(any(NotificationAggregate.class));
            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_CREATED), any(), any());
        }

        @Test
        void saveNotification_shouldRetryPersistence_whenDuplicateKeyExceptionOnFirstAttempt() {
            // Given — first persistence attempt collides, second succeeds
            Origin origin = new Origin("GB", "true", "REF123");
            NotificationDto notificationDto = NotificationDto.builder().origin(origin).build();

            NotificationAggregate saved = new NotificationAggregate();
            saved.setNotification(new Notification());
            saved.setId("507f1f77bcf86cd799439011");
            saved.getNotification().setOrigin(origin);
            saved.setReferenceNumber("GBN-AG-26-ABC002");

            when(referenceNumberGenerator.generate())
                .thenReturn("GBN-AG-26-ABC001")  // first attempt — collides
                .thenReturn("GBN-AG-26-ABC002"); // second attempt — succeeds

            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenThrow(new DuplicateKeyException("duplicate reference number"))
                .thenReturn(saved);

            // When
            NotificationAggregate result = notificationService.saveNotification(notificationDto, "", null);

            // Then
            assertThat(result.getReferenceNumber()).isEqualTo("GBN-AG-26-ABC002");
            verify(referenceNumberGenerator, times(2)).generate();
            verify(notificationRepository, times(2)).save(any(NotificationAggregate.class));
        }

        @Test
        void saveNotification_shouldThrowIllegalStateException_whenAllPersistenceRetriesExhausted() {
            // Given — all three persistence attempts collide
            Origin origin = new Origin("GB", "true", "REF123");
            NotificationDto notificationDto = NotificationDto.builder().origin(origin).build();

            when(referenceNumberGenerator.generate()).thenReturn("GBN-AG-26-ABC001");
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenThrow(new DuplicateKeyException("duplicate reference number"))
                .thenThrow(new DuplicateKeyException("duplicate reference number"))
                .thenThrow(new DuplicateKeyException("duplicate reference number"));

            // When / Then
            assertThatThrownBy(() -> notificationService.saveNotification(notificationDto, "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3");

            verify(referenceNumberGenerator, times(3)).generate();
            verify(notificationRepository, times(3)).save(any(NotificationAggregate.class));
        }

        @Test
        void saveNotification_shouldUpdateNotificationFieldsAndWriteEditedEvent_whenDraft() {
            // Given
            String existingId = "507f191e810c19729de860ea";
            String referenceNumber = "GBN-AG-26-507F19";
            Origin origin = new Origin("FR", "false", "REF456");
            AdditionalDetails additionalDetails = new AdditionalDetails("HUMAN_CONSUMPTION", "true");
            Species species = species();
            CommodityComplement complement = new CommodityComplement("LIVE", 5, null, List.of(species));
            Commodity commodity = Commodity.builder()
                .name("Fish")
                .commodityComplement(List.of(complement))
                .build();
            String cphNumber = "123456789";
            Transport transport = Transport.builder()
                .portOfEntry("ABERDEEN")
                .arrivalDate(LocalDate.of(2026, Month.JANUARY, 1))
                .transporter(transporters().getFirst())
                .build();

            NotificationAggregate existingNotification = new NotificationAggregate();
            existingNotification.setNotification(new Notification());
            existingNotification.setId(existingId);
            existingNotification.setReferenceNumber(referenceNumber);
            existingNotification.setStatus(DRAFT);
            existingNotification.getNotification().setOrigin(origin);

            NotificationAggregate updatedNotification = NotificationAggregate.builder()
                .id(existingId)
                .referenceNumber(referenceNumber)
                .concurrencyToken(0L)
                .status(DRAFT)
                .notification(Notification.builder()
                    .origin(origin)
                    .commodity(commodity)
                    .consignor(consignors().getFirst())
                    .destination(destinations().getFirst())
                    .additionalDetails(additionalDetails)
                    .reasonForImport("PERMANENT")
                    .cphNumber(cphNumber)
                    .transport(transport)
                    .consignment(consignments().getFirst())
                    .build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existingNotification));
            when(notificationRepository.save(any(NotificationAggregate.class))).thenReturn(updatedNotification);

            NotificationDto updateDto = NotificationDto.builder()
                .referenceNumber(referenceNumber)
                .concurrencyToken(0L)
                .origin(origin)
                .commodity(commodity)
                .consignor(consignors().getFirst())
                .destination(destinations().getFirst())
                .additionalDetails(additionalDetails)
                .reasonForImport("PERMANENT")
                .cphNumber(cphNumber)
                .transport(transport)
                .consignment(consignments().getFirst())
                .build();

            // When
            NotificationAggregate result = notificationService.saveNotification(updateDto, "trace-upd-001", null);

            // Then
            assertThat(result)
                .usingRecursiveComparison()
                .isEqualTo(updatedNotification);
            verify(notificationRepository, times(1)).save(any(NotificationAggregate.class));
            verify(outboxService).appendEvent(updatedNotification, OutboxEventType.NOTIFICATION_EDITED, "trace-upd-001", null);
        }

        @Test
        void saveNotification_shouldStillSave_whenAReferencedAddressHasBeenDeleted() {
            // Given — a draft whose consignor points at an address the trader has since deleted.
            // UCD's ruling is that a deleted address behaves as if it were never selected, so this
            // must not block them from saving the rest of the draft; only a submit has to be
            // complete.
            String addressId = "665f1c2ab3e4d51a2c9d0e77";
            String referenceNumber = "GBN-AG-26-EDIT01";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            when(addressBookClient.findById(ORG_ID, addressId))
                .thenReturn(Optional.of(addressBookRecord(addressId, true)));

            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(referenceNumber)
                .concurrencyToken(0L)
                .consignor(NotificationTestData.reference(addressId))
                .build();

            // When
            NotificationAggregate saved = notificationService.saveNotification(
                dto, "trace-edit-001", Actor.builder().organisationId(ORG_ID).build());

            // Then — the draft is saved, still holding the reference
            assertThat(saved.getNotification().getConsignor()).isEqualTo(ConsignmentParty.reference(addressId));

            // And the event carries the role blank rather than failing the write
            ArgumentCaptor<NotificationAggregate> captor = ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(outboxService).appendEvent(
                captor.capture(), eq(OutboxEventType.NOTIFICATION_EDITED), eq("trace-edit-001"),
                any());
            assertThat(captor.getValue().getNotification().getConsignor()).isNull();
        }

        @Test
        void saveNotification_shouldWriteEditedEvent_whenAmend() {
            // Given
            String referenceNumber = "GBN-AG-26-AMEND1";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(referenceNumber).concurrencyToken(0L).build();

            // When
            notificationService.saveNotification(dto, "trace-amd-001", null);

            // Then
            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_EDITED), eq("trace-amd-001"), eq(null));
        }

        @Test
        void saveNotification_shouldThrowBadRequest_whenStatusIsSubmitted() {
            // Given
            String referenceNumber = "GBN-AG-26-SUBM01";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));

            NotificationDto dto = NotificationDto.builder().referenceNumber(referenceNumber).build();

            // When / Then
            assertThatThrownBy(() -> notificationService.saveNotification(dto, "trace-001", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SUBMITTED");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }

        @Test
        void saveNotification_shouldThrowNotFound_whenRefUnknown() {
            // Given
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.empty());

            NotificationDto dto = NotificationDto.builder().referenceNumber(referenceNumber).build();

            // When / Then
            assertThatThrownBy(() -> notificationService.saveNotification(dto, "trace-001", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(referenceNumber);

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }

        @Test
        void saveNotification_shouldThrowBadRequest_whenConcurrencyTokenIsNullOnUpdate() {
            // Given — POST-with-referenceNumber routes through updateNotification, which now
            // requires the client to send the concurrencyToken they last saw. Symmetric with replace().
            String referenceNumber = "GBN-AG-26-NULLVU";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .concurrencyToken(3L)
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));

            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(referenceNumber)
                .origin(new Origin("GB", "no", "REF"))
                .build();

            // When / Then
            assertThatThrownBy(() -> notificationService.saveNotification(dto, "trace-nvu", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("concurrencyToken");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }

        @Test
        void saveNotification_shouldThrowOutboxWriteException_whenLockNotAcquired() {
            // Given
            String referenceNumber = "GBN-AG-26-LOCK01";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(NotificationAggregate.builder()
                    .referenceNumber(referenceNumber).status(DRAFT).build()));
            when(lockProvider.lock(any())).thenReturn(Optional.empty());

            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(referenceNumber).concurrencyToken(0L).build();

            // When / Then
            assertThatThrownBy(() -> notificationService.saveNotification(dto, "trace-001", null))
                .isInstanceOf(OutboxWriteException.class)
                .satisfies(ex -> {
                    OutboxWriteException owe = (OutboxWriteException) ex;
                    assertThat(owe.getAggregateId())
                        .isEqualTo("Imports.Notification.GBN-AG." + referenceNumber);
                    assertThat(owe.getCorrelationId()).isEqualTo("trace-001");
                });

            verify(lockProvider, times(3)).lock(any());
            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }

        @Test
        void saveNotification_shouldRetryAndSucceed_whenFirstLockAttemptFails() {
            // Given — first lock attempt fails, second succeeds
            String referenceNumber = "GBN-AG-26-RETRY1";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();
            NotificationAggregate saved = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class))).thenReturn(saved);

            when(lockProvider.lock(any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(mock(SimpleLock.class)));

            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(referenceNumber).concurrencyToken(0L).build();

            // When
            NotificationAggregate result = notificationService.saveNotification(dto, "trace-retry", null);

            // Then
            assertThat(result).isEqualTo(saved);
            verify(lockProvider, times(2)).lock(any());
            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_EDITED), any(), any());
        }

        @Test
        void saveNotification_shouldSaveBeforeAppendingOutboxEvent() {
            // Given — save must precede outbox write
            String referenceNumber = "GBN-AG-26-ORDER1";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(referenceNumber).concurrencyToken(0L).build();

            // When
            notificationService.saveNotification(dto, "trace-ord-001", null);

            // Then
            InOrder inOrder = inOrder(notificationRepository, outboxService);
            inOrder.verify(notificationRepository).save(existing);
            inOrder.verify(outboxService).appendEvent(existing, OutboxEventType.NOTIFICATION_EDITED, "trace-ord-001", null);
        }
    }

    @Nested
    class FindAll {

        @Test
        void findAll_shouldReturnEmptyPage() {
            // Given
            Page<NotificationView> emptyPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(0, 54), 0);
            when(notificationRepository.findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(emptyPage);

            // When
            NotificationPageResponse result = notificationService.findAll(1, null);

            // Then
            assertThat(result).isNotNull();
            verify(notificationRepository, times(1))
                .findAllViewByStatusIn(eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class));
        }

        @Test
        void findAll_shouldExcludeDeletedNotifications() {
            // Given — only DRAFT and SUBMITTED are passed as the allowlist; DELETED is excluded
            NotificationView draft = notificationView()
                .referenceNumber("GBN-AG-26-000DFT")
                .status(DRAFT)
                .build();
            NotificationView submitted = notificationView()
                .referenceNumber("GBN-AG-26-000SUB")
                .status(SUBMITTED)
                .build();

            Page<NotificationView> page = new PageImpl<>(
                List.of(draft, submitted), PageRequest.of(0, 54), 0);

            when(notificationRepository.findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(page);

            // When
            NotificationPageResponse result = notificationService.findAll(1, null);

            // Then — only DRAFT and SUBMITTED are returned
            assertThat(result.content()).hasSize(2);
            assertThat(result.content()).extracting(NotificationView::getStatus)
                .containsExactlyInAnyOrder(DRAFT, SUBMITTED);
            assertThat(result.page()).isEqualTo(1);
            assertThat(result.size()).isEqualTo(54);
            verify(notificationRepository, times(1))
                .findAllViewByStatusIn(eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class));
        }

        @Test
        void findAll_shouldMapStatusToNotificationDto() {
            // Given
            NotificationView view = notificationView()
                .referenceNumber("GBN-AG-26-ABC123")
                .origin(new Origin("GB", "true", "REF-1"))
                .status(SUBMITTED)
                .build();
            Page<NotificationView> page = new PageImpl<>(
                List.of(view), PageRequest.of(0, 54), 1);
            when(notificationRepository.findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(page);

            // When
            NotificationPageResponse result = notificationService.findAll(1, null);

            // Then
            assertThat(result.content()).hasSize(1);
            assertThat(result.content().getFirst().getReferenceNumber()).isEqualTo(
                "GBN-AG-26-ABC123");
            assertThat(result.content().getFirst().getStatus()).isEqualTo(
                SUBMITTED);
        }

        @Test
        void findAll_shouldServeFrozenPartiesInline_forASubmittedRow() {
            // The dashboard resolves a party's addressId against the address book itself, so a
            // submitted row must arrive with the frozen name already inline and no id to chase.
            String addressId = "665f1c2ab3e4d51a2c9d0e77";
            NotificationView submitted = notificationView()
                .referenceNumber("GBN-AG-26-FRZ100")
                .status(SUBMITTED)
                .consignor(ConsignmentParty.reference(addressId))
                .consignee(ConsignmentParty.reference(addressId))
                .submittedNotificationBaseline(Notification.builder()
                    .consignor(ConsignmentParty.builder()
                        .addressId(addressId).name("Astra Rosales").build())
                    .consignee(ConsignmentParty.builder()
                        .addressId(addressId).name("Bramble Holdings").build())
                    .build())
                .build();
            when(notificationRepository.findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(submitted), PageRequest.of(0, 54), 1));

            NotificationPageResponse result = notificationService.findAll(1, null);

            NotificationView row = result.content().getFirst();
            assertThat(row.getConsignor().getName()).isEqualTo("Astra Rosales");
            assertThat(row.getConsignor().getAddressId()).isNull();
            assertThat(row.getConsignee().getName()).isEqualTo("Bramble Holdings");
            assertThat(row.getConsignee().getAddressId()).isNull();
            // The baseline itself never leaves the server.
            assertThat(row.getSubmittedNotificationBaseline()).isNull();
        }

        @Test
        void findAll_shouldKeepTheLiveReference_forDraftAndAmendRows() {
            // The opposite rule: an unsubmitted notification is meant to reflect address edits, so
            // the reference has to survive the read for the caller to resolve.
            String addressId = "665f1c2ab3e4d51a2c9d0e77";
            NotificationView draft = notificationView()
                .referenceNumber("GBN-AG-26-FRZ101")
                .status(DRAFT)
                .consignor(ConsignmentParty.reference(addressId))
                .build();
            NotificationView amending = notificationView()
                .referenceNumber("GBN-AG-26-FRZ102")
                .status(AMEND)
                .consignor(ConsignmentParty.reference(addressId))
                .submittedNotificationBaseline(Notification.builder()
                    .consignor(ConsignmentParty.builder()
                        .addressId(addressId).name("Frozen At Submit").build())
                    .build())
                .build();
            when(notificationRepository.findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(draft, amending), PageRequest.of(0, 54), 2));

            NotificationPageResponse result = notificationService.findAll(1, null);

            assertThat(result.content().get(0).getConsignor())
                .isEqualTo(ConsignmentParty.reference(addressId));
            // AMEND holds a baseline, but shows live: an amendment is for picking up changes.
            assertThat(result.content().get(1).getConsignor())
                .isEqualTo(ConsignmentParty.reference(addressId));
            assertThat(result.content().get(1).getSubmittedNotificationBaseline()).isNull();
        }

        @Test
        void findAll_shouldIncludeAmendNotifications() {
            // Regression: notifications in AMEND were silently excluded from the
            // dashboard before AMEND was added to the allow-list (EUDPA-171).
            NotificationView amend = notificationView()
                .referenceNumber("GBN-AG-26-000AMD")
                .status(AMEND)
                .build();

            Page<NotificationView> page = new PageImpl<>(
                List.of(amend), PageRequest.of(0, 54), 0);

            when(notificationRepository.findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(page);

            NotificationPageResponse result = notificationService.findAll(1, null);

            assertThat(result.content()).hasSize(1);
            assertThat(result.content()).extracting(NotificationView::getStatus)
                .containsExactly(AMEND);
        }

        @Test
        void findAll_shouldUseCreatedAtSort_whenRequested() {
            Page<NotificationView> emptyPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(0, 54), 0);
            when(notificationRepository.findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(emptyPage);

            notificationService.findAll(1, "createdAt,asc");

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(notificationRepository).findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), pageableCaptor.capture());
            assertThat(pageableCaptor.getValue().getSort().getOrderFor("created").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        }

        @Test
        void findAll_shouldReturnMatchingNotification_whenReferenceNumberProvided() {
            NotificationView draft = notificationView()
                .referenceNumber("GBN-AG-26-ABC123")
                .status(DRAFT)
                .build();

            when(notificationRepository.findViewByReferenceNumberAndStatusIn(
                "GBN-AG-26-ABC123", List.of(DRAFT, SUBMITTED, AMEND)))
                .thenReturn(Optional.of(draft));

            NotificationPageResponse result =
                notificationService.findAll(1, null, "GBN-AG-26-ABC123");

            assertThat(result.content()).hasSize(1);
            assertThat(result.content().getFirst().getReferenceNumber())
                .isEqualTo("GBN-AG-26-ABC123");
            assertThat(result.totalElements()).isEqualTo(1);
            verify(notificationRepository, never())
                .findAllViewByStatusIn(any(), any(Pageable.class));
        }

        @Test
        void findAll_shouldReturnEmptyPage_whenReferenceNumberNotFound() {
            when(notificationRepository.findViewByReferenceNumberAndStatusIn(
                "GBN-AG-26-ZZZZZZ", List.of(DRAFT, SUBMITTED, AMEND)))
                .thenReturn(Optional.empty());

            NotificationPageResponse result =
                notificationService.findAll(1, null, "GBN-AG-26-ZZZZZZ");

            assertThat(result.content()).isEmpty();
            assertThat(result.totalElements()).isZero();
            verify(notificationRepository, never())
                .findAllViewByStatusIn(any(), any(Pageable.class));
        }

        @Test
        void findAll_shouldTrimReferenceNumber_beforeLookup() {
            when(notificationRepository.findViewByReferenceNumberAndStatusIn(
                "GBN-AG-26-ABC123", List.of(DRAFT, SUBMITTED, AMEND)))
                .thenReturn(Optional.empty());

            notificationService.findAll(1, null, "  GBN-AG-26-ABC123  ");

            verify(notificationRepository).findViewByReferenceNumberAndStatusIn(
                "GBN-AG-26-ABC123", List.of(DRAFT, SUBMITTED, AMEND));
        }
    }

    @Nested
    class FindFulfilmentsView {

        @Test
        void findFulfilmentsView_shouldReturnProjection_whenNotificationExists() {
            // Given
            String ref = "GBN-AG-26-FUL001";
            NotificationFulfilmentsView view = mock(NotificationFulfilmentsView.class);
            when(notificationRepository.findFulfilmentsViewByReferenceNumber(ref))
                .thenReturn(Optional.of(view));

            // When
            NotificationFulfilmentsView result = notificationService.findFulfilmentsView(ref);

            // Then
            assertThat(result).isSameAs(view);
        }

        @Test
        void findFulfilmentsView_shouldThrowNotFound_whenReferenceNumberUnknown() {
            // Given
            String ref = "GBN-AG-26-ABSENT";
            when(notificationRepository.findFulfilmentsViewByReferenceNumber(ref))
                .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> notificationService.findFulfilmentsView(ref))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(ref);
        }
    }

    @Nested
    class FindAllReferenceNumbers {

        @Test
        void findAllReferenceNumbers_shouldReturnEmptyPage_whenNoNotificationsExist() {
            // Given
            Page<NotificationReferenceOnly> emptyPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(0, 54), 0);
            when(notificationRepository.findAllProjectedBy(any(Pageable.class)))
                .thenReturn(emptyPage);

            // When
            ReferenceNumberPageResponse result = notificationService.findAllReferenceNumbers(0);

            // Then
            assertThat(result.content()).isEmpty();
            assertThat(result.page()).isZero();
            assertThat(result.size()).isEqualTo(54);
            assertThat(result.numberOfElements()).isZero();
            assertThat(result.totalElements()).isZero();
            assertThat(result.totalPages()).isZero();
            verify(notificationRepository, times(1)).findAllProjectedBy(any(Pageable.class));
        }

        @Test
        void findAllReferenceNumbers_shouldReturnPagedReferenceNumbers_whenNotificationsExist() {
            // Given
            NotificationReferenceOnly ref1 = () -> "GBN-AG-26-ABC123";
            NotificationReferenceOnly ref2 = () -> "GBN-AG-26-XYZ456";
            Page<NotificationReferenceOnly> page = new PageImpl<>(
                List.of(ref1, ref2), PageRequest.of(0, 54), 2);
            when(notificationRepository.findAllProjectedBy(any(Pageable.class))).thenReturn(page);

            // When
            ReferenceNumberPageResponse result = notificationService.findAllReferenceNumbers(0);

            // Then
            assertThat(result.content()).containsExactly("GBN-AG-26-ABC123", "GBN-AG-26-XYZ456");
            assertThat(result.totalElements()).isEqualTo(2);
            assertThat(result.totalPages()).isEqualTo(1);
            verify(notificationRepository, times(1)).findAllProjectedBy(any(Pageable.class));
        }

        @Test
        void findAllReferenceNumbers_shouldPassPageRequestWithConfiguredPageSizeAndAscendingIdSort() {
            // Given
            Page<NotificationReferenceOnly> emptyPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(2, 54), 200);
            when(notificationRepository.findAllProjectedBy(any(Pageable.class)))
                .thenReturn(emptyPage);

            // When
            notificationService.findAllReferenceNumbers(2);

            // Then
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(notificationRepository).findAllProjectedBy(pageableCaptor.capture());
            Pageable pageable = pageableCaptor.getValue();
            assertThat(pageable.getPageNumber()).isEqualTo(2);
            assertThat(pageable.getPageSize()).isEqualTo(50);
            assertThat(pageable.getSort().getOrderFor("created")).isNotNull();
            assertThat(pageable.getSort().getOrderFor("created").getDirection())
                .isEqualTo(Direction.DESC);
        }
    }

    @Nested
    class DeleteByReferenceNumbers {

        @Test
        void deleteByReferenceNumbers_shouldDeleteAll_whenAllFound() {
            // Given
            String ref1 = "GBN-AG-26-000111";
            String ref2 = "GBN-AG-26-000222";
            NotificationReferenceOnly n1 = () -> ref1;
            NotificationReferenceOnly n2 = () -> ref2;

            when(notificationRepository.findAllByReferenceNumberIn(List.of(ref1, ref2)))
                .thenReturn(List.of(n1, n2));
            when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

            // When
            notificationService.deleteByReferenceNumbers(List.of(ref1, ref2),
                new AuditContext(TEST_TRACE_ID, TEST_USER_ID));

            // Then — deleteAllByReferenceNumberIn is called with the original reference numbers
            verify(notificationRepository).deleteAllByReferenceNumberIn(List.of(ref1, ref2));

            // And cascade document deletion is triggered for the same refs
            verify(documentService).deleteForNotificationRefs(List.of(ref1, ref2));

            // And an audit record is saved with SUCCESS
            ArgumentCaptor<Audit> auditCaptor = ArgumentCaptor.forClass(Audit.class);
            verify(auditRepository).save(auditCaptor.capture());
            Audit saved = auditCaptor.getValue();
            assertThat(saved.getResult()).isEqualTo(Result.SUCCESS);
            assertThat(saved.getNotificationReferenceNumbers()).containsExactly(ref1, ref2);
            assertThat(saved.getNumberOfNotifications()).isEqualTo(2);
            assertThat(saved.getTraceId()).isEqualTo("test-trace-id");
            assertThat(saved.getUserId()).isEqualTo("test-user-id");
        }

        @Test
        void deleteByReferenceNumbers_shouldThrowNotFoundException_whenOneIsMissing() {
            // Given
            String existingRef = "GBN-AG-26-000111";
            String missingRef = "GBN-AG-26-MSNG00";
            NotificationReferenceOnly n1 = () -> existingRef;

            when(
                notificationRepository.findAllByReferenceNumberIn(List.of(existingRef, missingRef)))
                .thenReturn(List.of(n1));
            when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

            // When / Then
            List<String> referenceNumbers = List.of(existingRef, missingRef);
            AuditContext auditContext = new AuditContext(TEST_TRACE_ID, TEST_USER_ID);

            assertThatThrownBy(
                () -> notificationService.deleteByReferenceNumbers(referenceNumbers, auditContext))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(missingRef);

            // deleteAllByReferenceNumberIn must NOT be called — no partial deletes
            verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());

            // But a FAILURE audit record is saved
            ArgumentCaptor<Audit> auditCaptor = ArgumentCaptor.forClass(Audit.class);
            verify(auditRepository).save(auditCaptor.capture());
            assertThat(auditCaptor.getValue().getResult()).isEqualTo(Result.FAILURE);
        }

        @Test
        void deleteByReferenceNumbers_shouldListAllMissingRefs_inExceptionMessage() {
            // Given
            String missing1 = "GBN-AG-26-000AAA";
            String missing2 = "GBN-AG-26-000BBB";

            when(notificationRepository.findAllByReferenceNumberIn(List.of(missing1, missing2)))
                .thenReturn(Collections.emptyList());
            when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

            // When / Then
            List<String> referenceNumbers = List.of(missing1, missing2);
            AuditContext auditContext = new AuditContext(TEST_TRACE_ID, TEST_USER_ID);

            assertThatThrownBy(
                () -> notificationService.deleteByReferenceNumbers(referenceNumbers, auditContext))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(missing1)
                .hasMessageContaining(missing2);

            verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
            verify(auditRepository).save(any(Audit.class));
        }

        @Test
        void deleteByReferenceNumbers_shouldDoNothing_whenListIsEmpty() {
            // When — empty list is passed (defensive guard; controller rejects this before reaching service)
            notificationService.deleteByReferenceNumbers(Collections.emptyList(),
                new AuditContext(TEST_TRACE_ID, TEST_USER_ID));

            // Then — repository is never called
            verify(notificationRepository, never()).findAllByReferenceNumberIn(anyList());
            verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
            verify(auditRepository, never()).save(any(Audit.class));
        }

        @Test
        void deleteByReferenceNumbers_shouldCascadeDeleteDocuments_whenNotificationsDeleted() {
            // Given
            String referenceNumber = "GBN-AG-26-000111";
            NotificationReferenceOnly notificationRef = () -> referenceNumber;

            when(notificationRepository.findAllByReferenceNumberIn(List.of(referenceNumber)))
                .thenReturn(List.of(notificationRef));
            when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

            // When
            notificationService.deleteByReferenceNumbers(List.of(referenceNumber),
                new AuditContext(TEST_TRACE_ID, TEST_USER_ID));

            // Then — notifications deleted first, then documents cascade deleted (order matters)
            InOrder inOrder = inOrder(notificationRepository, documentService);
            inOrder.verify(notificationRepository)
                .deleteAllByReferenceNumberIn(List.of(referenceNumber));
            inOrder.verify(documentService).deleteForNotificationRefs(List.of(referenceNumber));
        }
    }

    @Nested
    class ExpiryStamping {

        @BeforeEach
        void stubCreate() {
            lenient().when(lockProvider.lock(any()))
                .thenReturn(Optional.of(mock(SimpleLock.class)));
            when(referenceNumberGenerator.generate()).thenReturn("GBN-AG-26-TTL001");
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        }

        private NotificationAggregate create(NotificationTtlConfig ttlConfig) {
            NotificationService service = buildService(ttlConfig);
            return service.saveNotification(
                NotificationDto.builder().origin(new Origin("GB", "true", "REF123")).build(), "", null);
        }

        @Test
        void createNotification_stampsExpireAt_whenDaysConfiguredAndNotProd() {
            NotificationAggregate result = create(new NotificationTtlConfig(7, "dev", sweep(false)));

            assertThat(result.getExpireAt()).isEqualTo(result.getCreated().plusDays(7));
        }

        @Test
        void createNotification_neverStampsExpireAt_inProd_regardlessOfOtherConfig() {
            // AC: in a prod-configured environment, notifications are never marked for automatic
            // removal, regardless of other config values (days set, sweep enabled).
            NotificationAggregate result = create(new NotificationTtlConfig(7, "prod", sweep(true)));

            assertThat(result.getExpireAt()).isNull();
        }

        @Test
        void createNotification_neverStampsExpireAt_inProd_caseInsensitive() {
            NotificationAggregate result = create(new NotificationTtlConfig(7, "PROD", sweep(true)));

            assertThat(result.getExpireAt()).isNull();
        }

        @Test
        void createNotification_doesNotStampExpireAt_whenDaysUnconfigured() {
            NotificationAggregate result = create(new NotificationTtlConfig(null, "dev", sweep(false)));

            assertThat(result.getExpireAt()).isNull();
        }
    }

    @Nested
    class DeleteExpired {

        @Test
        void deleteExpired_deletesDueNotifications_andCascadesToDocuments_withNoAudit() {
            String ref1 = "GBN-AG-26-EXP001";
            String ref2 = "GBN-AG-26-EXP002";
            when(notificationRepository.findExpired(
                any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(() -> ref1, () -> ref2));

            int deleted = notificationService.deleteExpired(10);

            assertThat(deleted).isEqualTo(2);
            InOrder inOrder = inOrder(notificationRepository, documentService);
            inOrder.verify(notificationRepository).deleteAllByReferenceNumberIn(List.of(ref1, ref2));
            inOrder.verify(documentService).deleteForNotificationRefs(List.of(ref1, ref2));
            // A background sweep is not a user action — it writes no audit record.
            verify(auditRepository, never()).save(any(Audit.class));
        }

        @Test
        void deleteExpired_returnsZeroAndSkipsDeletion_whenNothingDue() {
            when(notificationRepository.findExpired(
                any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

            assertThat(notificationService.deleteExpired(10)).isZero();

            verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
            verify(documentService, never()).deleteForNotificationRefs(anyList());
        }

        @Test
        void deleteExpired_queriesFirstPageBoundedByBatchSize() {
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            when(notificationRepository.findExpired(
                any(LocalDateTime.class), pageableCaptor.capture()))
                .thenReturn(Collections.emptyList());

            notificationService.deleteExpired(5);

            assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);
        }
    }

    @Nested
    class SubmitNotification {

        @BeforeEach
        void setUp() {
            // Default: lock is acquired and the task executes
            lenient().when(lockProvider.lock(any()))
                .thenReturn(Optional.of(mock(SimpleLock.class)));
        }

        @Test
        void submitNotification_shouldSetStatusToSubmittedAndSave() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC123";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            NotificationAggregate result = notificationService.submitNotification(referenceNumber,
                "trace-001", null);

            // Then
            assertThat(result.getStatus()).isEqualTo(SUBMITTED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notificationAggregate);
            // Captured rather than compared by value: the event travels on a resolved copy, which
            // by design does not carry the stored notification's frozen baseline.
            ArgumentCaptor<NotificationAggregate> captor =
                ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(outboxService).appendEvent(captor.capture(),
                eq(OutboxEventType.NOTIFICATION_SUBMITTED), eq("trace-001"), isNull());
            assertThat(captor.getValue().getReferenceNumber()).isEqualTo(referenceNumber);
            assertThat(captor.getValue().getStatus()).isEqualTo(SUBMITTED);
        }

        @Test
        void submitNotification_shouldNotCarryTheFrozenBaselineOnTheOutboxEvent() {
            // The freeze is server-internal state, not part of the transmitted document. It rides
            // the stored notification only; the event copy must stay clean.
            String referenceNumber = "GBN-AG-26-FRZ001";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-frozen")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder()
                    .origin(new Origin("GB", "yes", "REF-1"))
                    .build())
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.submitNotification(referenceNumber,
                "trace-frozen", null);

            ArgumentCaptor<NotificationAggregate> captor =
                ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(outboxService).appendEvent(captor.capture(),
                eq(OutboxEventType.NOTIFICATION_SUBMITTED), eq("trace-frozen"), isNull());
            assertThat(captor.getValue().getSubmittedNotificationBaseline()).isNull();
            assertThat(result.getSubmittedNotificationBaseline()).isNotNull();
        }

        @Test
        void submitNotification_shouldFreezeResolvedDetails_whenPartyIsAReference() {
            // The case the ticket exists for: a referenced consignor freezes the *resolved* name
            // and address, while the stored role field keeps the reference alone. Editing the
            // address book afterwards cannot reach the frozen copy.
            String addressId = "665f1c2ab3e4d51a2c9d0e77";
            String referenceNumber = "GBN-AG-26-FRZ002";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-frozen-ref")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder()
                    .consignor(NotificationTestData.reference(addressId))
                    .build())
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            when(addressBookClient.findById(ORG_ID, addressId))
                .thenReturn(Optional.of(new AddressBookRecord(
                    addressId, "Astra Rosales", "43 East Hague Extension", null, "Vernier",
                    "Soleure", "30055", "CH", "+41 22 000 0000", "astra@example.com", false)));
            Actor actor = Actor.builder().organisationId(ORG_ID).build();

            NotificationAggregate result = notificationService.submitNotification(referenceNumber,
                "trace-frozen-ref", actor);

            // The baseline holds resolved details AND the id they were resolved from.
            ConsignmentParty frozenConsignor =
                result.getSubmittedNotificationBaseline().getConsignor();
            assertThat(frozenConsignor.getName()).isEqualTo("Astra Rosales");
            assertThat(frozenConsignor.getAddress().getPostcode()).isEqualTo("30055");
            assertThat(frozenConsignor.getAddress().getTownOrCity()).isEqualTo("Vernier");
            assertThat(frozenConsignor.getAddressId()).isEqualTo(addressId);
            // Storage keeps the reference alone — the link an amendment re-resolves (AC #2).
            assertThat(result.getNotification().getConsignor())
                .isEqualTo(ConsignmentParty.reference(addressId));
        }

        @Test
        void submitNotification_shouldNotCaptureABaseline_whenAReferenceCannotBeResolved() {
            // The resolve runs before the lock and aborts the submit, so a failed submit must not
            // leave a half-frozen notification behind.
            String addressId = "665f1c2ab3e4d51a2c9d0e78";
            String referenceNumber = "GBN-AG-26-FRZ003";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-frozen-miss")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder()
                    .consignor(NotificationTestData.reference(addressId))
                    .build())
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(addressBookClient.findById(ORG_ID, addressId)).thenReturn(Optional.empty());
            Actor actor = Actor.builder().organisationId(ORG_ID).build();

            assertThatThrownBy(() -> notificationService.submitNotification(referenceNumber,
                "trace-frozen-miss", actor))
                .isInstanceOf(BadRequestException.class);

            assertThat(notificationAggregate.getSubmittedNotificationBaseline()).isNull();
            assertThat(notificationAggregate.getStatus()).isEqualTo(DRAFT);
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void submitNotification_shouldResolveReferencedParty_beforeAppendingOutboxEvent() {
            String addressId = "665f1c2ab3e4d51a2c9d0e77";
            String referenceNumber = "GBN-AG-26-REF011";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-ref")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder()
                    .consignor(NotificationTestData.reference(addressId))
                    .build())
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            when(addressBookClient.findById(ORG_ID, addressId))
                .thenReturn(Optional.of(new AddressBookRecord(
                    addressId, "Astra Rosales", "43 East Hague Extension", null, "Vernier",
                    "Soleure", "30055", "CH", "+41 22 000 0000", "astra@example.com", false)));
            Actor actor = Actor.builder().organisationId(ORG_ID).build();

            notificationService.submitNotification(referenceNumber, "trace-ref-001", actor);

            ArgumentCaptor<NotificationAggregate> captor = ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(outboxService).appendEvent(
                captor.capture(), eq(OutboxEventType.NOTIFICATION_SUBMITTED), eq("trace-ref-001"),
                eq(actor));
            assertThat(captor.getValue().getNotification().getConsignor().getName()).isEqualTo("Astra Rosales");
            assertThat(captor.getValue().getNotification().getConsignor().getAddress().getPostcode()).isEqualTo("30055");
        }

        @Test
        void submitNotification_shouldResolveOntoACopy_leavingTheStoredNotificationReferencedOnly() {
            // Given — resolution feeds the event only. The notification that is saved, and the one
            // handed back to the caller, must still carry the reference and nothing else.
            String addressId = "665f1c2ab3e4d51a2c9d0e77";
            String referenceNumber = "GBN-AG-26-REF012";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-copy")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder()
                    .consignor(NotificationTestData.reference(addressId))
                    .build())
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            when(addressBookClient.findById(ORG_ID, addressId))
                .thenReturn(Optional.of(addressBookRecord(addressId, false)));

            // When
            NotificationAggregate returned = notificationService.submitNotification(
                referenceNumber, "trace-copy-001", Actor.builder().organisationId(ORG_ID).build());

            // Then
            assertThat(returned.getNotification().getConsignor()).isEqualTo(ConsignmentParty.reference(addressId));
            ArgumentCaptor<NotificationAggregate> saved = ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(notificationRepository).save(saved.capture());
            assertThat(saved.getValue().getNotification().getConsignor())
                .isEqualTo(ConsignmentParty.reference(addressId));
        }

        @Test
        void submitNotification_shouldFetchASharedAddressOnce_whenTwoRolesReferenceIt() {
            // Given — the same saved address used as both consignor and consignee. Every lookup
            // runs against a 2s timeout budget, so the roles share one call rather than each
            // paying for their own.
            String addressId = "665f1c2ab3e4d51a2c9d0e77";
            String referenceNumber = "GBN-AG-26-REF013";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-shared")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder()
                    .consignor(NotificationTestData.reference(addressId))
                    .consignee(NotificationTestData.reference(addressId))
                    .build())
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            when(addressBookClient.findById(ORG_ID, addressId))
                .thenReturn(Optional.of(addressBookRecord(addressId, false)));

            // When
            notificationService.submitNotification(
                referenceNumber, "trace-shared-001", Actor.builder().organisationId(ORG_ID).build());

            // Then
            ArgumentCaptor<NotificationAggregate> captor = ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(outboxService).appendEvent(captor.capture(), any(), any(), any());
            assertThat(captor.getValue().getNotification().getConsignee().getName()).isEqualTo("Astra Rosales");
            verify(addressBookClient, times(1)).findById(ORG_ID, addressId);
        }

        @Test
        void submitNotification_shouldWriteOutboxEvent_afterSavingNotification() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC123";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.submitNotification(referenceNumber, "trace-001", null);

            // Then — save must happen before the outbox event is written
            InOrder inOrder = inOrder(notificationRepository, outboxService);
            inOrder.verify(notificationRepository).save(notificationAggregate);
            inOrder.verify(outboxService).appendEvent(any(NotificationAggregate.class),
                eq(OutboxEventType.NOTIFICATION_SUBMITTED), eq("trace-001"), isNull());
        }

        @Test
        void submitNotification_shouldThrowOutboxWriteException_whenAppendEventFails() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC123";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            doThrow(new OutboxWriteException("Forced failure", "agg-id", 1L, "trace-001"))
                .when(outboxService).appendEvent(any(), any(), any(), any());

            // When / Then — exception propagates out of submitNotification
            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, "trace-001", null))
                .isInstanceOf(OutboxWriteException.class);
        }

        @Test
        void submitNotification_shouldThrowNotFoundException_whenReferenceNumberUnknown() {
            // Given
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, "trace-001", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(referenceNumber);

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
            // Lock must never be acquired — the notification lookup fails before the lock scope
            verify(lockProvider, never()).lock(any());
        }

        @Test
        void submitNotification_shouldThrowOutboxWriteException_whenLockNotAcquired() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC123";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            // Lock not available — executor returns wasExecuted() == false
            when(lockProvider.lock(any())).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, "trace-001", null))
                .isInstanceOf(OutboxWriteException.class)
                .satisfies(ex -> {
                    OutboxWriteException owe = (OutboxWriteException) ex;
                    assertThat(owe.getAggregateId())
                        .isEqualTo("Imports.Notification.GBN-AG." + referenceNumber);
                    assertThat(owe.getCorrelationId()).isEqualTo("trace-001");
                });

            verify(lockProvider, times(3)).lock(any());
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void submitNotification_shouldSetStatusToSubmittedAndSave_whenAmend() {
            // Given
            String referenceNumber = "GBN-AG-26-AMEND1";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amend-1")
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            NotificationAggregate result = notificationService.submitNotification(referenceNumber,
                "trace-002", null);

            // Then
            assertThat(result.getStatus()).isEqualTo(SUBMITTED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notificationAggregate);
            ArgumentCaptor<NotificationAggregate> captor =
                ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(outboxService).appendEvent(captor.capture(),
                eq(OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED), eq("trace-002"), isNull());
            assertThat(captor.getValue().getReferenceNumber()).isEqualTo(referenceNumber);
            assertThat(captor.getValue().getStatus()).isEqualTo(SUBMITTED);
        }

        @Test
        void submitNotification_shouldThrowBadRequest_whenAlreadySubmitted() {
            // Given
            String referenceNumber = "GBN-AG-26-ALREADY";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-already")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            // When / Then
            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, "trace-003", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SUBMITTED");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
            verify(lockProvider, never()).lock(any());
        }

        @Test
        void submitNotification_shouldThrowBadRequest_whenDeleted() {
            // Given
            String referenceNumber = "GBN-AG-26-DELETED";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-deleted")
                .referenceNumber(referenceNumber)
                .status(NotificationStatus.DELETED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            // When / Then
            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, "trace-004", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DELETED");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }
    }

    @Nested
    class AmendNotification {

        @BeforeEach
        void setUp() {
            // Default: lock is acquired and the task executes
            lenient().when(lockProvider.lock(any()))
                .thenReturn(Optional.of(mock(SimpleLock.class)));
        }

        @Test
        void amendNotification_shouldSetStatusToAmendAndSave_whenSubmitted() {
            // Given
            String referenceNumber = "GBN-AG-26-AMD001";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-1")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                // A submitted notification already carries the baseline frozen at submit.
                .submittedNotificationBaseline(Notification.builder()
                    .origin(new Origin("GB", "true", "AS-SUBMITTED"))
                    .build())
                .build();
            Notification frozenAtSubmit = notificationAggregate.getSubmittedNotificationBaseline();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            NotificationAggregate result = notificationService.amendNotification(referenceNumber,
                "trace-amd-1", null);

            // Then
            assertThat(result.getStatus()).isEqualTo(AMEND);
            assertThat(result.getUpdated()).isNotNull();
            // Retained across the amendment — the amendment does not re-snapshot.
            assertThat(result.getSubmittedNotificationBaseline()).isSameAs(frozenAtSubmit);
            verify(notificationRepository).save(notificationAggregate);
            verify(outboxService).appendEvent(notificationAggregate, OutboxEventType.NOTIFICATION_AMENDMENT_REQUESTED, "trace-amd-1", null);
        }

        @Test
        void amendNotification_shouldRetainTheSubmitBaseline_notRecaptureCurrentContent() {
            // The bug this ticket fixes. Capturing the baseline at amend-start would snapshot
            // content whose references resolve to *today's* addresses, so cancelling the amendment
            // would revert to a state that was never submitted. The baseline must stay the one
            // frozen at submit, even when the stored content has moved on since.
            String referenceNumber = "GBN-AG-26-AMD008";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-8")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder()
                    .origin(new Origin("FR", "true", "EDITED-SINCE"))
                    .build())
                .submittedNotificationBaseline(Notification.builder()
                    .origin(new Origin("GB", "true", "AS-SUBMITTED"))
                    .build())
                .build();
            Notification frozenAtSubmit = notificationAggregate.getSubmittedNotificationBaseline();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.amendNotification(referenceNumber, "trace-amd-8", null);

            // Then
            assertThat(notificationAggregate.getSubmittedNotificationBaseline())
                .isSameAs(frozenAtSubmit);
            assertThat(notificationAggregate.getSubmittedNotificationBaseline().getOrigin()
                .getInternalReference()).isEqualTo("AS-SUBMITTED");
        }

        @Test
        void amendNotification_shouldWriteOutboxEvent_afterSavingNotification() {
            // Given
            String referenceNumber = "GBN-AG-26-AMD002";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-2")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.amendNotification(referenceNumber, "trace-amd-2", null);

            // Then — save must happen before the outbox event is written
            InOrder inOrder = inOrder(notificationRepository, outboxService);
            inOrder.verify(notificationRepository).save(notificationAggregate);
            inOrder.verify(outboxService).appendEvent(notificationAggregate, OutboxEventType.NOTIFICATION_AMENDMENT_REQUESTED, "trace-amd-2", null);
        }

        @Test
        void amendNotification_shouldThrowBadRequest_whenDraft() {
            // Given
            String referenceNumber = "GBN-AG-26-AMD003";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-3")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            // When / Then
            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-3", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DRAFT");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
            verify(lockProvider, never()).lock(any());
        }

        @Test
        void amendNotification_shouldThrowBadRequest_whenAlreadyAmend() {
            // Given
            String referenceNumber = "GBN-AG-26-AMD004";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-4")
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            // When / Then
            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-4", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("AMEND");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }

        @Test
        void amendNotification_shouldThrowNotFoundException_whenReferenceNumberUnknown() {
            // Given
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-5", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(referenceNumber);

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
            verify(lockProvider, never()).lock(any());
        }

        @Test
        void amendNotification_shouldThrowOutboxWriteException_whenLockNotAcquired() {
            // Given
            String referenceNumber = "GBN-AG-26-AMD006";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-6")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            when(lockProvider.lock(any())).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-6", null))
                .isInstanceOf(OutboxWriteException.class)
                .satisfies(ex -> {
                    OutboxWriteException owe = (OutboxWriteException) ex;
                    assertThat(owe.getAggregateId())
                        .isEqualTo("Imports.Notification.GBN-AG." + referenceNumber);
                    assertThat(owe.getCorrelationId()).isEqualTo("trace-amd-6");
                });

            verify(lockProvider, times(3)).lock(any());
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void amendNotification_shouldThrowOutboxWriteException_whenAppendEventFails() {
            // Given
            String referenceNumber = "GBN-AG-26-AMD007";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-7")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            doThrow(new OutboxWriteException("Forced failure", "agg-id", 1L, "trace-amd-7"))
                .when(outboxService).appendEvent(any(), any(), any(), any());

            // When / Then
            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-7", null))
                .isInstanceOf(OutboxWriteException.class);
        }
    }

    @Nested
    class CancelAmendNotification {

        @BeforeEach
        void setUp() {
            lenient().when(lockProvider.lock(any()))
                .thenReturn(Optional.of(mock(SimpleLock.class)));
        }

        @Test
        void cancelAmendNotification_shouldRestoreBaselineAndSetSubmitted() {
            // Given
            String referenceNumber = "GBN-AG-26-CAN001";
            Notification baseline = Notification.builder()
                .origin(new Origin("GB", "true", "ORIGINAL-REF"))
                .build();
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-can-1")
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder()
                    .origin(new Origin("FR", "false", "EDITED-REF"))
                    .build())
                .submittedNotificationBaseline(baseline)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            NotificationAggregate result = notificationService.cancelAmendNotification(referenceNumber, "trace", null);

            // Then
            assertThat(result.getStatus()).isEqualTo(SUBMITTED);
            // Retained, not cleared: back at SUBMITTED the baseline is the read source again.
            assertThat(result.getSubmittedNotificationBaseline()).isSameAs(baseline);
            assertThat(result.getNotification().getOrigin().getInternalReference()).isEqualTo("ORIGINAL-REF");
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notificationAggregate);
            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_AMENDMENT_CANCELLED), any(), any());
        }

        @Test
        void cancelAmendNotification_shouldThrowBadRequest_whenNotAmend() {
            // Given
            String referenceNumber = "GBN-AG-26-CAN002";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            // When / Then
            assertThatThrownBy(() -> notificationService.cancelAmendNotification(referenceNumber, "trace", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SUBMITTED");

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void cancelAmendNotification_shouldThrowBadRequest_whenBaselineMissing() {
            // Given
            String referenceNumber = "GBN-AG-26-CAN003";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder().build())
                .submittedNotificationBaseline(null)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            // When / Then
            assertThatThrownBy(() -> notificationService.cancelAmendNotification(referenceNumber, "trace", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("baseline");

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void cancelAmendNotification_shouldThrowNotFound_whenReferenceNumberUnknown() {
            // Given
            when(notificationRepository.findByReferenceNumber("GBN-AG-26-ABSENT"))
                .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(
                () -> notificationService.cancelAmendNotification("GBN-AG-26-ABSENT", "trace", null))
                .isInstanceOf(NotFoundException.class);

            verify(notificationRepository, never()).save(any());
        }
    }

    @Nested
    class CopyNotification {

        @BeforeEach
        void setUp() {
            lenient().when(lockProvider.lock(any()))
                .thenReturn(Optional.of(mock(SimpleLock.class)));
        }

        @Test
        void copyNotification_shouldCreateNewDraftWithNewReferenceNumber() {
            // Given
            String sourceRef = "GBN-AG-26-SRC001";
            String newRef = "GBN-AG-26-NEW001";
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .notification(Notification.builder()
                    .origin(new Origin("IE", "no", "INT-REF-DO-NOT-COPY"))
                    .build())
                .status(NotificationStatus.DRAFT)
                .notification(Notification.builder().build())
                .concurrencyToken(0L)
                .build();

            NotificationAggregate created = NotificationAggregate.builder()
                .referenceNumber(newRef)
                .status(NotificationStatus.DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            when(notificationRepository.save(any(NotificationAggregate.class))).thenReturn(created);

            // When
            NotificationAggregate result = notificationService.copyNotification(sourceRef, 0L, "trace", null);

            // Then
            assertThat(result.getReferenceNumber()).isEqualTo(newRef);
            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        }

        @Test
        void copyNotification_shouldCreateNewDraftFromSubmittedSource() {
            // Given
            String sourceRef = "GBN-AG-26-SUB001";
            String newRef = "GBN-AG-26-NEW002";
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .notification(Notification.builder()
                    .origin(new Origin("IE", "no", "INT-REF-DO-NOT-COPY"))
                    .build())
                .status(NotificationStatus.SUBMITTED)
                .notification(Notification.builder().build())
                .concurrencyToken(0L)
                .build();

            NotificationAggregate created = NotificationAggregate.builder()
                .referenceNumber(newRef)
                .status(NotificationStatus.DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            when(notificationRepository.save(any(NotificationAggregate.class))).thenReturn(created);

            // When
            NotificationAggregate result = notificationService.copyNotification(sourceRef, 0L, "trace", null);

            // Then
            assertThat(result.getReferenceNumber()).isEqualTo(newRef);
            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        }

        @Test
        void copyNotification_shouldRetainCopiedFields() {
            // Given
            String sourceRef = "GBN-AG-26-SRC002";
            Origin origin = new Origin("DE", "yes", "INTERNAL-REF");
            AdditionalDetails additionalDetails = new AdditionalDetails("Breeding", "yes");
            CommodityComplement complement = new CommodityComplement("LIVE", 10, 5,
                List.of(species()));
            Commodity commodity = Commodity.builder()
                .name("Live bovine animals")
                .commodityComplement(List.of(complement))
                .build();

            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DRAFT)
                .notification(Notification.builder().build())
                .concurrencyToken(0L)
                .notification(Notification.builder()
                    .origin(origin)
                    .commodity(commodity)
                    .reasonForImport("internalMarket")
                    .additionalDetails(additionalDetails)
                    .consignor(consignors().getFirst())
                    .destination(destinations().getFirst())
                    .cphNumber("12/345/6789")
                    .transport(Transport.builder()
                        .portOfEntry("GBDVR")
                        .arrivalDate(LocalDate.of(2026, Month.MAY, 1))
                        .transporter(transporters().getFirst())
                        .build())
                    .consignment(consignments().getFirst())
                    .build())
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn("GBN-AG-26-CPY002");
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.copyNotification(sourceRef, 0L, "trace", null);

            // Then — capture what was saved and assert retained fields
            ArgumentCaptor<NotificationAggregate> captor = ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(notificationRepository).save(captor.capture());
            NotificationAggregate saved = captor.getValue();

            // Retained
            assertThat(saved.getStatus()).isEqualTo(NotificationStatus.DRAFT);
            assertThat(saved.getNotification().getOrigin().getCountryCode()).isEqualTo("DE");
            assertThat(saved.getNotification().getOrigin().getRequiresRegionCode()).isEqualTo("yes");
            assertThat(saved.getNotification().getReasonForImport()).isEqualTo("internalMarket");
            assertThat(saved.getNotification().getCommodity().getName()).isEqualTo("Live bovine animals");
            assertThat(saved.getNotification().getAdditionalDetails().getCertifiedFor()).isEqualTo("Breeding");
            assertThat(saved.getNotification().getConsignor()).isEqualTo(consignors().getFirst());
            assertThat(saved.getNotification().getDestination()).isEqualTo(destinations().getFirst());
            assertThat(saved.getNotification().getCphNumber()).isEqualTo("12/345/6789");
        }

        @Test
        void copyNotification_shouldResetExcludedFields() {
            // Given
            String sourceRef = "GBN-AG-26-SRC003";
            CommodityComplement complement = new CommodityComplement("LIVE", 10, 5,
                List.of(species()));
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DRAFT)
                .notification(Notification.builder().build())
                .concurrencyToken(0L)
                .notification(Notification.builder()
                    .origin(new Origin("FR", "no", "DO-NOT-COPY"))
                    .commodity(Commodity.builder()
                        .name("Cattle")
                        .commodityComplement(List.of(complement))
                        .build())
                    .additionalDetails(new AdditionalDetails("Slaughter", "no"))
                    .transport(Transport.builder()
                        .portOfEntry("GBFXT")
                        .arrivalDate(LocalDate.of(2026, Month.JUNE, 1))
                        .build())
                    .consignment(consignments().getFirst())
                    .build())
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn("GBN-AG-26-CPY003");
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.copyNotification(sourceRef, 0L, "trace", null);

            // Then — excluded fields must be null on the saved copy
            ArgumentCaptor<NotificationAggregate> captor = ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(notificationRepository).save(captor.capture());
            NotificationAggregate saved = captor.getValue();

            assertThat(saved.getNotification().getOrigin().getInternalReference()).isNull();
            assertThat(saved.getNotification().getAdditionalDetails().getUnweanedAnimals()).isNull();
            assertThat(saved.getNotification().getTransport()).isNull();
            assertThat(saved.getNotification().getConsignment()).isNull();
            // Commodity complement strips per-animal data
            CommodityComplement cc = saved.getNotification().getCommodity().getCommodityComplement().getFirst();
            assertThat(cc.getTypeOfCommodity()).isEqualTo("LIVE");
            assertThat(cc.getTotalNoOfAnimals()).isNull();
            assertThat(cc.getTotalNoOfPackages()).isNull();
            assertThat(cc.getSpecies()).isNull();
        }

        @Test
        void copyNotification_shouldCreateNewDraftFromAmendSource() {
            // Regression: AMEND notifications were rejected by copyNotification's
            // status guard before AMEND was added to the allow-list (EUDPA-171).
            String sourceRef = "GBN-AG-26-AMD001";
            String newRef = "GBN-AG-26-NEW-AMD";
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .notification(Notification.builder()
                    .origin(new Origin("IE", "no", "INT-REF-DO-NOT-COPY"))
                    .build())
                .status(AMEND)
                .notification(Notification.builder().build())
                .concurrencyToken(0L)
                .build();

            NotificationAggregate created = NotificationAggregate.builder()
                .referenceNumber(newRef)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            when(notificationRepository.save(any(NotificationAggregate.class))).thenReturn(created);

            NotificationAggregate result = notificationService.copyNotification(sourceRef, 0L, "trace", null);

            assertThat(result.getReferenceNumber()).isEqualTo(newRef);
            assertThat(result.getStatus()).isEqualTo(DRAFT);
        }

        @Test
        void copyNotification_shouldThrowNotFoundException_whenSourceNotFound() {
            // Given
            String sourceRef = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> notificationService.copyNotification(sourceRef, 0L, "trace", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(sourceRef);

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void copyNotification_shouldThrowBadRequestException_whenSourceIsDeleted() {
            // Given
            String sourceRef = "GBN-AG-26-DELETED";
            NotificationAggregate deleted = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DELETED)
                .notification(Notification.builder().build())
                .build();
            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(deleted));

            // When / Then
            assertThatThrownBy(() -> notificationService.copyNotification(sourceRef, 0L, "trace", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DELETED");

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void copyNotification_shouldThrowIllegalState_whenExpectedConcurrencyTokenIsNull() {
            // Given — a valid source with a real concurrencyToken.
            String sourceRef = "GBN-AG-26-NULLEV";
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DRAFT)
                .notification(Notification.builder().build())
                .concurrencyToken(5L)
                .build();
            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));

            // When / Then — a null expectedVersion is a caller-programming error; Spring
            // rejects it at the HTTP boundary (@RequestParam Long is required), and the
            // service defends the same invariant.
            assertThatThrownBy(() -> notificationService.copyNotification(sourceRef, null, "trace", null))
                .isInstanceOf(IllegalStateException.class);

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void copyNotification_shouldThrowIllegalState_whenPersistedConcurrencyTokenIsNull() {
            // Given — a source read back with no concurrencyToken (pre-EUDPA-314 shape). All
            // notifications written after this ticket carry one; a null here means data-integrity trouble.
            String sourceRef = "GBN-AG-26-NULLPV";
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DRAFT)
                .notification(Notification.builder().build())
                .build();
            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));

            // When / Then
            assertThatThrownBy(() -> notificationService.copyNotification(sourceRef, 0L, "trace", null))
                .isInstanceOf(IllegalStateException.class);

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void copyNotification_shouldEmitNotificationCreated_forNewNotification() {
            // Given
            String sourceRef = "GBN-AG-26-CPY-SRC";
            String newRef = "GBN-AG-26-CPY-NEW";
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DRAFT)
                .notification(Notification.builder().build())
                .concurrencyToken(0L)
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.copyNotification(sourceRef, 0L, "trace", null);

            // Then — the new notification emits NOTIFICATION_CREATED; the source produces no event
            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_CREATED), any(), any());
            verifyNoMoreInteractions(outboxService);
        }
    }

    // Fulfilments handling. Covers the PUT/replace path, the amend/cancelAmend/submit snapshot
    // semantics, and copy carrying fulfilments.
    @Nested
    class Fulfilments {

        @BeforeEach
        void setUp() {
            // For tests that go through writeWithOutbox (submit path).
            lenient().when(lockProvider.lock(any()))
                .thenReturn(Optional.of(mock(SimpleLock.class)));
        }

        @Test
        void replace_shouldReplaceContentIncludingFulfilments_whenDraft() {
            // Given
            String ref = "GBN-AG-26-REP001";
            NotificationAggregate existing = NotificationAggregate.builder()
                .id("db-id-1")
                .referenceNumber(ref)
                .status(DRAFT)
                .notification(Notification.builder()
                    .origin(new Origin("FR", "no", "OLD"))
                    .build())
                .build();
            List<Document> newFulfilments = List.of(new Document("obligationId", "abc"));
            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(ref)
                .concurrencyToken(0L)
                .origin(new Origin("GB", "no", "NEW"))
                .fulfilments(newFulfilments)
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            NotificationAggregate result = notificationService.replace(ref, dto, "trace", null);

            // Then
            assertThat(result.getNotification().getOrigin().getCountryCode()).isEqualTo("GB");
            assertThat(result.getFulfilments()).isEqualTo(newFulfilments);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(existing);
            // Emits NOTIFICATION_EDITED on every save (EUDPA-304) — see replace() Javadoc.
            verify(outboxService).appendEvent(
                any(NotificationAggregate.class), eq(OutboxEventType.NOTIFICATION_EDITED), eq("trace"), any());
        }

        @Test
        void replace_shouldReplaceContentIncludingFulfilments_whenAmend() {
            // Given
            String ref = "GBN-AG-26-REP002";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(ref)
                .status(AMEND)
                .notification(Notification.builder().build())
                .build();
            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(ref)
                .concurrencyToken(0L)
                .origin(new Origin("GB", "no", "AMEND-EDIT"))
                .fulfilments(List.of(new Document("obligationId", "xyz")))
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            NotificationAggregate result = notificationService.replace(ref, dto, "trace", null);

            // Then
            assertThat(result.getStatus()).isEqualTo(AMEND);
            assertThat(result.getNotification().getOrigin().getInternalReference()).isEqualTo("AMEND-EDIT");
            assertThat(result.getFulfilments()).hasSize(1);
        }

        @Test
        void replace_shouldThrowBadRequest_whenSubmitted() {
            // Given
            String ref = "GBN-AG-26-REP003";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(ref)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();
            NotificationDto dto = NotificationDto.builder().referenceNumber(ref).build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(existing));

            // When / Then
            assertThatThrownBy(() -> notificationService.replace(ref, dto, "trace", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SUBMITTED");
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void replace_shouldThrowBadRequest_whenDeleted() {
            // Given
            String ref = "GBN-AG-26-REP004";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(ref)
                .status(DELETED)
                .notification(Notification.builder().build())
                .build();
            NotificationDto dto = NotificationDto.builder().referenceNumber(ref).build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(existing));

            // When / Then
            assertThatThrownBy(() -> notificationService.replace(ref, dto, "trace", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DELETED");
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void replace_shouldThrowNotFound_whenReferenceUnknown() {
            when(notificationRepository.findByReferenceNumber("GBN-AG-26-ABSENT"))
                .thenReturn(Optional.empty());
            NotificationDto dto = NotificationDto.builder().build();

            assertThatThrownBy(
                () -> notificationService.replace("GBN-AG-26-ABSENT", dto, "trace", null))
                .isInstanceOf(NotFoundException.class);
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void replace_shouldThrowBadRequest_whenConcurrencyTokenIsNull() {
            // Given — a notification
            String ref = "GBN-AG-26-NULLVR";
            NotificationAggregate existing = NotificationAggregate.builder()
                .id("db-id-nv")
                .referenceNumber(ref)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .concurrencyToken(3L)
                .build();
            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(existing));

            //When a concurrency token is not supplied
            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(ref)
                .origin(new Origin("GB", "no", "REF"))
                .build();

            //Then the update should fail 
            assertThatThrownBy(() -> notificationService.replace(ref, dto, "trace", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("concurrencyToken");

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void amend_shouldSnapshotFulfilmentsIntoBaseline() {
            // Given
            String ref = "GBN-AG-26-AMD001";
            List<Document> fulfilments = new ArrayList<>(
                List.of(new Document("obligationId", "abc")));
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("db-id-a")
                .referenceNumber(ref)
                .status(SUBMITTED)
                .notification(Notification.builder()
                    .origin(new Origin("GB", "no", "REF"))
                    .build())
                .fulfilments(fulfilments)
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            NotificationAggregate result = notificationService.amendNotification(ref, "trace", null);

            // Then
            assertThat(result.getStatus()).isEqualTo(AMEND);
            assertThat(result.getSubmittedFulfilmentsBaseline()).isEqualTo(fulfilments);
            // Defensive copy — mutating source after snapshot must not affect baseline.
            fulfilments.add(new Document("obligationId", "post-snapshot"));
            assertThat(result.getSubmittedFulfilmentsBaseline()).hasSize(1);
        }

        @Test
        void submit_shouldFreezeNotificationContentIntoBaseline_withMutationIndependence() {
            // Given — a draft whose content will be frozen at submit. The freeze moved here from
            // amend-start: capturing at amend would snapshot references that resolve to today's
            // addresses. The deep-clone independence this test guards is unchanged by that move.
            String ref = "GBN-AG-26-AMD002";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("db-id-b")
                .referenceNumber(ref)
                .status(DRAFT)
                .notification(Notification.builder()
                    .origin(new Origin("GB", "no", "ORIGINAL"))
                    .commodity(Commodity.builder().name("Cattle").build())
                    .reasonForImport("PERMANENT")
                    .cphNumber("12/345/6789")
                    .build())
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            NotificationAggregate result = notificationService.submitNotification(ref, "trace", null);

            // Then — the baseline captured the content as submitted.
            assertThat(result.getSubmittedNotificationBaseline()).isNotNull();
            assertThat(result.getSubmittedNotificationBaseline().getOrigin().getInternalReference())
                .isEqualTo("ORIGINAL");
            assertThat(result.getSubmittedNotificationBaseline().getCommodity().getName())
                .isEqualTo("Cattle");

            // And — the baseline is a distinct object graph: mutating the live notification (as a
            // trader would during a later amendment) must not bleed through into the freeze.
            result.getNotification().getOrigin().setInternalReference("EDITED");
            result.getNotification().getCommodity().setName("MutatedCattle");
            result.getNotification().setReasonForImport("CHANGED");
            result.getNotification().setCphNumber("99/999/9999");

            assertThat(result.getSubmittedNotificationBaseline().getOrigin().getInternalReference())
                .isEqualTo("ORIGINAL");
            assertThat(result.getSubmittedNotificationBaseline().getCommodity().getName())
                .isEqualTo("Cattle");
            assertThat(result.getSubmittedNotificationBaseline().getReasonForImport())
                .isEqualTo("PERMANENT");
            assertThat(result.getSubmittedNotificationBaseline().getCphNumber())
                .isEqualTo("12/345/6789");
        }

        @Test
        void cancelAmend_shouldRestoreFulfilmentsAndPreserveOriginalSubmittedAt() {
            // Given — an in-flight amendment carries the original submittedAt (writeWithOutbox
            // does not touch it on the SUBMITTED -> AMEND transition), so cancel-amend must
            // return that same timestamp untouched.
            String ref = "GBN-AG-26-CAN001";
            LocalDateTime originalSubmittedAt = LocalDateTime.of(2026, Month.APRIL, 15, 10, 0);
            List<Document> priorFulfilments = List.of(new Document("obligationId", "prior"));
            Notification baseline = Notification.builder()
                .origin(new Origin("GB", "no", "ORIGINAL"))
                .build();
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("db-id-c")
                .referenceNumber(ref)
                .status(AMEND)
                .notification(Notification.builder()
                    .origin(new Origin("FR", "yes", "EDITED"))
                    .build())
                .submittedNotificationBaseline(baseline)
                .submittedFulfilmentsBaseline(new ArrayList<>(priorFulfilments))
                .fulfilments(List.of(new Document("obligationId", "in-flight-edit")))
                .submittedAt(originalSubmittedAt)
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            NotificationAggregate result = notificationService.cancelAmendNotification(ref, "trace", null);

            // Then
            assertThat(result.getStatus()).isEqualTo(SUBMITTED);
            // The content baseline survives the cancel — it is the read source once back at
            // SUBMITTED. The fulfilments baseline is an amend-only scratchpad and is discarded.
            assertThat(result.getSubmittedNotificationBaseline()).isSameAs(baseline);
            assertThat(result.getSubmittedFulfilmentsBaseline()).isNull();
            assertThat(result.getFulfilments()).isEqualTo(priorFulfilments);
            assertThat(result.getSubmittedAt()).isEqualTo(originalSubmittedAt);
            assertThat(result.getNotification().getOrigin().getInternalReference()).isEqualTo("ORIGINAL");
        }

        @Test
        void deepCopyFulfilments_shouldProduceIndependentCopy_underNestedMutation() {
            // Given — a fulfilments payload representative of what the frontend PUTs: an outer
            // entry with an obligationId scalar plus a nested list of record Documents that carry
            // their own fields. This is the shape a shallow copy would previously leave aliased
            // at the inner-Document level, so mutating the original could surface on the baseline.
            Document nestedRecord = new Document("fulfilmentId", "line0").append("value", "5");
            Document outer = new Document("obligationId", "packages")
                .append("records", new ArrayList<>(List.of(nestedRecord)));
            List<Document> source = new ArrayList<>(List.of(outer));

            // When
            List<Document> copy = NotificationService.deepCopyFulfilments(source);

            // And — mutate the original at every nesting level after the copy is taken
            source.get(0).put("obligationId", "mutated");
            source.get(0).getList("records", Document.class).add(new Document("fulfilmentId", "line1"));
            source.get(0).getList("records", Document.class).get(0).put("value", "999");

            // Then — the copy is a different list containing different Documents, and none of the
            // post-copy mutations on the original bleed through at any nesting depth.
            assertThat(copy).isNotSameAs(source);
            Document copiedOuter = copy.get(0);
            assertThat(copiedOuter).isNotSameAs(outer);
            assertThat(copiedOuter.getString("obligationId")).isEqualTo("packages");
            assertThat(copiedOuter.getList("records", Document.class)).hasSize(1);
            assertThat(copiedOuter.getList("records", Document.class).get(0).getString("value")).isEqualTo("5");
        }

        @Test
        void submittedAt_shouldReflectLatestSubmission_acrossFullAmendCycle() {
            // Given — a fresh DRAFT that is submitted, amended, then re-submitted. This pins the
            // agreed semantics for submittedAt: it tracks the LATEST submission event (see the
            // Javadoc on NotificationAggregate#submittedAt), not the original submit time. That is what
            // downstream consumers of the outbox event's submittedAt field see for a
            // resubmitted notification. Cancel-amend, by contrast, preserves the original —
            // covered by cancelAmend_shouldRestoreFulfilmentsAndPreserveOriginalSubmittedAt.
            String ref = "GBN-AG-26-SBMT02";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("db-id-cycle")
                .referenceNumber(ref)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When — DRAFT -> SUBMITTED (first submit)
            NotificationAggregate afterFirstSubmit = notificationService.submitNotification(ref, "t1", null);
            LocalDateTime firstSubmittedAt = afterFirstSubmit.getSubmittedAt();
            assertThat(firstSubmittedAt).isNotNull();

            // And — SUBMITTED -> AMEND (submittedAt preserved through the transition)
            NotificationAggregate afterAmend = notificationService.amendNotification(ref, "t2", null);
            assertThat(afterAmend.getSubmittedAt()).isEqualTo(firstSubmittedAt);

            // And — AMEND -> SUBMITTED (resubmit updates submittedAt to the new submission moment)
            NotificationAggregate afterResubmit = notificationService.submitNotification(ref, "t3", null);

            // Then — submittedAt reflects the RESUBMISSION, not the original submit.
            assertThat(afterResubmit.getSubmittedAt()).isNotNull();
            assertThat(afterResubmit.getSubmittedAt()).isAfterOrEqualTo(firstSubmittedAt);
        }

        @Test
        void submit_shouldSetSubmittedAt_replaceContentBaseline_andClearFulfilmentsBaseline_whenSubmittingFromAmend() {
            // Given
            String ref = "GBN-AG-26-SBM001";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("db-id-s")
                .referenceNumber(ref)
                .status(AMEND)
                .notification(Notification.builder()
                    .origin(new Origin("FR", "yes", "AMENDED"))
                    .build())
                .submittedNotificationBaseline(Notification.builder()
                    .origin(new Origin("GB", "no", "PREVIOUS-SUBMIT"))
                    .build())
                .submittedFulfilmentsBaseline(new ArrayList<>(
                    List.of(new Document("obligationId", "prior"))))
                .fulfilments(List.of(new Document("obligationId", "current")))
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            NotificationAggregate result = notificationService.submitNotification(ref, "trace", null);

            // Then
            assertThat(result.getStatus()).isEqualTo(SUBMITTED);
            assertThat(result.getSubmittedAt()).isNotNull();
            // The content baseline is REPLACED by a fresh freeze of what was just submitted, not
            // cleared — a re-submitted notification is frozen at its latest submission.
            assertThat(result.getSubmittedNotificationBaseline()).isNotNull();
            assertThat(result.getSubmittedNotificationBaseline().getOrigin().getInternalReference())
                .isEqualTo("AMENDED");
            // The fulfilments baseline stays transient and is discarded on submit.
            assertThat(result.getSubmittedFulfilmentsBaseline()).isNull();
            // Fulfilments are the in-flight edit, NOT the baseline (submit-from-amend accepts the edit).
            assertThat(result.getFulfilments()).extracting("obligationId").containsExactly("current");
        }

        @Test
        void softDelete_shouldBeIdempotent_onAlreadyDeleted() {
            // Given
            String ref = "GBN-AG-26-DEL001";
            NotificationAggregate alreadyDeleted = NotificationAggregate.builder()
                .referenceNumber(ref)
                .status(DELETED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(alreadyDeleted));

            // When
            NotificationAggregate result = notificationService.softDeleteNotification(ref, "trace", null);

            // Then — returns the existing notification unchanged, no save.
            assertThat(result).isSameAs(alreadyDeleted);
            assertThat(result.getStatus()).isEqualTo(DELETED);
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void copy_shouldCarryFulfilmentsAcross() {
            // Given
            String sourceRef = "GBN-AG-26-CPY001";
            String newRef = "GBN-AG-26-CPY002";
            List<Document> sourceFulfilments = new ArrayList<>(
                List.of(new Document("obligationId", "source")));
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .concurrencyToken(0L)
                .notification(Notification.builder()
                    .origin(new Origin("GB", "no", "SOURCE-REF"))
                    .build())
                .fulfilments(sourceFulfilments)
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            ArgumentCaptor<NotificationAggregate> captor = ArgumentCaptor.forClass(NotificationAggregate.class);
            when(notificationRepository.save(captor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.copyNotification(sourceRef, 0L, "trace", null);

            // Then — the saved (new) notification carries the source fulfilments.
            NotificationAggregate saved = captor.getValue();
            assertThat(saved.getReferenceNumber()).isEqualTo(newRef);
            assertThat(saved.getFulfilments()).isEqualTo(sourceFulfilments);
            // Defensive copy — mutating the source after copy must not affect the new notification.
            sourceFulfilments.add(new Document("obligationId", "post-copy"));
            assertThat(saved.getFulfilments()).hasSize(1);
        }
    }

    @Nested
    class SoftDeleteNotification {

        @BeforeEach
        void setUp() {
            lenient().when(lockProvider.lock(any()))
                .thenReturn(Optional.of(mock(SimpleLock.class)));
        }

        @Test
        void softDeleteNotification_shouldSetStatusToDeletedAndSave_whenDraft() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC123";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            NotificationAggregate result = notificationService.softDeleteNotification(referenceNumber, "trace", null);

            // Then
            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notificationAggregate);
        }

        @Test
        void softDeleteNotification_shouldSetStatusToDeletedAndSave_whenSubmitted() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC456";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-002")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            NotificationAggregate result = notificationService.softDeleteNotification(referenceNumber, "trace", null);

            // Then
            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notificationAggregate);
        }

        @Test
        void softDeleteNotification_shouldSetStatusToDeletedAndSave_whenAmend() {
            // Regression: AMEND notifications were rejected by softDelete's
            // status guard before AMEND was added to the allow-list (EUDPA-171).
            // The AMEND view page renders a Delete button (AC4) that reaches here.
            String referenceNumber = "GBN-AG-26-ABCAMD";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amend-del")
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.softDeleteNotification(referenceNumber, "trace", null);

            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notificationAggregate);
        }

        @Test
        void softDeleteNotification_shouldEmitNotificationDeleted_whenDraft() {
            // Given
            String referenceNumber = "GBN-AG-26-DEL-DRAFT";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-del-draft")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.softDeleteNotification(referenceNumber, "trace", null);

            // Then — DRAFT → DELETED emits NOTIFICATION_DELETED (no prior submission)
            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_DELETED), any(), any());
        }

        @Test
        void softDeleteNotification_shouldEmitNotificationSubmissionDeleted_whenSubmitted() {
            // Given
            String referenceNumber = "GBN-AG-26-DEL-SUB";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-del-sub")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.softDeleteNotification(referenceNumber, "trace", null);

            // Then — SUBMITTED → DELETED emits NOTIFICATION_SUBMISSION_DELETED
            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_SUBMISSION_DELETED), any(), any());
        }

        @Test
        void softDeleteNotification_shouldEmitNotificationSubmissionDeleted_whenAmend() {
            // Given
            String referenceNumber = "GBN-AG-26-DEL-AMD";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-del-amd")
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.softDeleteNotification(referenceNumber, "trace", null);

            // Then — AMEND → DELETED also emits NOTIFICATION_SUBMISSION_DELETED
            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_SUBMISSION_DELETED), any(), any());
        }

        // NB: soft-delete on an already-DELETED notification is idempotent — returns the existing
        // notification unchanged. See Fulfilments.softDelete_shouldBeIdempotent_onAlreadyDeleted.

        @Test
        void softDeleteNotification_shouldThrowNotFoundException_whenReferenceNumberUnknown() {
            // Given
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> notificationService.softDeleteNotification(referenceNumber, "trace", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(referenceNumber);

            verify(notificationRepository, never()).save(any());
        }
    }

    // Small fluent builder over the {@link NotificationView} record so tests can construct
    // view instances by naming only the fields they care about (nulls elsewhere).
    private static NotificationViewBuilder notificationView() {
        return new NotificationViewBuilder();
    }

    private static final class NotificationViewBuilder {
        private String referenceNumber;
        private NotificationStatus status;
        private LocalDateTime created;
        private Origin origin;
        private Commodity commodity;
        private ConsignmentParty consignor;
        private ConsignmentParty consignee;
        private Transport transport;
        private Notification submittedNotificationBaseline;

        NotificationViewBuilder referenceNumber(String v) { this.referenceNumber = v; return this; }
        NotificationViewBuilder status(NotificationStatus v) { this.status = v; return this; }
        NotificationViewBuilder created(LocalDateTime v) { this.created = v; return this; }
        NotificationViewBuilder origin(Origin v) { this.origin = v; return this; }
        NotificationViewBuilder commodity(Commodity v) { this.commodity = v; return this; }
        NotificationViewBuilder consignor(ConsignmentParty v) { this.consignor = v; return this; }
        NotificationViewBuilder consignee(ConsignmentParty v) { this.consignee = v; return this; }
        NotificationViewBuilder transport(Transport v) { this.transport = v; return this; }
        NotificationViewBuilder submittedNotificationBaseline(Notification v) {
            this.submittedNotificationBaseline = v; return this;
        }

        NotificationView build() {
            return new NotificationView.Data(
                referenceNumber, 0L, status, created, origin, commodity, consignor, consignee,
                transport, submittedNotificationBaseline);
        }
    }
}
