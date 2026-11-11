package uk.gov.defra.trade.imports.animals.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;
import uk.gov.defra.trade.imports.animals.exceptions.OutboxWriteException;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.outbox.gbnag.GbnAgEventDataMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    static final String AGGREGATE_TYPE = "Notification";
    static final String SUB_TYPE = "GBN-AG";
    static final String SCHEMA_VERSION = "1";
    static final String AGGREGATE_ID_PREFIX = "Imports.Notification.GBN-AG.";

    private static final Set<String> SUBMISSION_EVENT_WIRE_VALUES = OutboxEventType.SUBMISSION_EVENTS
        .stream().map(OutboxEventType::value).collect(Collectors.toUnmodifiableSet());

    private static final Set<OutboxEventType> NO_VERSION_ID_EVENTS = Set.of(
        OutboxEventType.NOTIFICATION_CREATED,
        OutboxEventType.NOTIFICATION_EDITED,
        OutboxEventType.NOTIFICATION_DELETED);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final GbnAgEventDataMapper gbnAgEventDataMapper;
    private final AccompanyingDocumentRepository accompanyingDocumentRepository;

    public void appendEvent(NotificationAggregate notificationAggregate, OutboxEventType eventType, String correlationId, Actor actor) {
        String aggregateId = buildAggregateId(notificationAggregate.getReferenceNumber());

        Optional<OutboxEvent> latestEvent = outboxEventRepository
            .findTopByAggregateIdOrderByAggregateVersionDesc(aggregateId);

        long nextVersion = latestEvent.map(e -> e.getAggregateVersion() + 1).orElse(1L);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        List<StatusChange> priorChanges = latestEvent
            .map(e -> e.getStatusChanges() != null ? e.getStatusChanges() : List.<StatusChange>of())
            .orElseGet(List::of);

        NotificationStatus lastStatus = priorChanges.isEmpty() ? null
            : priorChanges.getLast().getStatus();

        List<StatusChange> statusChanges;
        if (notificationAggregate.getStatus() != lastStatus) {
            StatusChange currentChange = StatusChange.builder()
                .status(notificationAggregate.getStatus())
                .dateChanged(now)
                .actor(actor)
                .build();
            statusChanges = Stream.concat(priorChanges.stream(), Stream.of(currentChange)).toList();
        } else {
            statusChanges = priorChanges;
        }

        Integer versionId = computeVersionId(aggregateId, eventType);

        List<AccompanyingDocument> claimedScannedDocuments = accompanyingDocumentRepository
            .findAllByNotificationReferenceNumberAndScanStatusAndClaimedIsTrueOrderByCreatedAsc(
                notificationAggregate.getReferenceNumber(), ScanStatus.COMPLETE);

        Map<String, Object> data = objectMapper.convertValue(
            gbnAgEventDataMapper.toGbnAgEventData(notificationAggregate, versionId, claimedScannedDocuments),
            MAP_TYPE);

        OutboxEvent event = OutboxEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .aggregateId(aggregateId)
            .aggregateType(AGGREGATE_TYPE)
            .subType(SUB_TYPE)
            .aggregateVersion(nextVersion)
            .eventType(eventType.value())
            .timestamp(now)
            .organisationId(notificationAggregate.getOrganisationId())
            .data(data)
            .metadata(OutboxEventMetadata.builder()
                .correlationId(correlationId)
                .schemaVersion(SCHEMA_VERSION)
                .schemaUrl(eventType.schemaUrl())
                .build())
            .actor(actor)
            .statusChanges(statusChanges)
            .build();

        try {
            outboxEventRepository.save(event);
        } catch (DuplicateKeyException e) {
            throw new OutboxWriteException(
                "Duplicate aggregateVersion on outbox insert",
                aggregateId, nextVersion, correlationId, e);
        }

        log.info("Outbox event written: aggregateId={} version={} correlationId={}",
            aggregateId, nextVersion, correlationId);
    }

    private Integer computeVersionId(String aggregateId, OutboxEventType eventType) {
        if (NO_VERSION_ID_EVENTS.contains(eventType)) {
            return null;
        }
        long submissionCount = outboxEventRepository
            .countByAggregateIdAndEventTypeIn(aggregateId, SUBMISSION_EVENT_WIRE_VALUES);
        // Submission events mint a new version; all others carry the current count forward.
        return (int) (SUBMISSION_EVENT_WIRE_VALUES.contains(eventType.value())
            ? submissionCount + 1
            : submissionCount);
    }

    public List<OutboxEvent> findByReferenceNumber(String referenceNumber) {
        String aggregateId = buildAggregateId(referenceNumber);
        return outboxEventRepository.findAllByAggregateIdOrderByAggregateVersionAsc(aggregateId);
    }

    public static String buildAggregateId(String referenceNumber) {
        return AGGREGATE_ID_PREFIX + referenceNumber;
    }
}
