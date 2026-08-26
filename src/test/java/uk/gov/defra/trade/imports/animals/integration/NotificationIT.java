package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.model.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.bson.Document;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.notification.AdditionalDetails;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.CommodityComplement;
import uk.gov.defra.trade.imports.animals.notification.ConsignmentParty;
import uk.gov.defra.trade.imports.animals.notification.MeansOfTransport;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationController;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.SaveNotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationPageResponse;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.NotificationView;
import uk.gov.defra.trade.imports.animals.notification.NotificationFulfilmentsView;
import uk.gov.defra.trade.imports.animals.notification.NotificationService;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberPageResponse;
import uk.gov.defra.trade.imports.animals.notification.Species;
import uk.gov.defra.trade.imports.animals.notification.Transport;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventType;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventRepository;
import uk.gov.defra.trade.imports.animals.outbox.OutboxService;
import uk.gov.defra.trade.imports.animals.utils.NotificationTestData;

class NotificationIT extends IntegrationBase {

    private static final String NOTIFICATION_ENDPOINT = "/notifications";
    private static final String ADMIN_SECRET_HEADER = "Trade-Imports-Animals-Admin-Secret";
    private static final String VALID_ADMIN_SECRET = "test-admin-secret";
    private static final String HEADER_TRACE_ID = NotificationController.HEADER_TRACE_ID;
    private static final String REF_FORMAT_REGEX = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN;
    private static final String NONEXISTENT_REF = "GBN-AG-00-000000";
    /** The submitting actor's organisation, whose address book the outbox resolve reads. */
    private static final String ORG_ID = "5900002";
    private static final String ADDRESS_ID = "665f1c2ab3e4d51a2c9d0e77";
    private static final String ADDRESS_BOOK_JSON = """
        {
          "id": "665f1c2ab3e4d51a2c9d0e77",
          "name": "Astra Rosales",
          "addressLine1": "43 East Hague Extension",
          "addressLine2": null,
          "townOrCity": "Vernier",
          "county": "Soleure",
          "postcode": "30055",
          "countryCode": "CH",
          "phone": "+41 22 000 0000",
          "email": "astra@example.com",
          "deleted": false
        }
        """;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private AccompanyingDocumentRepository accompanyingDocumentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        auditRepository.deleteAll();
        accompanyingDocumentRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    void post_shouldMapAllFieldsToNotificationAndSave() {
        // Given
        Species species = NotificationTestData.species();
        CommodityComplement complement = new CommodityComplement("LIVE", 10, 5, List.of(species));
        Commodity commodity = Commodity.builder()
            .name("Live bovine animals")
            .commodityComplement(List.of(complement))
            .build();
        Transport transport = Transport.builder()
            .portOfEntry("GBFXT")
            .arrivalDate(LocalDate.of(2026, Month.APRIL, 22))
            .meansOfTransport(MeansOfTransport.RAILWAY)
            .transportIdentification("Train 4471, wagon 12")
            .transportDocumentReference("CIM-CONSIGNMENT-001")
            .transitedCountries(List.of("FR", "DE"))
            .build();
        NotificationDto notificationDto = NotificationDto.builder()
            .origin(new Origin("GB", "true", "REF-001"))
            .commodity(commodity)
            .reasonForImport("PERMANENT")
            .additionalDetails(new AdditionalDetails("HUMAN_CONSUMPTION", "true"))
            .cphNumber("22/123/4567")
            .transport(transport)
            .build();

        // When
        NotificationAggregate created = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDto))
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult().getResponseBody();

        // Then — verify response
        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getReferenceNumber()).matches(REF_FORMAT_REGEX);
        assertNotificationMappedFields(created);

        // Verify persisted — reload via API
        NotificationAggregate persisted = notificationRepository.findByReferenceNumber(created.getReferenceNumber())
            .orElseThrow();
        assertThat(persisted.getId()).isEqualTo(created.getId());
        assertNotificationMappedFields(persisted);
    }

    @Test
    void findAll_shouldReturnAllNotifications() {
        // Given - create multiple notifications
        NotificationDto notificationDto1 = createNotificationDto("GB", "Live cattle");
        NotificationDto notificationDto2 = createNotificationDto("IE", "Live sheep");
        NotificationDto notificationDto3 = createNotificationDto("FR", "Live pigs");

        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT).bodyValue(SaveNotificationDto.of(notificationDto1)).exchange();
        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT).bodyValue(SaveNotificationDto.of(notificationDto2)).exchange();
        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT).bodyValue(SaveNotificationDto.of(notificationDto3)).exchange();

        // When — page-size is 2 in integration-test profile, so page 0 has 2 items
        NotificationPageResponse page0 = findAllNotificationsPage(1);
        NotificationPageResponse page1 = findAllNotificationsPage(2);

        // Then
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.content()).hasSize(2);
        assertThat(page1.content()).hasSize(1);
    }

    @Test
    void findAll_shouldReturnEmptyPage_whenNoNotifications() {
        // When
        NotificationPageResponse page = findAllNotificationsPage();

        // Then
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.page()).isEqualTo(1);
    }

    @Test
    void findAll_shouldReturnMatchingNotification_whenReferenceNumberProvided() {
        NotificationDto notificationDto1 = createNotificationDto("GB", "Live cattle");
        NotificationDto notificationDto2 = createNotificationDto("IE", "Live sheep");

        String matchingRef = webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDto1)).exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult().getResponseBody().getReferenceNumber();

        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDto2)).exchange()
            .expectStatus().isOk();

        NotificationPageResponse page = findAllNotificationsPage(1, null, matchingRef);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().getReferenceNumber()).isEqualTo(matchingRef);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void findAll_shouldReturnEmptyPage_whenReferenceNumberUnknown() {
        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle"))).exchange()
            .expectStatus().isOk();

        NotificationPageResponse page =
            findAllNotificationsPage(1, null, "GBN-AG-26-ZZZZZZ");

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void findAll_shouldReturnEmptyPage_whenReferenceNumberIsDeleted() {
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", referenceNumber)
            .exchange().expectStatus().isOk();

        NotificationPageResponse page = findAllNotificationsPage(1, null, referenceNumber);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void findAll_shouldReturnEmptyPage_whenReferenceNumberInvalid() {
        NotificationPageResponse page =
            findAllNotificationsPage(1, null, "invalid-ref");

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void findAll_shouldReturnNotificationsOrderedByArrivalDateDescending() {
        // Given
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDtoWithArrivalDate("GB", LocalDate.of(2026, Month.JANUARY, 10))))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDtoWithArrivalDate("IE", LocalDate.of(2026, Month.JUNE, 15))))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDtoWithArrivalDate("FR", LocalDate.of(2026, Month.MARCH, 1))))
            .exchange()
            .expectStatus().isOk();

        // When — page-size is 2 in integration-test profile; all 3 fit across 2 pages
        NotificationPageResponse page0 = findAllNotificationsPage(1);
        NotificationPageResponse page1 = findAllNotificationsPage(2);

        // Then — arrival dates across pages are ordered descending
        assertThat(page0.content()).hasSize(2);
        assertThat(page0.content())
            .extracting(n -> n.getTransport().getArrivalDate())
            .containsExactly(
                LocalDate.of(2026, Month.JUNE, 15),
                LocalDate.of(2026, Month.MARCH, 1));
        assertThat(page1.content()).hasSize(1);
        assertThat(page1.content().getFirst().getTransport().getArrivalDate())
            .isEqualTo(LocalDate.of(2026, Month.JANUARY, 10));
    }

    @Test
    void findAll_shouldReturnNotificationsOrderedByCreatedAtAscending_whenSortRequested() {
        String refOlder = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDtoWithArrivalDate("GB", LocalDate.of(2026, Month.JANUARY, 10))))
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult()
            .getResponseBody()
            .getReferenceNumber();

        String refNewer = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDtoWithArrivalDate("IE", LocalDate.of(2026, Month.JUNE, 15))))
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult()
            .getResponseBody()
            .getReferenceNumber();

        NotificationAggregate older = notificationRepository.findByReferenceNumber(refOlder).orElseThrow();
        older.setCreated(LocalDateTime.of(2026, Month.JANUARY, 1, 10, 0));
        notificationRepository.save(older);

        NotificationAggregate newer = notificationRepository.findByReferenceNumber(refNewer).orElseThrow();
        newer.setCreated(LocalDateTime.of(2026, Month.JANUARY, 2, 10, 0));
        notificationRepository.save(newer);

        NotificationPageResponse page = findAllNotificationsPage(1, "createdAt,asc");

        assertThat(page.content()).hasSize(2);
        assertThat(page.content().getFirst().getReferenceNumber()).isEqualTo(refOlder);
        assertThat(page.content().get(1).getReferenceNumber()).isEqualTo(refNewer);
    }

    @Test
    void findAll_notifications_with_null_arrivalDate_list_beforeThoseWithValid_ArrivalDates_whenSortedAscending() {
        // Given — mix of dated and null/missing transport.arrivalDate notifications
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDtoWithArrivalDate("IE", LocalDate.of(2026, Month.JUNE, 15))))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDtoWithArrivalDate("FR", LocalDate.of(2026, Month.JANUARY, 10))))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDtoWithTransportButNoArrivalDate("DE")))
            .exchange()
            .expectStatus().isOk();

        // When — page-size=2; ascending sort must place nulls first (NULLS FIRST)
        NotificationPageResponse page0 = findAllNotificationsPage(1, "arrivalDate,asc");
        NotificationPageResponse page1 = findAllNotificationsPage(2, "arrivalDate,asc");

        // Then — null arrival dates first, then dated items in ascending order
        assertThat(page0.totalElements()).isEqualTo(4);
        assertThat(page0.content()).hasSize(2);
        assertThat(page0.content())
            .extracting(this::extractArrivalDate)
            .containsOnlyNulls();
        assertThat(page1.content()).hasSize(2);
        assertThat(page1.content())
            .extracting(n -> n.getTransport().getArrivalDate())
            .containsExactly(LocalDate.of(2026, Month.JANUARY, 10), LocalDate.of(2026, Month.JUNE, 15));
    }

    @Test
    void findAll_notifications_with_null_arrivalDate_list_afterThoseWithValid_ArrivalDates_whenSortedDescending() {
        // Given — mix of dated and null/missing transport.arrivalDate notifications
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDtoWithArrivalDate("IE", LocalDate.of(2026, Month.JUNE, 15))))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDtoWithArrivalDate("FR", LocalDate.of(2026, Month.JANUARY, 10))))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDtoWithTransportButNoArrivalDate("DE")))
            .exchange()
            .expectStatus().isOk();

        // When — page-size=2; descending sort must place nulls last (NULLS LAST)
        NotificationPageResponse page0 = findAllNotificationsPage(1, "arrivalDate,desc");
        NotificationPageResponse page1 = findAllNotificationsPage(2, "arrivalDate,desc");

        // Then — dated items first in descending order, then null arrival dates
        assertThat(page0.totalElements()).isEqualTo(4);
        assertThat(page0.content()).hasSize(2);
        assertThat(page0.content())
            .extracting(n -> n.getTransport().getArrivalDate())
            .containsExactly(LocalDate.of(2026, Month.JUNE, 15), LocalDate.of(2026, Month.JANUARY, 10));
        assertThat(page1.content()).hasSize(2);
        assertThat(page1.content())
            .extracting(this::extractArrivalDate)
            .containsOnlyNulls();
    }

    @Test
    void findAll_notifications_with_draft_and_submitted_statuses() {
        // Given — mix of DRAFT (with and without arrival date) and SUBMITTED
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange()
            .expectStatus().isOk();

        String submittedRef = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDtoWithArrivalDate("IE", LocalDate.of(2026, Month.JUNE, 15))))
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult()
            .getResponseBody()
            .getReferenceNumber();

        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDtoWithArrivalDate("FR", LocalDate.of(2026, Month.MARCH, 1))))
            .exchange()
            .expectStatus().isOk();

        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", submittedRef)
            .exchange()
            .expectStatus().isOk();

        // When — page-size=2 in test profile
        NotificationPageResponse page0 = findAllNotificationsPage(1);
        NotificationPageResponse page1 = findAllNotificationsPage(2);

        // Then — no status filter; drafts and submitted both appear, ordered by arrival date desc
        assertThat(page0.totalElements()).isEqualTo(3);
        List<NotificationView> all = new java.util.ArrayList<>(page0.content());
        all.addAll(page1.content());

        assertThat(all).hasSize(3);
        assertThat(all)
            .extracting(NotificationView::getStatus)
            .containsExactlyInAnyOrder(
                NotificationStatus.DRAFT,
                NotificationStatus.DRAFT,
                NotificationStatus.SUBMITTED);
        assertThat(all.getFirst().getReferenceNumber()).isEqualTo(submittedRef);
        assertThat(all.getFirst().getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(all.getFirst().getTransport().getArrivalDate())
            .isEqualTo(LocalDate.of(2026, Month.JUNE, 15));
        assertThat(all.get(1).getTransport().getArrivalDate())
            .isEqualTo(LocalDate.of(2026, Month.MARCH, 1));
        assertThat(extractArrivalDate(all.get(2))).isNull();
    }

    @Test
    void findAll_shouldExcludeDeletedNotifications() {
        // Given — create three notifications, submit one, soft-delete one
        String draftRef = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        String submittedRef = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("IE", "Live sheep")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        String deletedRef = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("FR", "Live pigs")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", submittedRef)
            .exchange().expectStatus().isOk();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", deletedRef)
            .exchange().expectStatus().isOk();

        // When
        List<NotificationView> notifications = findAllNotificationsPage(1).content();

        // Then — only DRAFT and SUBMITTED are returned; DELETED is excluded
        assertThat(notifications).hasSize(2);
        assertThat(notifications)
            .extracting(NotificationView::getReferenceNumber)
            .containsExactlyInAnyOrder(draftRef, submittedRef);
        assertThat(notifications)
            .extracting(NotificationView::getStatus)
            .doesNotContain(NotificationStatus.DELETED);
    }

    @Test
    void post_shouldAllowMultipleNotificationsWithNullReferenceNumber() {
        // Given - create multiple notifications without explicitly setting referenceNumber
        NotificationDto notificationDto1 = createNotificationDto("GB", "Live cattle");
        NotificationDto notificationDto2 = createNotificationDto("IE", "Live sheep");
        NotificationDto notificationDto3 = createNotificationDto("FR", "Live pigs");

        // When - save all notifications
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDto1))
            .exchange()
            .expectStatus().isOk();

        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDto2))
            .exchange()
            .expectStatus().isOk();

        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(notificationDto3))
            .exchange()
            .expectStatus().isOk();

        // Then - verify all notifications were created with generated referenceNumbers
        NotificationPageResponse pageResponse = findAllNotificationsPage(1);
        NotificationPageResponse page1Response = findAllNotificationsPage(2);
        List<NotificationView> allNotifications = new java.util.ArrayList<>(pageResponse.content());
        allNotifications.addAll(page1Response.content());
        assertThat(allNotifications).hasSize(3);
        assertThat(allNotifications)
            .extracting(NotificationView::getReferenceNumber)
            .allMatch(ref -> ref != null && ref.startsWith("GBN-AG-"));
        assertThat(allNotifications)
            .extracting(n -> n.getOrigin().getCountryCode())
            .containsExactlyInAnyOrder("GB", "IE", "FR");
    }

    @Test
    void post_shouldUpdateAllFieldsOnExistingNotification() {
        // Given — create a notification with initial values for all fields
        Species initialSpecies = new Species("OVI", "Ovine", 5, 2, "UK09876543210", "UK0987654300888");
        CommodityComplement initialComplement = new CommodityComplement("LIVE", 5, 2, List.of(initialSpecies));
        NotificationDto initial = NotificationDto.builder()
            .origin(new Origin("IE", "false", "REF-initial"))
            .commodity(Commodity.builder()
                .name("Live ovine animals")
                .commodityComplement(List.of(initialComplement))
                .build())
            .reasonForImport("TRANSIT")
            .additionalDetails(new AdditionalDetails("OTHER", "false"))
            .cphNumber("11/111/1111")
            .transport(Transport.builder().portOfEntry("GBBEL").arrivalDate(LocalDate.of(2026, Month.JANUARY, 1)).build())
            .build();

        NotificationAggregate created = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(SaveNotificationDto.of(initial))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody();
        String referenceNumber = created.getReferenceNumber();

        // When — update every field with new values
        Species updatedSpecies = NotificationTestData.species();
        CommodityComplement updatedComplement = new CommodityComplement("LIVE", 10, 5, List.of(updatedSpecies));
        NotificationDto updateDto = NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .concurrencyToken(created.getConcurrencyToken())
            .origin(new Origin("GB", "true", "REF-updated"))
            .commodity(Commodity.builder()
                .name("Live bovine animals")
                .commodityComplement(List.of(updatedComplement))
                .build())
            .reasonForImport("PERMANENT")
            .additionalDetails(new AdditionalDetails("HUMAN_CONSUMPTION", "true"))
            .cphNumber("22/123/4567")
            .transport(Transport.builder()
                .portOfEntry("GBFXT")
                .arrivalDate(LocalDate.of(2026, Month.APRIL, 22))
                .meansOfTransport(MeansOfTransport.RAILWAY)
                .transportIdentification("Train 4471, wagon 12")
                .transportDocumentReference("CIM-CONSIGNMENT-001")
                .transitedCountries(List.of("FR", "DE"))
                .build())
            .build();

        NotificationAggregate updated = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(SaveNotificationDto.of(updateDto))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody();

        // Then — verify response reflects updated values
        assertThat(updated).isNotNull();
        assertThat(updated.getReferenceNumber()).isEqualTo(referenceNumber);
        assertNotificationMappedFields(updated, "REF-updated");

        // Verify only one notification exists and reload via API
        List<NotificationView> all = findAllNotifications();
        assertThat(all).hasSize(1);
        NotificationAggregate persisted = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertNotificationMappedFields(persisted, "REF-updated");
    }

    @Test
    void post_shouldClearTransitedCountries_whenUpdatedToMeansThatDoesNotRequireTransit() {
        NotificationDto initial = NotificationDto.builder()
            .origin(new Origin("GB", "true", "REF-001"))
            .commodity(Commodity.builder().name("Live bovine animals").build())
            .transport(Transport.builder()
                .portOfEntry("GBFXT")
                .arrivalDate(LocalDate.of(2026, Month.APRIL, 22))
                .meansOfTransport(MeansOfTransport.ROAD_VEHICLE)
                .transportIdentification("HG12 ABC")
                .transportDocumentReference("CMR-001")
                .transitedCountries(List.of("FR", "DE"))
                .build())
            .build();

        NotificationAggregate created = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(SaveNotificationDto.of(initial))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody();
        String referenceNumber = created.getReferenceNumber();

        NotificationDto updateDto = NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .concurrencyToken(created.getConcurrencyToken())
            .origin(new Origin("GB", "true", "REF-001"))
            .commodity(Commodity.builder().name("Live bovine animals").build())
            .transport(Transport.builder()
                .portOfEntry("GBFXT")
                .arrivalDate(LocalDate.of(2026, Month.APRIL, 22))
                .meansOfTransport(MeansOfTransport.VESSEL)
                .transportIdentification("Vessel Poseidon, voyage 42")
                .transportDocumentReference("BILL-OF-LADING-001")
                .transitedCountries(null)
                .build())
            .build();

        NotificationAggregate updated = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(SaveNotificationDto.of(updateDto))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody();

        assertThat(updated).isNotNull();
        assertThat(updated.getNotification().getTransport().getMeansOfTransport()).isEqualTo(MeansOfTransport.VESSEL);
        assertThat(updated.getNotification().getTransport().getTransitedCountries()).isNullOrEmpty();

        NotificationAggregate persisted = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertThat(persisted.getNotification().getTransport().getTransitedCountries()).isNullOrEmpty();
    }

    @Test
    void delete_shouldDeleteNotifications_whenAllReferenceNumbersExist() {
        // Given — create two notifications
        String ref1 = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        String ref2 = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("IE", "Live sheep")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — delete both by reference number
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header("x-cdp-request-id", "trace-001")
            .header("User-Id", "user-001")
            .bodyValue(List.of(ref1, ref2))
            .exchange()
            .expectStatus().isNoContent();

        // Then — both are gone
        assertThat(findAllNotifications()).isEmpty();
    }

    @Test
    void delete_shouldCreateSuccessAuditRecord_whenNotificationsDeleted() {
        // Given
        String ref = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header("x-cdp-request-id", "trace-audit-success")
            .header("User-Id", "user-audit")
            .bodyValue(List.of(ref))
            .exchange()
            .expectStatus().isNoContent();

        // Then — a SUCCESS audit record is persisted
        List<Audit> audits = auditRepository.findAll();
        assertThat(audits).hasSize(1);
        Audit audit = audits.getFirst();
        assertThat(audit.getResult()).isEqualTo(Result.SUCCESS);
        assertThat(audit.getNotificationReferenceNumbers()).containsExactly(ref);
        assertThat(audit.getNumberOfNotifications()).isEqualTo(1);
        assertThat(audit.getTraceId()).isEqualTo("trace-audit-success");
        assertThat(audit.getUserId()).isEqualTo("user-audit");
        assertThat(audit.getTimestamp()).isNotNull();
    }

    @Test
    void delete_shouldReturn404_whenReferenceNumberDoesNotExist() {
        // When — attempt to delete a non-existent reference number
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header("x-cdp-request-id", "trace-002")
            .header("User-Id", "user-002")
            .bodyValue(List.of(NONEXISTENT_REF))
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(
                Matchers.containsString(NONEXISTENT_REF));
    }

    @Test
    void delete_shouldCreateFailureAuditRecord_whenReferenceNumberDoesNotExist() {
        // When — attempt to delete a non-existent reference number
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header("x-cdp-request-id", "trace-audit-failure")
            .header("User-Id", "user-audit-failure")
            .bodyValue(List.of(NONEXISTENT_REF))
            .exchange()
            .expectStatus().isNotFound();

        // Then — a FAILURE audit record is persisted
        List<Audit> audits = auditRepository.findAll();
        assertThat(audits).hasSize(1);
        Audit audit = audits.getFirst();
        assertThat(audit.getResult()).isEqualTo(Result.FAILURE);
        assertThat(audit.getNotificationReferenceNumbers()).containsExactly(NONEXISTENT_REF);
        assertThat(audit.getTraceId()).isEqualTo("trace-audit-failure");
        assertThat(audit.getUserId()).isEqualTo("user-audit-failure");
    }

    @Test
    void delete_shouldReturn404AndNotDeleteAnything_whenOneReferenceNumberIsMissing() {
        // Given — create one notification
        String existingRef = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("FR", "Live pigs")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — attempt to delete the existing one plus a missing one
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header("x-cdp-request-id", "trace-003")
            .header("User-Id", "user-003")
            .bodyValue(List.of(existingRef, NONEXISTENT_REF))
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString(NONEXISTENT_REF));

        // Then — the existing notification was NOT deleted (all-or-nothing)
        List<NotificationView> remaining = findAllNotifications();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().getReferenceNumber()).isEqualTo(existingRef);
    }

    @Test
    void delete_shouldReturn400_whenListIsEmpty() {
        // When
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .bodyValue(List.of())
            .exchange()
            .expectStatus().isBadRequest();

        // Then — DB state should be unchanged (empty as per @BeforeEach)
        assertThat(findAllNotifications()).isEmpty();
    }

    @Test
    void delete_shouldReturn401_whenAdminSecretHeaderIsMissing() {
        // When — no Trade-Imports-Animals-Admin-Secret header
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .bodyValue(List.of(NONEXISTENT_REF))
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void delete_shouldReturn401_whenAdminSecretHeaderIsIncorrect() {
        // When — wrong secret value
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, "wrong-secret")
            .bodyValue(List.of(NONEXISTENT_REF))
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void submit_shouldTransitionStatusFromDraftToSubmitted() {
        // Given — create a notification (starts as DRAFT)
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — submit the notification
        NotificationAggregate submitted = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult().getResponseBody();

        // Then — status is SUBMITTED
        assertThat(submitted).isNotNull();
        assertThat(submitted.getReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(submitted.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(submitted.getUpdated()).isNotNull();
    }

    @Test
    void amend_shouldTransitionStatusFromSubmittedToAmend() {
        // Given — submitted notification
        String referenceNumber = createAndSubmitNotificationWithFullContent();

        // When
        NotificationAggregate amended = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult().getResponseBody();

        // Then
        assertThat(amended).isNotNull();
        assertThat(amended.getReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(amended.getStatus()).isEqualTo(NotificationStatus.AMEND);
        assertThat(amended.getUpdated()).isNotNull();
    }

    @Test
    void submit_shouldPersistFrozenBaselineInMongo_andAmendShouldRetainIt() {
        // Given — the freeze lands at SUBMIT. It used to be taken when an amendment began, which
        // snapshotted whatever the references resolved to that day rather than what was submitted.
        String referenceNumber = createAndSubmitNotificationWithFullContent();
        NotificationAggregate afterSubmit =
            notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();

        assertThat(afterSubmit.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(afterSubmit.getSubmittedNotificationBaseline()).isNotNull();
        assertThat(afterSubmit.getSubmittedNotificationBaseline().getOrigin().getInternalReference())
            .isEqualTo("INTERNAL-DO-NOT-COPY");
        assertThat(afterSubmit.getSubmittedNotificationBaseline().getOrigin())
            .isNotSameAs(afterSubmit.getNotification().getOrigin());
        assertThat(afterSubmit.getSubmittedNotificationBaseline().getCommodity().getName())
            .isEqualTo(afterSubmit.getNotification().getCommodity().getName());

        // When — an amendment begins
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .exchange()
            .expectStatus().isOk();

        // Then — the same freeze is still in Mongo, carried across rather than re-taken
        NotificationAggregate reloaded =
            notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.AMEND);
        assertThat(reloaded.getSubmittedNotificationBaseline()).isNotNull();
        assertThat(reloaded.getSubmittedNotificationBaseline().getOrigin().getInternalReference())
            .isEqualTo("INTERNAL-DO-NOT-COPY");
    }

    @Test
    void amend_shouldWriteOutboxEventWithCorrectEnvelope() {
        // Given — create and submit a notification
        String referenceNumber = createAndSubmitNotificationWithFullContent();

        // When — amend with a trace ID
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-amend-001")
            .exchange()
            .expectStatus().isOk();

        // Then — created + submitted + amendment-requested events exist in order
        List<OutboxEvent> events = outboxEventRepository.findAll()
            .stream()
            .sorted(java.util.Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationCreated");
        assertThat(events.get(0).getAggregateVersion()).isEqualTo(1L);
        assertThat(events.get(1).getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
        assertThat(events.get(1).getAggregateVersion()).isEqualTo(2L);

        OutboxEvent amendEvent = events.get(2);
        assertThat(amendEvent.getAggregateId())
            .isEqualTo(OutboxService.buildAggregateId(referenceNumber));
        assertThat(amendEvent.getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationAmendmentRequested");
        assertThat(amendEvent.getAggregateVersion()).isEqualTo(3L);
        assertThat(amendEvent.getMetadata().getCorrelationId()).isEqualTo("trace-amend-001");
        assertThat(amendEvent.getMetadata().getSchemaUrl()).isEqualTo(OutboxEventType.NOTIFICATION_AMENDMENT_REQUESTED.schemaUrl());
        assertThat(gbnAgIdentifier(amendEvent)).isEqualTo(referenceNumber);
    }

    /*
     * Submission is the only path that still reads the address book — the dashboard resolves its own
     * names. These two cover it end to end, against a stubbed book, because the unit tests mock the
     * client and so cannot show the wiring (base URL, org header, deserialisation) actually working.
     */

    @Test
    void submit_shouldResolveReferencedParty_intoTheOutboxEvent_withoutRewritingWhatIsStored() {
        // Given — a notification whose consignor is held as an address-book reference
        stubAddressBook(ADDRESS_BOOK_JSON, 200);
        String referenceNumber = createNotificationWithReferencedConsignor();

        // When
        submitAs(referenceNumber, ORG_ID);

        // Then — GBNAG carries the resolved details
        Map<String, Object> consignor = outboxConsignorParty(submittedOutboxEvent());
        assertThat(consignor).containsEntry("name", "Astra Rosales");
        assertThat((Map<String, Object>) consignor.get("postalAddress"))
            .containsEntry("postcodeCode", "30055")
            .containsEntry("cityName", "Vernier");

        // And — storage still holds only the reference. Resolving for transmission must not grow a
        // stale copy of the address beside the reference that exists to avoid exactly that.
        NotificationAggregate stored = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertThat(stored.getNotification().getConsignor()).isEqualTo(ConsignmentParty.reference(ADDRESS_ID));
    }

    @Test
    void submit_shouldFreezeResolvedAddressDetails_thatLaterBookChangesCannotReach() {
        // AC #3/#4 end to end: what a submitted notification shows is fixed at submit, and editing
        // or deleting the address afterwards neither changes it nor errors.
        stubAddressBook(ADDRESS_BOOK_JSON, 200);
        String referenceNumber = createNotificationWithReferencedConsignor();

        submitAs(referenceNumber, ORG_ID);

        // The freeze holds the resolved details AND the id they came from (AC #1/#2)...
        NotificationAggregate afterSubmit = notificationRepository
            .findByReferenceNumber(referenceNumber).orElseThrow();
        ConsignmentParty frozen = afterSubmit.getSubmittedNotificationBaseline().getConsignor();
        assertThat(frozen.getName()).isEqualTo("Astra Rosales");
        assertThat(frozen.getAddress().getPostcode()).isEqualTo("30055");
        assertThat(frozen.getAddress().getTownOrCity()).isEqualTo("Vernier");
        assertThat(frozen.getAddressId()).isEqualTo(ADDRESS_ID);
        // ...while the stored role field keeps the reference alone, the live link an amend re-reads.
        assertThat(afterSubmit.getNotification().getConsignor())
            .isEqualTo(ConsignmentParty.reference(ADDRESS_ID));

        // When — the address is renamed, moved, and then deleted outright in the book
        stubAddressBook(ADDRESS_BOOK_JSON
            .replace("Astra Rosales", "Renamed Since Submission")
            .replace("Vernier", "Carlisle")
            .replace("30055", "CA1 1AA")
            .replace("\"deleted\": false", "\"deleted\": true"), 200);

        // Then — the fulfilments read still serves the submission-time details, and does not error
        NotificationFulfilmentsView view =
            notificationService.findFulfilmentsView(referenceNumber);
        assertThat(view.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(view.getSubmittedNotificationBaseline().getConsignor().getName())
            .isEqualTo("Astra Rosales");
        assertThat(view.getSubmittedNotificationBaseline().getConsignor().getAddress()
            .getTownOrCity()).isEqualTo("Vernier");

        // And — the dashboard row serves the frozen name inline, so nothing resolves it live
        NotificationView row = notificationService.findAll(1, null, referenceNumber)
            .content().getFirst();
        assertThat(row.getConsignor().getName()).isEqualTo("Astra Rosales");
        assertThat(row.getConsignor().getAddressId()).isNull();
        assertThat(row.getSubmittedNotificationBaseline()).isNull();
    }

    @Test
    void submit_shouldReturn400_andEmitNoSubmittedEvent_whenAReferencedPartyCannotBeResolved() {
        // Given — the referenced address has since been deleted. Unlike a read, which would render
        // the role blank, a submit must fail rather than send GBNAG a party with no name.
        stubAddressBook(ADDRESS_BOOK_JSON.replace("\"deleted\": false", "\"deleted\": true"), 200);
        String referenceNumber = createNotificationWithReferencedConsignor();

        // When & Then — the rejection names the role, so the caller knows which one to correct,
        // and carries the address id so the cause is diagnosable.
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .bodyValue(Map.of("organisationId", ORG_ID))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString("consignor"))
            .jsonPath("$.errors.consignor[0]").value(Matchers.containsString(ADDRESS_ID));

        // The draft's own NotificationCreated event stays; only the submission must not be emitted.
        assertThat(outboxEventRepository.findAll())
            .noneMatch(e -> e.getEventType().equals(OutboxEventType.NOTIFICATION_SUBMITTED.value()));
        NotificationAggregate rejected = notificationRepository
            .findByReferenceNumber(referenceNumber).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        // No freeze either: the resolve aborts before the write, so a failed submit cannot leave a
        // half-frozen notification behind.
        assertThat(rejected.getSubmittedNotificationBaseline()).isNull();
    }

    @Test
    void submit_shouldName_everyRole_whenSeveralReferencedAddressesAreDeleted() {
        // Given — two roles reference addresses that have both since been deleted. Reporting only
        // the first would send the submitter round the loop once per bad reference.
        String secondAddressId = "665f1c2ab3e4d51a2c9d0e88";
        String deleted = ADDRESS_BOOK_JSON.replace("\"deleted\": false", "\"deleted\": true");
        stubAddressBookFor(ADDRESS_ID, deleted, 200);
        stubAddressBookFor(secondAddressId, deleted.replace(ADDRESS_ID, secondAddressId), 200);

        NotificationDto dto = createNotificationDto("CH", "Live cattle");
        dto.setConsignor(ConsignmentParty.reference(ADDRESS_ID));
        dto.setImporter(ConsignmentParty.reference(secondAddressId));
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(dto))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When & Then — both roles come back, each with the address it refers to.
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .bodyValue(Map.of("organisationId", ORG_ID))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors.consignor[0]").value(Matchers.containsString(ADDRESS_ID))
            .jsonPath("$.errors.importer[0]").value(Matchers.containsString(secondAddressId));

        // NOTIFICATION_CREATED was written when the draft was created; no NOTIFICATION_SUBMITTED
        // because the submit failed before the outbox write.
        assertThat(outboxEventRepository.findAll())
            .hasSize(1)
            .allMatch(e -> e.getEventType().endsWith("NotificationCreated"));
        assertThat(notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow()
            .getStatus()).isEqualTo(NotificationStatus.DRAFT);
    }

    @Test
    void amend_shouldReturn404_whenReferenceNumberDoesNotExist() {
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(Matchers.containsString(NONEXISTENT_REF));
    }

    @Test
    void amend_shouldReturn400_whenNotificationNotSubmitted() {
        // Given — draft notification
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When / Then
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString("DRAFT"));
    }

    @Test
    void cancelAmend_shouldRestoreBaselineAndRevertStatusInMongo() {
        // Given — submitted notification reloaded from Mongo as the golden baseline
        String referenceNumber = createAndSubmitNotificationWithFullContent();
        NotificationAggregate submittedInMongo = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertThat(submittedInMongo.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);

        // When — start amendment (baseline written to Mongo via the real amend path)
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .exchange().expectStatus().isOk();

        NotificationAggregate inAmendInMongo = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertThat(inAmendInMongo.getStatus()).isEqualTo(NotificationStatus.AMEND);
        assertThat(inAmendInMongo.getSubmittedNotificationBaseline()).isNotNull();
        assertAmendableContentMatches(submittedInMongo.getNotification(), inAmendInMongo.getSubmittedNotificationBaseline());

        // Simulate trader edits persisted to Mongo during AMEND
        inAmendInMongo.getNotification().getOrigin().setInternalReference("EDITED-REF");
        inAmendInMongo.getNotification().getOrigin().setCountryCode("FR");
        inAmendInMongo.getNotification().getCommodity().setName("Changed commodity");
        inAmendInMongo.getNotification().setReasonForImport("changedReason");
        inAmendInMongo.getNotification().setCphNumber("99/999/9999");
        notificationRepository.save(inAmendInMongo);

        NotificationAggregate editedInMongo = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertThat(editedInMongo.getNotification().getOrigin().getInternalReference()).isEqualTo("EDITED-REF");
        assertThat(editedInMongo.getSubmittedNotificationBaseline()).isNotNull();

        // When — cancel amendment via API
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/cancel-amend", referenceNumber)
            .exchange().expectStatus().isOk();

        // Then — reload from Mongo: status reverted, all amendable fields restored, and the
        // baseline RETAINED. Back at SUBMITTED it is the read source again, so clearing it would
        // leave the notification with nothing to read its addresses from.
        NotificationAggregate restoredInMongo = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertThat(restoredInMongo.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(restoredInMongo.getSubmittedNotificationBaseline()).isNotNull();
        assertAmendableContentMatches(submittedInMongo.getNotification(), restoredInMongo.getNotification());
    }

    @Test
    void cancelAmend_shouldReturn404_whenReferenceNumberDoesNotExist() {
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/cancel-amend", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(Matchers.containsString(NONEXISTENT_REF));
    }

    @Test
    void cancelAmend_shouldReturn400_whenNotificationNotInAmendStatus() {
        // Given — submitted notification (not in AMEND)
        String referenceNumber = createAndSubmitNotificationWithFullContent();

        // When / Then
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/cancel-amend", referenceNumber)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString("SUBMITTED"));
    }

    @Test
    void cancelAmend_shouldWriteAmendmentCancelledOutboxEvent() {
        // Given — notification in AMEND: CREATED(v1) + SUBMITTED(v2) + AMENDMENT_REQUESTED(v3)
        String referenceNumber = createAndSubmitNotificationWithFullContent();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .exchange().expectStatus().isOk();

        long eventsBeforeCancel = outboxEventRepository.count();

        // When
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/cancel-amend", referenceNumber)
            .exchange().expectStatus().isOk();

        // Then — NOTIFICATION_AMENDMENT_CANCELLED event added
        assertThat(outboxEventRepository.count()).isEqualTo(eventsBeforeCancel + 1);
        OutboxEvent cancelEvent = outboxEventRepository.findAll().stream()
            .max(java.util.Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .orElseThrow();
        assertThat(cancelEvent.getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationAmendmentCancelled");
    }

    @Test
    void submitFromAmend_shouldReplaceSubmittedBaselineWithTheNewFreeze() {
        // Given — notification amended with edited content
        String referenceNumber = createAndSubmitNotificationWithFullContent();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .exchange().expectStatus().isOk();

        NotificationAggregate inAmend = notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        inAmend.getNotification().getOrigin().setInternalReference("EDITED-AND-KEPT");
        notificationRepository.save(inAmend);
        assertThat(inAmend.getSubmittedNotificationBaseline()).isNotNull();

        // When — resubmit amended notification
        NotificationAggregate resubmitted = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult().getResponseBody();

        // Then — edited content kept, and the baseline re-frozen onto the amended content rather
        // than cleared: a re-submitted notification is frozen at its LATEST submission.
        assertThat(resubmitted.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(resubmitted.getNotification().getOrigin().getInternalReference()).isEqualTo("EDITED-AND-KEPT");

        NotificationAggregate reloaded = notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        assertThat(reloaded.getSubmittedNotificationBaseline()).isNotNull();
        assertThat(reloaded.getSubmittedNotificationBaseline().getOrigin().getInternalReference())
            .isEqualTo("EDITED-AND-KEPT");
        assertThat(reloaded.getNotification().getOrigin().getInternalReference()).isEqualTo("EDITED-AND-KEPT");

        // And — the resubmit emits NotificationSubmissionAmended (AMEND -> SUBMITTED), not NotificationSubmitted
        OutboxEvent resubmitEvent = outboxEventRepository.findAll().stream()
            .max(java.util.Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .orElseThrow();
        assertThat(resubmitEvent.getEventType())
            .isEqualTo(OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED.value());
        assertThat(resubmitEvent.getMetadata().getSchemaUrl())
            .isEqualTo(OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED.schemaUrl());
    }

    @Test
    void submit_shouldReturn404_whenReferenceNumberDoesNotExist() {
        // When / Then
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(
                Matchers.containsString(NONEXISTENT_REF));
    }

    @Test
    void submit_shouldWriteOutboxEventWithCorrectEnvelope() {
        // Given — create a notification
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — submit it with a trace ID
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-outbox-001")
            .exchange()
            .expectStatus().isOk();

        // Then — NOTIFICATION_CREATED (v1) + NOTIFICATION_SUBMITTED (v2) exist; verify envelope of v2
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(2);
        OutboxEvent event = events.stream()
            .filter(e -> e.getEventType().endsWith("NotificationSubmitted"))
            .findFirst().orElseThrow();

        assertThat(event.getAggregateId())
            .isEqualTo(OutboxService.buildAggregateId(referenceNumber));
        assertThat(event.getAggregateType()).isEqualTo("Notification");
        assertThat(event.getSubType()).isEqualTo("GBN-AG");
        assertThat(event.getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
        assertThat(event.getAggregateVersion()).isEqualTo(2L);
        assertThat(event.getTimestamp()).isNotNull();
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getMetadata().getCorrelationId()).isEqualTo("trace-outbox-001");
        assertThat(event.getMetadata().getSchemaVersion()).isEqualTo("1");
        assertThat(event.getMetadata().getSchemaUrl()).isEqualTo(OutboxEventType.NOTIFICATION_SUBMITTED.schemaUrl());
        assertThat(gbnAgIdentifier(event)).isEqualTo(referenceNumber);
        assertThat(event.getActor()).isNull();
        // statusChanges accumulates from DRAFT (v1 NOTIFICATION_CREATED) through SUBMITTED
        assertThat(event.getStatusChanges()).hasSize(2);
        assertThat(event.getStatusChanges().getLast().getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(event.getStatusChanges().getLast().getDateChanged()).isNotNull();
        assertThat(event.getStatusChanges().getLast().getActor()).isNull();
    }

    @Test
    void submit_shouldWriteActorAndStatusChanges_whenActorBodyProvided() {
        // Given — create a notification
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        var actorBody = Map.of(
            "id", "contact-guid-001",
            "source", "dynamics-contact",
            "userType", "B2C",
            "displayName", "Jane Farmer",
            "organisationId", "org-001"
        );

        // When — submit with actor body
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .bodyValue(actorBody)
            .exchange()
            .expectStatus().isOk();

        // Then — actor is stamped on the SUBMITTED event and statusChanges carries it
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(2);
        OutboxEvent event = events.stream()
            .filter(e -> e.getEventType().endsWith("NotificationSubmitted"))
            .findFirst().orElseThrow();

        assertThat(event.getActor()).isNotNull();
        assertThat(event.getActor().getId()).isEqualTo("contact-guid-001");
        assertThat(event.getActor().getSource()).isEqualTo("dynamics-contact");
        assertThat(event.getActor().getUserType()).isEqualTo("B2C");
        assertThat(event.getActor().getDisplayName()).isEqualTo("Jane Farmer");
        assertThat(event.getActor().getOrganisationId()).isEqualTo("org-001");
        assertThat(event.getActor().getOnBehalfOfOrganisationId()).isNull();

        // statusChanges: DRAFT (from NOTIFICATION_CREATED, no actor) + SUBMITTED (with actor)
        assertThat(event.getStatusChanges()).hasSize(2);
        assertThat(event.getStatusChanges().getLast().getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(event.getStatusChanges().getLast().getActor()).isEqualTo(event.getActor());
    }

    @Test
    void submitThenAmend_shouldAccumulateStatusChanges() {
        // Given — create and submit
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        var submitActor = Map.of(
            "id", "contact-sub-001", "source", "dynamics-contact",
            "userType", "B2C", "displayName", "Alice", "organisationId", "org-001");
        var amendActor = Map.of(
            "id", "contact-sub-002", "source", "dynamics-contact",
            "userType", "B2C", "displayName", "Bob", "organisationId", "org-002");

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .bodyValue(submitActor)
            .exchange().expectStatus().isOk();

        // When — amend
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .bodyValue(amendActor)
            .exchange().expectStatus().isOk();

        // Then — CREATED(v1) + SUBMITTED(v2) + AMENDMENT_REQUESTED(v3); check statusChanges on v3
        List<OutboxEvent> events = outboxEventRepository.findAll()
            .stream()
            .sorted(java.util.Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();

        assertThat(events).hasSize(3);
        OutboxEvent amendEvent = events.get(2);
        // statusChanges: DRAFT (NOTIFICATION_CREATED) + SUBMITTED (Alice) + AMEND (Bob)
        assertThat(amendEvent.getStatusChanges()).hasSize(3);
        assertThat(amendEvent.getStatusChanges().get(1).getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(amendEvent.getStatusChanges().get(1).getActor().getId()).isEqualTo("contact-sub-001");
        assertThat(amendEvent.getStatusChanges().get(2).getStatus()).isEqualTo(NotificationStatus.AMEND);
        assertThat(amendEvent.getStatusChanges().get(2).getActor().getId()).isEqualTo("contact-sub-002");
    }

    @SuppressWarnings("unchecked")
    private static Object gbnAgIdentifier(OutboxEvent event) {
        Map<String, Object> exchangedDocument =
            (Map<String, Object>) event.getData().get("exchangedDocument");
        return exchangedDocument.get("identifier");
    }

    @SuppressWarnings("unchecked")
    private static Object gbnAgVersionId(OutboxEvent event) {
        Map<String, Object> exchangedDocument =
            (Map<String, Object>) event.getData().get("exchangedDocument");
        return exchangedDocument.get("versionId");
    }

    @Test
    void submit_shouldIncrementAggregateVersion_onSubsequentSubmissions() {
        // Given — create and submit a notification (version 1)
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        // Reset status to DRAFT so we can submit again (simulates re-submission scenario)
        NotificationAggregate notificationAggregate = notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        notificationAggregate.setStatus(NotificationStatus.DRAFT);
        notificationRepository.save(notificationAggregate);

        // When — submit again (version 2)
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        // Then — CREATED(v1) + SUBMITTED(v2) + SUBMITTED(v3) with incrementing versions
        List<OutboxEvent> events = outboxEventRepository.findAll()
            .stream()
            .sorted(java.util.Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).getAggregateVersion()).isEqualTo(1L);
        assertThat(events.get(1).getAggregateVersion()).isEqualTo(2L);
        assertThat(events.get(2).getAggregateVersion()).isEqualTo(3L);
    }

    @Test
    void submit_shouldNotWriteOutboxEvent_whenNotificationDoesNotExist() {
        // When
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound();

        // Then — no outbox events written
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void post_shouldWriteNotificationCreatedEvent_whenCreatingDraftNotification() {
        // When — create a draft notification
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk();

        // Then — NOTIFICATION_CREATED outbox event written at v1
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationCreated");
        assertThat(events.getFirst().getAggregateVersion()).isEqualTo(1L);
    }

    @Test
    void softDelete_shouldTransitionStatusToDeleted_whenNotificationIsDraft() {
        // Given — create a notification (starts as DRAFT)
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — soft-delete the notification
        NotificationAggregate result = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult().getResponseBody();

        // Then — status transitions to DELETED
        assertThat(result).isNotNull();
        assertThat(result.getReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
        assertThat(result.getUpdated()).isNotNull();
    }

    @Test
    void softDelete_shouldTransitionStatusToDeleted_whenNotificationIsSubmitted() {
        // Given — create and submit a notification
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        // When — soft-delete the submitted notification
        NotificationAggregate result = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult().getResponseBody();

        // Then — status transitions to DELETED
        assertThat(result).isNotNull();
        assertThat(result.getReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
        assertThat(result.getUpdated()).isNotNull();
    }

    @Test
    void softDelete_shouldReturn404_whenReferenceNumberDoesNotExist() {
        // When / Then
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(
                Matchers.containsString(NONEXISTENT_REF));
    }

    @Test
    void softDelete_shouldBeIdempotent_whenNotificationIsAlreadyDeleted() {
        // Given — create and soft-delete a notification
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", referenceNumber)
            .exchange().expectStatus().isOk();

        // When — a repeat soft-delete returns the already-DELETED notification unchanged (REST DELETE idempotency).
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .value(n -> assertThat(n.getStatus()).isEqualTo(NotificationStatus.DELETED));
    }

    @Test
    void softDelete_shouldWriteNotificationDeletedEvent_whenDraft() {
        // Given — create a DRAFT notification (NOTIFICATION_CREATED at v1)
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — soft-delete the draft
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", referenceNumber)
            .exchange().expectStatus().isOk();

        // Then — NOTIFICATION_DELETED emitted at v2 (DRAFT never submitted, no versionId)
        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
            .sorted(java.util.Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();
        assertThat(events).hasSize(2);
        OutboxEvent deleteEvent = events.get(1);
        assertThat(deleteEvent.getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationDeleted");
        assertThat(deleteEvent.getAggregateVersion()).isEqualTo(2L);
        assertThat(gbnAgVersionId(deleteEvent)).isNull();
    }

    @Test
    void softDelete_shouldWriteNotificationSubmissionDeletedEvent_whenSubmitted() {
        // Given — create and submit (NOTIFICATION_CREATED v1, NOTIFICATION_SUBMITTED v2)
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        // When — soft-delete the submitted notification
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", referenceNumber)
            .exchange().expectStatus().isOk();

        // Then — NOTIFICATION_SUBMISSION_DELETED emitted at v3, carrying versionId=1
        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
            .sorted(java.util.Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();
        assertThat(events).hasSize(3);
        OutboxEvent deleteEvent = events.get(2);
        assertThat(deleteEvent.getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmissionDeleted");
        assertThat(deleteEvent.getAggregateVersion()).isEqualTo(3L);
        assertThat(gbnAgVersionId(deleteEvent)).isEqualTo(1);
    }

    @Test
    void submit_shouldSetVersionIdToOne_onFirstSubmission() {
        // Given — create a notification
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — submit
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        // Then — NOTIFICATION_SUBMITTED carries versionId=1
        OutboxEvent submitEvent = outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType().endsWith("NotificationSubmitted"))
            .findFirst().orElseThrow();
        assertThat(gbnAgVersionId(submitEvent)).isEqualTo(1);
    }

    @Test
    void submit_shouldIncrementVersionId_onResubmission() {
        // Given — create, submit, force back to DRAFT, submit again
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        NotificationAggregate notificationAggregate =
            notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        notificationAggregate.setStatus(NotificationStatus.DRAFT);
        notificationRepository.save(notificationAggregate);

        // When — resubmit
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        // Then — second NOTIFICATION_SUBMITTED carries versionId=2
        List<OutboxEvent> submitEvents = outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType().endsWith("NotificationSubmitted"))
            .sorted(java.util.Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();
        assertThat(submitEvents).hasSize(2);
        assertThat(gbnAgVersionId(submitEvents.get(0))).isEqualTo(1);
        assertThat(gbnAgVersionId(submitEvents.get(1))).isEqualTo(2);
    }

    @Test
    void findAllReferenceNumbers_shouldReturnEmptyPage_whenNoNotificationsExist() {
        // When
        ReferenceNumberPageResponse page = findAllReferenceNumbersPage();

        // Then
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.page()).isZero();
    }

    @Test
    void findAllReferenceNumbers_shouldPageOnlyReferenceNumbersAcrossPages() {
        // Given — create three notifications; page-size is 2 in integration profile
        String ref1 = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        String ref2 = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("IE", "Live sheep")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        String ref3 = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("FR", "Live pigs")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — fetch page 0 + page 1 + page 2
        ReferenceNumberPageResponse page0 = findAllReferenceNumbersPage(0);
        ReferenceNumberPageResponse page1 = findAllReferenceNumbersPage(1);
        ReferenceNumberPageResponse page2 = findAllReferenceNumbersPage(2);

        // Then
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(3);
        assertThat(page0.content()).hasSize(1);
        assertThat(page1.content()).hasSize(1);
        assertThat(page2.content()).hasSize(1);
        assertThat(page0.content().size() + page1.content().size()
            + page2.content().size()).isEqualTo(3);
        
        // All three refs returned across the three pages, no duplicates
        List<String> allReferenceNumbers = Stream.of(page0, page1, page2)
            .map(ReferenceNumberPageResponse::content)
            .flatMap(Collection::stream)
            .toList();
        assertThat(allReferenceNumbers).containsExactlyInAnyOrder(ref1, ref2, ref3);
    }

    @Test
    void findAllReferenceNumbers_shouldReturnEmptyContentForOutOfRangePage() {
        // Given — one notification (page-size 2 ⇒ totalPages 1)
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk();

        // When — request a page beyond the last
        ReferenceNumberPageResponse page = findAllReferenceNumbersPage(5);

        // Then — empty content, but totals still reflect the real data
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    @Test
    void copy_shouldRetainReferencedConsignorAddressIdAlone() {
        NotificationDto sourceDto = createNotificationDto("DE", "Live cattle");
        sourceDto.setConsignor(ConsignmentParty.reference(ADDRESS_ID));

        NotificationAggregate source = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(SaveNotificationDto.of(sourceDto))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult().getResponseBody();

        assertThat(source).isNotNull();
        assertThat(source.getNotification().getConsignor().getAddressId()).isEqualTo(ADDRESS_ID);
        assertThat(source.getNotification().getConsignor().getName()).isNull();

        NotificationAggregate copy = webClient("NoAuth")
            .post()
            .uri(uriBuilder -> uriBuilder
                .path(NOTIFICATION_ENDPOINT + "/{ref}/copy")
                .queryParam("concurrencyToken", source.getConcurrencyToken())
                .build(source.getReferenceNumber()))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult().getResponseBody();

        assertThat(copy).isNotNull();
        assertThat(copy.getNotification().getConsignor()).isEqualTo(ConsignmentParty.reference(ADDRESS_ID));
    }

    @Test
    void delete_shouldCascadeDeleteAccompanyingDocuments_whenNotificationDeleted() {
        // Given — create a notification
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("FR", "Live pigs")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // And persist an accompanying document directly
        AccompanyingDocument document = AccompanyingDocument.builder()
            .notificationReferenceNumber(referenceNumber)
            .uploadId("upload-cascade-test-001")
            .documentType(DocumentType.VETERINARY_HEALTH_CERTIFICATE)
            .scanStatus(ScanStatus.COMPLETE)
            .build();
        accompanyingDocumentRepository.save(document);
        assertThat(accompanyingDocumentRepository.findAllByNotificationReferenceNumber(referenceNumber))
            .hasSize(1);

        // When — delete the notification
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header("x-cdp-request-id", "trace-cascade-001")
            .header("User-Id", "user-cascade-001")
            .bodyValue(List.of(referenceNumber))
            .exchange()
            .expectStatus().isNoContent();

        // Then — notification and its documents are both gone
        assertThat(notificationRepository.findByReferenceNumber(referenceNumber)).isEmpty();
        assertThat(accompanyingDocumentRepository.findAllByNotificationReferenceNumber(referenceNumber))
            .isEmpty();
    }

    private ReferenceNumberPageResponse findAllReferenceNumbersPage() {
        return findAllReferenceNumbersPage(0);
    }

    private ReferenceNumberPageResponse findAllReferenceNumbersPage(int page) {
        return webClient("NoAuth")
            .get()
            .uri(NOTIFICATION_ENDPOINT + "/reference-numbers?page=" + page)
            .exchange()
            .expectStatus().isOk()
            .expectBody(ReferenceNumberPageResponse.class)
            .returnResult().getResponseBody();
    }

    private NotificationPageResponse findAllNotificationsPage() {
        return findAllNotificationsPage(1);
    }

    private NotificationPageResponse findAllNotificationsPage(int page) {
        return findAllNotificationsPage(page, null);
    }

    private NotificationPageResponse findAllNotificationsPage(int page, String sort) {
        return findAllNotificationsPage(page, sort, null);
    }

    private NotificationPageResponse findAllNotificationsPage(
        int page, String sort, String referenceNumber) {
        String uri = NOTIFICATION_ENDPOINT + "?page=" + page;
        if (sort != null) {
            uri += "&sort=" + sort;
        }
        if (referenceNumber != null) {
            uri += "&referenceNumber=" + referenceNumber;
        }
        return webClient("NoAuth")
            .get()
            .uri(uri)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationPageResponse.class)
            .returnResult().getResponseBody();
    }

    private List<NotificationView> findAllNotifications() {
        return findAllNotificationsPage().content();
    }

    private void assertNotificationMappedFields(NotificationAggregate notificationAggregate) {
        assertNotificationMappedFields(notificationAggregate, "REF-001");
    }

    private void assertNotificationMappedFields(NotificationAggregate notificationAggregate, String internalReference) {
        assertThat(notificationAggregate.getNotification().getOrigin())
            .extracting(Origin::getCountryCode, Origin::getRequiresRegionCode, Origin::getInternalReference)
            .containsExactly("GB", "true", internalReference);

        assertThat(notificationAggregate.getNotification().getCommodity())
            .extracting(Commodity::getName)
            .isEqualTo("Live bovine animals");

        CommodityComplement complement = notificationAggregate.getNotification().getCommodity().getCommodityComplement().getFirst();
        assertThat(complement)
            .extracting(
                CommodityComplement::getTypeOfCommodity,
                CommodityComplement::getTotalNoOfAnimals,
                CommodityComplement::getTotalNoOfPackages)
            .containsExactly("LIVE", 10, 5);

        Species species = complement.getSpecies().getFirst();
        assertThat(species)
            .extracting(
                Species::getValue,
                Species::getText,
                Species::getNoOfAnimals,
                Species::getNoOfPackages,
                Species::getEarTag,
                Species::getPassport)
            .containsExactly("BOV", "Bovine", 10, 5, "UK01234567890", "UK0123456700999");

        assertThat(notificationAggregate.getNotification())
            .extracting(Notification::getReasonForImport, Notification::getCphNumber)
            .containsExactly("PERMANENT", "22/123/4567");

        assertThat(notificationAggregate.getNotification().getAdditionalDetails())
            .extracting(AdditionalDetails::getCertifiedFor, AdditionalDetails::getUnweanedAnimals)
            .containsExactly("HUMAN_CONSUMPTION", "true");

        assertThat(notificationAggregate.getNotification().getTransport())
            .extracting(
                Transport::getPortOfEntry,
                Transport::getArrivalDate,
                Transport::getMeansOfTransport,
                Transport::getTransportIdentification,
                Transport::getTransportDocumentReference,
                Transport::getTransitedCountries)
            .containsExactly(
                "GBFXT",
                LocalDate.of(2026, Month.APRIL, 22),
                MeansOfTransport.RAILWAY,
                "Train 4471, wagon 12",
                "CIM-CONSIGNMENT-001",
                List.of("FR", "DE"));
    }

    private void assertAmendableContentMatches(Notification expected, Notification actual) {
        assertThat(actual.getOrigin()).isEqualTo(expected.getOrigin());
        assertThat(actual.getReasonForImport()).isEqualTo(expected.getReasonForImport());
        assertThat(actual.getCommodity()).isEqualTo(expected.getCommodity());
        assertThat(actual.getAdditionalDetails()).isEqualTo(expected.getAdditionalDetails());
        assertThat(actual.getPlaceOfOrigin()).isEqualTo(expected.getPlaceOfOrigin());
        assertThat(actual.getConsignor()).isEqualTo(expected.getConsignor());
        assertThat(actual.getConsignee()).isEqualTo(expected.getConsignee());
        assertThat(actual.getImporter()).isEqualTo(expected.getImporter());
        assertThat(actual.getDestination()).isEqualTo(expected.getDestination());
        assertThat(actual.getConsignment()).isEqualTo(expected.getConsignment());
        assertThat(actual.getCphNumber()).isEqualTo(expected.getCphNumber());
        assertThat(actual.getTransport()).isEqualTo(expected.getTransport());
    }

    @Test
    void getOutboxEvents_shouldReturnEventsInChronologicalOrder() {
        // Given — create and submit a notification (version 1)
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        // Reset status to DRAFT so we can submit again
        NotificationAggregate notificationAggregate = notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        notificationAggregate.setStatus(NotificationStatus.DRAFT);
        notificationRepository.save(notificationAggregate);

        // Submit again (version 2)
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        // When
        List<OutboxEvent> events = webClient("NoAuth")
            .get().uri(NOTIFICATION_ENDPOINT + "/{ref}/outbox-events", referenceNumber)
            .exchange().expectStatus().isOk()
            .expectBodyList(OutboxEvent.class).returnResult()
            .getResponseBody();

        // Then — CREATED(v1) + SUBMITTED(v2) + SUBMITTED(v3) returned in ascending order
        assertThat(events).hasSize(3);
        assertThat(events.get(0).getAggregateVersion()).isEqualTo(1L);
        assertThat(events.get(1).getAggregateVersion()).isEqualTo(2L);
        assertThat(events.get(2).getAggregateVersion()).isEqualTo(3L);
        assertThat(events.get(0).getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationCreated");
        assertThat(events.get(1).getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
    }

    @Test
    void getOutboxEvents_shouldReturnNotificationCreatedEvent_forUnsubmittedDraft() {
        // Given — create a notification but do not submit it
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When
        List<OutboxEvent> events = webClient("NoAuth")
            .get().uri(NOTIFICATION_ENDPOINT + "/{ref}/outbox-events", referenceNumber)
            .exchange().expectStatus().isOk()
            .expectBodyList(OutboxEvent.class).returnResult()
            .getResponseBody();

        // Then — NOTIFICATION_CREATED is the only event for an unsubmitted draft
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationCreated");
    }

    @Test
    void copy_shouldCreateNewDraftFromSourceNotification() {
        // Given — create a source notification with a full set of fields
        NotificationDto sourceDto = sourceNotificationWithAllOperators();

        NotificationAggregate source = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(sourceDto))
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult().getResponseBody();

        assertThat(source).isNotNull();
        String sourceRef = source.getReferenceNumber();

        // When — copy via the dedicated copy endpoint
        NotificationAggregate copy = webClient("NoAuth")
            .post()
            .uri(uriBuilder -> uriBuilder
                .path(NOTIFICATION_ENDPOINT + "/{ref}/copy")
                .queryParam("concurrencyToken", source.getConcurrencyToken())
                .build(sourceRef))
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult().getResponseBody();

        // Then — new reference number, DRAFT status
        assertThat(copy).isNotNull();
        assertThat(copy.getReferenceNumber()).isNotEqualTo(sourceRef);
        assertThat(copy.getReferenceNumber()).matches(REF_FORMAT_REGEX);
        assertThat(copy.getStatus()).isEqualTo(NotificationStatus.DRAFT);

        // Retained fields
        assertThat(copy.getNotification().getOrigin().getCountryCode()).isEqualTo("DE");
        assertThat(copy.getNotification().getOrigin().getRequiresRegionCode()).isEqualTo("yes");
        assertThat(copy.getNotification().getReasonForImport()).isEqualTo("internalMarket");
        assertThat(copy.getNotification().getCommodity().getName()).isEqualTo("Live bovine animals");
        assertThat(copy.getNotification().getAdditionalDetails().getCertifiedFor()).isEqualTo("Breeding");
        assertThat(copy.getNotification().getCphNumber()).isEqualTo("12/345/6789");

        // Excluded fields
        assertThat(copy.getNotification().getOrigin().getInternalReference()).isNull();
        assertThat(copy.getNotification().getAdditionalDetails().getUnweanedAnimals()).isNull();
        assertThat(copy.getNotification().getTransport()).isNull();
        assertThat(copy.getNotification().getConsignment()).isNull();
        CommodityComplement cc = copy.getNotification().getCommodity().getCommodityComplement().getFirst();
        assertThat(cc.getTypeOfCommodity()).isEqualTo("LIVE");
        assertThat(cc.getTotalNoOfAnimals()).isNull();
        assertThat(cc.getTotalNoOfPackages()).isNull();
        assertThat(cc.getSpecies()).isNull();

        // Original unchanged
        NotificationAggregate original = notificationRepository.findByReferenceNumber(sourceRef).orElseThrow();
        assertThat(original.getNotification().getOrigin().getInternalReference()).isEqualTo("INTERNAL-DO-NOT-COPY");
        assertThat(original.getNotification().getAdditionalDetails().getUnweanedAnimals()).isEqualTo("yes");
        assertThat(original.getNotification().getTransport().getPortOfEntry()).isEqualTo("GBDVR");
    }

    @Test
    void copy_shouldRetainAllOperatorAddresses() {
        NotificationDto sourceDto = sourceNotificationWithAllOperators();

        NotificationAggregate source = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(SaveNotificationDto.of(sourceDto))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult().getResponseBody();

        assertThat(source).isNotNull();

        NotificationAggregate copy = webClient("NoAuth")
            .post()
            .uri(uriBuilder -> uriBuilder
                .path(NOTIFICATION_ENDPOINT + "/{ref}/copy")
                .queryParam("concurrencyToken", source.getConcurrencyToken())
                .build(source.getReferenceNumber()))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult().getResponseBody();

        assertThat(copy).isNotNull();
        assertThat(copy.getNotification().getPlaceOfOrigin()).isEqualTo(NotificationTestData.placesOfOrigin().getFirst());
        assertThat(copy.getNotification().getConsignor()).isEqualTo(NotificationTestData.consignors().getFirst());
        assertThat(copy.getNotification().getConsignee()).isEqualTo(NotificationTestData.consignees().getFirst());
        assertThat(copy.getNotification().getImporter()).isEqualTo(NotificationTestData.importers().getFirst());
        assertThat(copy.getNotification().getDestination()).isEqualTo(NotificationTestData.destinations().getFirst());
    }

    @Test
    void copy_shouldReturn400_whenSourceNotificationIsDeleted() {
        // Given — create a source notification and capture the version the user's browser
        // would have last seen. Someone else then soft-deletes it.
        NotificationAggregate source = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("DE", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult().getResponseBody();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", source.getReferenceNumber())
            .exchange().expectStatus().isOk();

        // When — the user with the stale copy clicks copy. 
        // Then — the status guard fires with 400.
        webClient("NoAuth")
            .post()
            .uri(uriBuilder -> uriBuilder
                .path(NOTIFICATION_ENDPOINT + "/{ref}/copy")
                .queryParam("concurrencyToken", source.getConcurrencyToken())
                .build(source.getReferenceNumber()))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void copy_shouldCreateNewDraftFromSubmittedNotification() {
        // Given — create a notification and submit it
        String sourceRef = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("IE", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", sourceRef)
            .exchange().expectStatus().isOk();

        Long submittedVersion = notificationRepository.findByReferenceNumber(sourceRef)
            .orElseThrow().getConcurrencyToken();

        // When — copy the submitted notification
        NotificationAggregate copy = webClient("NoAuth")
            .post()
            .uri(uriBuilder -> uriBuilder
                .path(NOTIFICATION_ENDPOINT + "/{ref}/copy")
                .queryParam("concurrencyToken", submittedVersion)
                .build(sourceRef))
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult().getResponseBody();

        // Then — copy is a new DRAFT with a different reference number
        assertThat(copy).isNotNull();
        assertThat(copy.getReferenceNumber()).isNotEqualTo(sourceRef);
        assertThat(copy.getReferenceNumber()).matches(REF_FORMAT_REGEX);
        assertThat(copy.getStatus()).isEqualTo(NotificationStatus.DRAFT);
    }

    @Test
    void copy_shouldReturn409StaleConcurrencyToken_whenExpectedTokenDoesNotMatchSource() {
        // Given — source starts at version 0 after POST.
        NotificationAggregate oldVersion = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult().getResponseBody();

        // A PUT edit advances the source to version 1.
        NotificationDto newVersion = NotificationDto.builder()
            .referenceNumber(oldVersion.getReferenceNumber())
            .origin(new Origin("GB", "no", "EDITED"))
            .commodity(Commodity.builder().name("Live cattle").build())
            .concurrencyToken(oldVersion.getConcurrencyToken())
            .build();
        webClient("NoAuth")
            .put().uri(NOTIFICATION_ENDPOINT + "/{ref}", oldVersion.getReferenceNumber())
            .bodyValue(SaveNotificationDto.of(newVersion))
            .exchange()
            .expectStatus().isOk();

        // When — copy with the stake version 0 (eg in another browser)
        // Then return CONFLICT to prevent non-WYSIWYG copies.
        webClient("NoAuth")
            .post()
            .uri(uriBuilder -> uriBuilder
                .path(NOTIFICATION_ENDPOINT + "/{ref}/copy")
                .queryParam("concurrencyToken", oldVersion.getConcurrencyToken())
                .build(oldVersion.getReferenceNumber()))
            .exchange()
            .expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.status").isEqualTo(409)
            .jsonPath("$.code").isEqualTo("STALE_CONCURRENCY_TOKEN")
            .jsonPath("$.title").isEqualTo("Stale Concurrency Token");
    }

    @Test
    void copy_shouldReturn404_whenSourceNotificationDoesNotExist() {
        // When / Then
        webClient("NoAuth")
            .post()
            .uri(uriBuilder -> uriBuilder
                .path(NOTIFICATION_ENDPOINT + "/{ref}/copy")
                .queryParam("concurrencyToken", 0L)
                .build(NONEXISTENT_REF))
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(Matchers.containsString(NONEXISTENT_REF));
    }

    @Test
    void copy_shouldWriteNotificationCreatedEvent_forNewNotification() {
        // Given — source notification
        NotificationAggregate source = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody();

        // When — copy it
        NotificationAggregate copy = webClient("NoAuth")
            .post()
            .uri(uriBuilder -> uriBuilder
                .path(NOTIFICATION_ENDPOINT + "/{ref}/copy")
                .queryParam("concurrencyToken", source.getConcurrencyToken())
                .build(source.getReferenceNumber()))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody();

        // Then — NOTIFICATION_CREATED written for the new notification (not for the source)
        List<OutboxEvent> allEvents = outboxEventRepository.findAll();
        List<OutboxEvent> createdEvents = allEvents.stream()
            .filter(e -> e.getEventType().endsWith("NotificationCreated"))
            .toList();
        // One NOTIFICATION_CREATED per notification (source + copy = 2 total)
        assertThat(createdEvents).hasSize(2);
        assertThat(createdEvents).anyMatch(
            e -> e.getAggregateId().equals(OutboxService.buildAggregateId(copy.getReferenceNumber())));
        // Source aggregate has exactly one event (its own NOTIFICATION_CREATED, not a second one from the copy)
        long sourceEventCount = allEvents.stream()
            .filter(e -> e.getAggregateId().equals(OutboxService.buildAggregateId(source.getReferenceNumber())))
            .count();
        assertThat(sourceEventCount).isEqualTo(1);
    }

    @Test
    void findFulfilments_shouldReturnFulfilmentView_forExistingNotification() {
        // Given — create a notification carrying a fulfilments payload
        Document fulfilment = new Document("obligationId", "abc").append("value", "42");
        NotificationDto dto = createNotificationDto("GB", "Live cattle");
        dto.setFulfilments(List.of(fulfilment));

        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(dto))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When / Then
        webClient("NoAuth")
            .get().uri(NOTIFICATION_ENDPOINT + "/{ref}/fulfilments", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.referenceNumber").isEqualTo(referenceNumber)
            .jsonPath("$.status").isEqualTo("DRAFT")
            .jsonPath("$.fulfilments[0].obligationId").isEqualTo("abc")
            .jsonPath("$.fulfilments[0].value").isEqualTo("42");
    }

    @Test
    void findFulfilments_shouldReturn404_whenReferenceNumberUnknown() {
        webClient("NoAuth")
            .get().uri(NOTIFICATION_ENDPOINT + "/{ref}/fulfilments", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(Matchers.containsString(NONEXISTENT_REF));
    }

    @Test
    void put_shouldReturn409StaleConcurrencyToken_whenTokenIsStale() {
        // Given a notification with a version that has been progressed by an update.
        NotificationAggregate created = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(createNotificationDto("GB", "Live cattle")))
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult().getResponseBody();
        String ref = created.getReferenceNumber();
        Long staleVersion = created.getConcurrencyToken();

        NotificationDto firstEdit = NotificationDto.builder()
            .referenceNumber(ref)
            .origin(new Origin("GB", "no", "FIRST"))
            .commodity(Commodity.builder().name("Live cattle").build())
            .concurrencyToken(staleVersion)
            .build();
        webClient("NoAuth")
            .put().uri(NOTIFICATION_ENDPOINT + "/{ref}", ref)
            .bodyValue(SaveNotificationDto.of(firstEdit))
            .exchange()
            .expectStatus().isOk();

        // When — a second PUT is attempted using the stale version
        NotificationDto staleEdit = NotificationDto.builder()
            .referenceNumber(ref)
            .origin(new Origin("GB", "no", "STALE"))
            .commodity(Commodity.builder().name("Live cattle").build())
            .concurrencyToken(staleVersion)
            .build();

        // Then — the update is rejected with 409 STALE_CONCURRENCY_TOKEN
        webClient("NoAuth")
            .put().uri(NOTIFICATION_ENDPOINT + "/{ref}", ref)
            .bodyValue(SaveNotificationDto.of(staleEdit))
            .exchange()
            .expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.status").isEqualTo(409)
            .jsonPath("$.code").isEqualTo("STALE_CONCURRENCY_TOKEN")
            .jsonPath("$.title").isEqualTo("Stale Concurrency Token");

        // And the stored notification reflects the first PUT's write, not the stale one.
        NotificationAggregate stored = notificationRepository.findByReferenceNumber(ref).orElseThrow();
        assertThat(stored.getNotification().getOrigin().getInternalReference()).isEqualTo("FIRST");
    }

    private String createAndSubmitNotificationWithFullContent() {
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(sourceNotificationWithAllOperators()))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        return referenceNumber;
    }

    private NotificationDto createNotificationDto(String countryCode, String commodity) {
        Origin origin = new Origin();
        origin.setCountryCode(countryCode);

        return NotificationDto.builder()
            .origin(origin)
            .commodity(Commodity.builder().name(commodity).build())
            .build();
    }

    private String createNotificationWithReferencedConsignor() {
        NotificationDto dto = createNotificationDto("CH", "Live cattle");
        dto.setConsignor(ConsignmentParty.reference(ADDRESS_ID));
        return webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(dto))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();
    }

    private void submitAs(String referenceNumber, String organisationId) {
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .bodyValue(Map.of("organisationId", organisationId))
            .exchange().expectStatus().isOk();
    }

    private void stubAddressBook(String body, int statusCode) {
        stubAddressBookFor(ADDRESS_ID, body, statusCode);
    }

    /** The address book is scoped by organisation in both the path and the header, and the stub
     * matches on both — a resolve that sent the wrong organisation would miss this stub rather than
     * quietly return someone else's address. */
    private void stubAddressBookFor(String addressId, String body, int statusCode) {
        usingStub()
            .when(request()
                .withMethod("GET")
                .withPath("/organisation/" + ORG_ID + "/addresses/" + addressId)
                .withHeader("Trade-Imports-Organisation-Id", ORG_ID))
            .respond(response()
                .withStatusCode(statusCode)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(body));
    }

    private OutboxEvent submittedOutboxEvent() {
        return outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType().endsWith("NotificationSubmitted"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No NotificationSubmitted event found"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outboxConsignorParty(OutboxEvent event) {
        Map<String, Object> consignment =
            (Map<String, Object>) event.getData().get("specifiedConsignment");
        return (Map<String, Object>) consignment.get("consignorParty");
    }

    private NotificationDto notificationDtoWithArrivalDate(String countryCode, LocalDate arrivalDate) {
        return NotificationDto.builder()
            .origin(new Origin(countryCode, null, null))
            .commodity(Commodity.builder().name("Live animals").build())
            .transport(Transport.builder().arrivalDate(arrivalDate).build())
            .build();
    }

    private NotificationDto notificationDtoWithTransportButNoArrivalDate(String countryCode) {
        return NotificationDto.builder()
            .origin(new Origin(countryCode, null, null))
            .commodity(Commodity.builder().name("Live animals").build())
            .transport(Transport.builder().portOfEntry("GBFXT").build())
            .build();
    }

    private NotificationDto sourceNotificationWithAllOperators() {
        CommodityComplement complement = new CommodityComplement("LIVE", 10, 5,
            List.of(NotificationTestData.species()));
        return NotificationDto.builder()
            .origin(new Origin("DE", "yes", "INTERNAL-DO-NOT-COPY"))
            .commodity(Commodity.builder()
                .name("Live bovine animals")
                .commodityComplement(List.of(complement))
                .build())
            .reasonForImport("internalMarket")
            .additionalDetails(new AdditionalDetails("Breeding", "yes"))
            .placeOfOrigin(NotificationTestData.placesOfOrigin().getFirst())
            .consignor(NotificationTestData.consignors().getFirst())
            .consignee(NotificationTestData.consignees().getFirst())
            .importer(NotificationTestData.importers().getFirst())
            .destination(NotificationTestData.destinations().getFirst())
            .consignment(NotificationTestData.consignments().getFirst())
            .cphNumber("12/345/6789")
            .transport(Transport.builder()
                .portOfEntry("GBDVR")
                .arrivalDate(LocalDate.of(2026, Month.JUNE, 1))
                .build())
            .build();
    }

    private LocalDate extractArrivalDate(NotificationView notification) {
        if (notification.getTransport() == null) {
            return null;
        }
        return notification.getTransport().getArrivalDate();
    }
}
