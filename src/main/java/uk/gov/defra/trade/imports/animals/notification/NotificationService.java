package uk.gov.defra.trade.imports.animals.notification;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentService;
import uk.gov.defra.trade.imports.animals.audit.Action;
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

@Service
@Slf4j
public class NotificationService {

    private static final String CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER = "Cannot find notification with reference number: ";
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofSeconds(10);
    private static final int MAX_REF_RETRIES = 3;
    private static final int MAX_LOCK_RETRIES = 2;

    private final NotificationRepository notificationRepository;
    private final AuditRepository auditRepository;
    private final DocumentService documentService;
    private final OutboxService outboxService;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final NotificationCopyMapper notificationCopyMapper;
    private final NotificationContentMapper notificationContentMapper;
    private final ConsignmentPartyResolver consignmentPartyResolver;
    private final ReferenceNumberGenerator referenceNumberGenerator;
    private final NotificationTtlConfig ttlConfig;
    private final Duration lockAtLeastFor;
    private final int listPageSize;
    private final int adminPageSize;

    public NotificationService(
        NotificationRepository notificationRepository,
        AuditRepository auditRepository,
        DocumentService documentService,
        OutboxService outboxService,
        LockingTaskExecutor lockingTaskExecutor,
        NotificationCopyMapper notificationCopyMapper,
        NotificationContentMapper notificationContentMapper,
        ConsignmentPartyResolver consignmentPartyResolver,
        ReferenceNumberGenerator referenceNumberGenerator,
        NotificationTtlConfig ttlConfig,
        @Value("${notification.submit.lock-at-least-for}") Duration lockAtLeastFor,
        @Value("${notification.list.page-size}") int listPageSize,
        @Value("${notification.admin.page-size}") int adminPageSize) {
        this.notificationRepository = notificationRepository;
        this.auditRepository = auditRepository;
        this.documentService = documentService;
        this.outboxService = outboxService;
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.notificationCopyMapper = notificationCopyMapper;
        this.notificationContentMapper = notificationContentMapper;
        this.consignmentPartyResolver = consignmentPartyResolver;
        this.referenceNumberGenerator = referenceNumberGenerator;
        this.ttlConfig = ttlConfig;
        this.lockAtLeastFor = lockAtLeastFor;
        this.listPageSize = listPageSize;
        this.adminPageSize = adminPageSize;
    }

    public NotificationAggregate saveNotification(NotificationDto notificationDto, String correlationId, Actor actor) {
        if (StringUtils.isBlank(notificationDto.getReferenceNumber())) {
            return createNotification(notificationDto, correlationId, actor);
        } else {
            return updateNotification(notificationDto, correlationId, actor);
        }
    }

    /**
     * Replace the notification content at the given reference. Backs {@code PUT /notifications/{ref}}.
     * Requires DRAFT or AMEND — the state-transition entrypoints (submit / amend / cancelAmend /
     * softDelete) handle other cases. Emits a {@code NOTIFICATION_EDITED} outbox event on every save
     * (EUDPA-304), mirroring {@link #updateNotification}.
     */
    @Transactional
    public NotificationAggregate replace(String referenceNumber, NotificationDto dto,
        String correlationId, Actor actor) {
        NotificationAggregate notificationAggregate = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        if (notificationAggregate.getStatus() != NotificationStatus.DRAFT
            && notificationAggregate.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot replace notification content with status: " + notificationAggregate.getStatus());
        }
        if (dto.getConcurrencyToken() == null) {
            throw new BadRequestException("concurrencyToken is required to replace a notification");
        }
        notificationAggregate.setConcurrencyToken(dto.getConcurrencyToken());
        setNotificationDetails(dto, notificationAggregate);
        return writeWithOutbox(notificationAggregate, referenceNumber, correlationId,
            notificationAggregate.getStatus(), OutboxEventType.NOTIFICATION_EDITED, actor);
    }

    @Transactional
    public NotificationAggregate copyNotification(String referenceNumber, Long expectedConcurrencyToken,
        String correlationId, Actor actor) {
        NotificationAggregate source = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        if (source.getStatus() != NotificationStatus.DRAFT
            && source.getStatus() != NotificationStatus.SUBMITTED
            && source.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException("Cannot copy notification with status: " + source.getStatus());
        }
        if (expectedConcurrencyToken == null || source.getConcurrencyToken() == null) {
            throw new IllegalStateException(
                "Cannot check copy source concurrencyToken for " + referenceNumber
                    + ": expectedConcurrencyToken=" + expectedConcurrencyToken
                    + ", source.concurrencyToken=" + source.getConcurrencyToken());
        }
        if (!source.getConcurrencyToken().equals(expectedConcurrencyToken)) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                "Copy source " + referenceNumber + " has advanced from expected concurrencyToken "
                    + expectedConcurrencyToken + " to " + source.getConcurrencyToken());
        }
        log.info("Copying notification {}", referenceNumber);
        return createNotification(notificationCopyMapper.toCopyDto(source), correlationId, actor);
    }

    public NotificationPageResponse findAll(int page, String sort) {
        return findAll(page, sort, null);
    }

    /** Serves {@code GET /notifications/{ref}/fulfilments} — the frontend engine's rehydrate read. */
    public NotificationFulfilmentsView findFulfilmentsView(String referenceNumber) {
        return notificationRepository.findFulfilmentsViewByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
    }

    /** Serves {@code GET /notifications?…} for the dashboard list. */
    public NotificationPageResponse findAll(int page, String sort, String referenceNumber) {
        List<NotificationStatus> dashboardStatuses = List.of(
            NotificationStatus.DRAFT, NotificationStatus.SUBMITTED, NotificationStatus.AMEND);
        var pageable = PageRequest.of(page - 1, listPageSize, NotificationSort.toSort(sort));

        String trimmedReference = StringUtils.trimToNull(referenceNumber);
        if (trimmedReference != null) {
            log.debug("Fetching notification by reference {} for dashboard", trimmedReference);
            Page<NotificationView> matched = notificationRepository
                .findViewByReferenceNumberAndStatusIn(trimmedReference, dashboardStatuses)
                .<Page<NotificationView>>map(notification ->
                    new PageImpl<>(List.of(notification.forDashboard()), pageable, 1))
                .orElseGet(() -> Page.empty(pageable));
            log.debug("Found {} notifications for reference {}", matched.getNumberOfElements(),
                trimmedReference);
            return NotificationPageResponse.from(matched);
        }

        log.debug("Fetching notifications page {} (size {}) with sort {}", page, listPageSize, sort);
        Page<NotificationView> result = notificationRepository.findAllViewByStatusIn(
            dashboardStatuses, pageable);
        log.debug("Found {} notifications on page {} of {}",
            result.getNumberOfElements(), result.getNumber() + 1, result.getTotalPages());
        return NotificationPageResponse.from(result.map(NotificationView::forDashboard));
    }

    @Transactional
    public NotificationAggregate submitNotification(String referenceNumber, String correlationId, Actor actor) {
        NotificationAggregate notificationAggregate = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));

        if (notificationAggregate.getStatus() != NotificationStatus.DRAFT
            && notificationAggregate.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot submit notification with status: " + notificationAggregate.getStatus());
        }

        // AMEND -> SUBMITTED is a re-submission; DRAFT -> SUBMITTED is the first submission.
        OutboxEventType eventType = notificationAggregate.getStatus() == NotificationStatus.AMEND
            ? OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED
            : OutboxEventType.NOTIFICATION_SUBMITTED;

        return writeWithOutbox(
            notificationAggregate,
            referenceNumber,
            correlationId,
            NotificationStatus.SUBMITTED,
            eventType,
            actor);
    }

    @Transactional
    public NotificationAggregate amendNotification(String referenceNumber, String correlationId, Actor actor) {
        NotificationAggregate notificationAggregate = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));

        if (notificationAggregate.getStatus() != NotificationStatus.SUBMITTED) {
            throw new BadRequestException(
                "Cannot amend notification with status: " + notificationAggregate.getStatus());
        }

        // The content baseline is NOT captured here. It was frozen at submit from the resolved
        // copy and is retained across the amendment, so a cancel restores what was actually
        // submitted. Capturing it now would snapshot references that re-resolve to today's
        // addresses — a state that was never submitted.
        List<Document> currentFulfilments = notificationAggregate.getFulfilments();
        notificationAggregate.setSubmittedFulfilmentsBaseline(
            currentFulfilments == null ? null : deepCopyFulfilments(currentFulfilments));

        return writeWithOutbox(
            notificationAggregate,
            referenceNumber,
            correlationId,
            NotificationStatus.AMEND,
            OutboxEventType.NOTIFICATION_AMENDMENT_REQUESTED,
            actor);
    }

    @Transactional
    public NotificationAggregate cancelAmendNotification(String referenceNumber, String correlationId, Actor actor) {
        NotificationAggregate notificationAggregate = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));

        if (notificationAggregate.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot cancel amendment for notification with status: " + notificationAggregate.getStatus());
        }
        if (notificationAggregate.getSubmittedNotificationBaseline() == null) {
            throw new BadRequestException(
                "Cannot cancel amendment: no submitted baseline stored for notification");
        }

        notificationAggregate.setNotification(notificationContentMapper.deepClone(notificationAggregate.getSubmittedNotificationBaseline()));
        // The baseline is deliberately NOT cleared: it is the read source for a submitted
        // notification, so it has to outlive the cancel that returns us to SUBMITTED.
        // The restored content carries the frozen parties, which hold addressId *and* details.
        // Normalise the four referenced roles back to the reference alone so storage never grows
        // a copy beside the link; the frozen details live in the baseline and nowhere else.
        // placeOfOrigin and the consignment contact are inline by definition and restore verbatim.
        Notification restored = notificationAggregate.requireNotification();
        restored.setConsignor(ConsignmentParty.forStorage(restored.getConsignor()));
        restored.setConsignee(ConsignmentParty.forStorage(restored.getConsignee()));
        restored.setImporter(ConsignmentParty.forStorage(restored.getImporter()));
        restored.setDestination(ConsignmentParty.forStorage(restored.getDestination()));
        List<Document> priorFulfilments = notificationAggregate.getSubmittedFulfilmentsBaseline();
        notificationAggregate.setFulfilments(
            priorFulfilments == null ? null : deepCopyFulfilments(priorFulfilments));
        notificationAggregate.setSubmittedFulfilmentsBaseline(null);
        // submittedAt is deliberately NOT reset — reverting to the previously-submitted state
        // preserves the original submission timestamp.
        return writeWithOutbox(
            notificationAggregate,
            referenceNumber,
            correlationId,
            NotificationStatus.SUBMITTED,
            OutboxEventType.NOTIFICATION_AMENDMENT_CANCELLED,
            actor);
    }

    private NotificationAggregate writeWithOutbox(
        NotificationAggregate notification,
        String referenceNumber,
        String correlationId,
        NotificationStatus targetStatus,
        OutboxEventType eventType,
        Actor actor) {
        // Address-book resolution is HTTP, so it happens here rather than inside the lock below:
        // the outbox critical section is bounded by LOCK_AT_MOST_FOR, and a slow address book that
        // outlived it would let a second writer in behind us. It resolves into a copy, so the
        // notification we save keeps the reference alone and only the event carries the details.
        NotificationAggregate forOutbox = resolvedForOutbox(notification, eventType, actor);

        return executeWithOutboxLock(
            OutboxService.buildAggregateId(referenceNumber), correlationId, eventType.name(), () -> {
                if (targetStatus == NotificationStatus.SUBMITTED) {
                    // Freeze the content as submitted. forOutbox is the fully-resolved copy, so the
                    // baseline holds the address details as they stood at submit, while the stored
                    // role fields keep their addressId alone — the live link an amendment
                    // re-resolves. Captured here, inside the lock and before the save, so the
                    // freeze and the status change land in the same write.
                    notification.setSubmittedNotificationBaseline(
                        notificationContentMapper.deepClone(forOutbox.getNotification()));
                    if (notification.getStatus() == NotificationStatus.AMEND) {
                        // Unlike the content baseline, the fulfilments baseline stays an amend-only
                        // scratchpad: it is byte-faithful and has no resolve-to-today problem, so
                        // accepting the amendment discards it rather than freezing it.
                        notification.setSubmittedFulfilmentsBaseline(null);
                    }
                }
                notification.setStatus(targetStatus);
                notification.setUpdated(LocalDateTime.now());
                // Only actual submissions mint a new submittedAt; cancel-amend restores to SUBMITTED
                // but must preserve the timestamp from the original submission.
                if (OutboxEventType.SUBMISSION_EVENTS.contains(eventType)) {
                    notification.setSubmittedAt(LocalDateTime.now());
                }
                NotificationAggregate saved = notificationRepository.save(notification);
                forOutbox.setStatus(saved.getStatus());
                forOutbox.setUpdated(saved.getUpdated());
                forOutbox.setSubmittedAt(saved.getSubmittedAt());
                outboxService.appendEvent(forOutbox, eventType, correlationId, actor);
                return saved;
            });
    }

    // Draft-grade events: notification may not be fully resolved; best-effort resolution is appropriate.
    private static final Set<OutboxEventType> DRAFT_GRADE_EVENTS = Set.of(
        OutboxEventType.NOTIFICATION_CREATED,
        OutboxEventType.NOTIFICATION_EDITED,
        OutboxEventType.NOTIFICATION_DELETED);

    /**
     * The notification as every outbox event should carry it: references filled in, on a copy, so
     * the stored notification keeps the reference alone.
     *
     * <p>Submit and amend resolve strictly — a GBNAG document cannot carry a nameless party. A
     * draft edit is best-effort, so an address deleted since does not block the save.
     */
    private NotificationAggregate resolvedForOutbox(
        NotificationAggregate notificationAggregate, OutboxEventType eventType, Actor actor) {
        String organisationId = actor != null ? actor.getOrganisationId() : null;
        NotificationAggregate copy = notificationAggregate.toBuilder().build();
        // toBuilder is shallow; deep-clone the notification so the resolver's party mutations
        // don't leak back into the persisted aggregate.
        if (copy.getNotification() != null) {
            copy.setNotification(notificationContentMapper.deepClone(copy.getNotification()));
        }
        return DRAFT_GRADE_EVENTS.contains(eventType)
            ? consignmentPartyResolver.resolveForDraft(copy, organisationId)
            : consignmentPartyResolver.resolveForSubmission(copy, organisationId);
    }

    private <T> T executeWithOutboxLock(
        String aggregateId,
        String correlationId,
        String operationLabel,
        LockingTaskExecutor.TaskWithResult<T> task) {
        String lockName = "outbox-write:" + aggregateId;
        for (int attempt = 0; attempt <= MAX_LOCK_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(lockAtLeastFor.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new OutboxWriteException(
                        "Interrupted waiting for outbox lock for " + operationLabel,
                        aggregateId, null, correlationId, ie);
                }
            }
            LockConfiguration lockConfig = new LockConfiguration(
                Instant.now(), lockName, LOCK_AT_MOST_FOR, lockAtLeastFor);
            try {
                LockingTaskExecutor.TaskResult<T> result =
                    lockingTaskExecutor.executeWithLock(task, lockConfig);
                if (result.wasExecuted()) {
                    return result.getResult();
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new OutboxWriteException(
                    "Outbox write failed during " + operationLabel,
                    aggregateId, null, correlationId, e);
            }
        }
        throw new OutboxWriteException(
            "Could not acquire outbox lock for " + operationLabel,
            aggregateId, null, correlationId);
    }

    @Transactional
    public NotificationAggregate softDeleteNotification(String referenceNumber, String correlationId, Actor actor) {
        NotificationAggregate notificationAggregate = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        // Idempotent per REST DELETE convention — a repeat call after a lost response is a no-op.
        if (notificationAggregate.getStatus() == NotificationStatus.DELETED) {
            return notificationAggregate;
        }
        if (notificationAggregate.getStatus() != NotificationStatus.DRAFT
            && notificationAggregate.getStatus() != NotificationStatus.SUBMITTED
            && notificationAggregate.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot delete notification with status: " + notificationAggregate.getStatus());
        }
        // DRAFT -> DELETED has no submission history; SUBMITTED/AMEND -> DELETED does.
        OutboxEventType eventType = notificationAggregate.getStatus() == NotificationStatus.DRAFT
            ? OutboxEventType.NOTIFICATION_DELETED
            : OutboxEventType.NOTIFICATION_SUBMISSION_DELETED;
        return writeWithOutbox(
            notificationAggregate,
            referenceNumber,
            correlationId,
            NotificationStatus.DELETED,
            eventType,
            actor);
    }

    public ReferenceNumberPageResponse findAllReferenceNumbers(int page) {
        log.debug("Fetching notification reference numbers page {} (size {})", page, listPageSize);
        Page<NotificationReferenceOnly> result = notificationRepository.findAllProjectedBy(
            PageRequest.of(page, adminPageSize, Sort.by(Direction.DESC, "created")));
        log.debug("Found {} reference numbers on page {} of {}",
            result.getNumberOfElements(), result.getNumber() + 1, result.getTotalPages());
        return ReferenceNumberPageResponse.from(result);
    }

    @Transactional(noRollbackFor = NotFoundException.class)
    public void deleteByReferenceNumbers(List<String> referenceNumbers, AuditContext auditContext) {
        if (referenceNumbers == null || referenceNumbers.isEmpty()) {
            return;
        }
        List<NotificationReferenceOnly> found = notificationRepository.findAllByReferenceNumberIn(
            referenceNumbers);
        Set<String> foundRefs = found.stream()
            .map(NotificationReferenceOnly::getReferenceNumber)
            .collect(Collectors.toSet());
        List<String> missing = referenceNumbers.stream()
            .filter(ref -> !foundRefs.contains(ref))
            .toList();
        if (!missing.isEmpty()) {
            createNotificationAuditRecord(referenceNumbers, auditContext, Result.FAILURE);
            throw new NotFoundException(
                "Cannot find notifications with reference numbers: " + String.join(", ", missing));
        }
        log.info("Deleting {} notifications", found.size());
        deleteNotificationsAndDocuments(referenceNumbers);
        createNotificationAuditRecord(referenceNumbers, auditContext, Result.SUCCESS);
    }

    /**
     * Deletes notifications whose {@code expireAt} has passed, cascading to their accompanying
     * documents, up to {@code batchSize} per call. Called by the non-prod
     * {@code NotificationExpirySweeper}; unlike {@link #deleteByReferenceNumbers} it writes no audit
     * record and tolerates documents vanishing mid-batch (a background sweep should not fail because
     * another actor removed a row concurrently). Notifications with a {@code null} {@code expireAt}
     * — including everything created before this feature shipped — are never selected.
     *
     * @param batchSize maximum number of notifications to remove in this run
     * @return the number of notifications deleted
     */
    @Transactional
    public int deleteExpired(int batchSize) {
        List<NotificationReferenceOnly> due =
            notificationRepository.findExpired(LocalDateTime.now(), PageRequest.of(0, batchSize));
        if (due.isEmpty()) {
            return 0;
        }
        List<String> referenceNumbers = due.stream()
            .map(NotificationReferenceOnly::getReferenceNumber)
            .toList();
        log.info("Expiring {} notification(s)", referenceNumbers.size());
        deleteNotificationsAndDocuments(referenceNumbers);
        return referenceNumbers.size();
    }

    /**
     * Removes the given notifications and their accompanying documents. Shared by the audited,
     * strict-existence {@link #deleteByReferenceNumbers} path and the tolerant {@link #deleteExpired}
     * sweep; carries no audit or existence semantics of its own.
     */
    private void deleteNotificationsAndDocuments(List<String> referenceNumbers) {
        notificationRepository.deleteAllByReferenceNumberIn(referenceNumbers);
        documentService.deleteForNotificationRefs(referenceNumbers);
    }

    /**
     * Stamps {@code expireAt} on a freshly-created notification, but only when both prod safeguards
     * pass: a TTL duration is configured (non-prod config) and the running environment is not prod.
     * Anchored to {@code created}, so a notification expires a fixed window after creation
     * regardless of later activity.
     */
    private void stampExpiry(NotificationAggregate notificationAggregate) {
        Integer days = ttlConfig.days();
        if (days == null || ttlConfig.isProd()) {
            return;
        }
        notificationAggregate.setExpireAt(notificationAggregate.getCreated().plusDays(days));
    }

    private NotificationAggregate createNotification(NotificationDto dto, String correlationId, Actor actor) {
        NotificationAggregate notificationAggregate = new NotificationAggregate();
        notificationAggregate.setCreated(LocalDateTime.now());
        notificationAggregate.setStatus(NotificationStatus.DRAFT);
        stampExpiry(notificationAggregate);
        setNotificationDetails(dto, notificationAggregate);
        for (int attempt = 1; attempt <= MAX_REF_RETRIES; attempt++) {
            notificationAggregate.setReferenceNumber(referenceNumberGenerator.generate());
            try {
                NotificationAggregate saved = writeWithOutbox(
                    notificationAggregate,
                    notificationAggregate.getReferenceNumber(),
                    correlationId,
                    NotificationStatus.DRAFT,
                    OutboxEventType.NOTIFICATION_CREATED,
                    actor);
                log.info("NotificationAggregate saved with reference number: {}", saved.getReferenceNumber());
                return saved;
            } catch (DuplicateKeyException _) {
                log.warn("Reference number collision on persistence attempt {}/{}; retrying", attempt, MAX_REF_RETRIES);
            }
        }
        throw new IllegalStateException(
            "Failed to generate a unique reference number after " + MAX_REF_RETRIES + " attempts");
    }

    private NotificationAggregate updateNotification(NotificationDto dto, String correlationId, Actor actor) {
        String referenceNumber = dto.getReferenceNumber();
        NotificationAggregate existing = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        if (existing.getStatus() != NotificationStatus.DRAFT
            && existing.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot save notification with status: " + existing.getStatus());
        }
        if (dto.getConcurrencyToken() == null) {
            throw new BadRequestException("concurrencyToken is required to update a notification");
        }
        existing.setConcurrencyToken(dto.getConcurrencyToken());
        log.info("Updating notification {}", referenceNumber);
        setNotificationDetails(dto, existing);
        return writeWithOutbox(existing, referenceNumber, correlationId, existing.getStatus(),
            OutboxEventType.NOTIFICATION_EDITED, actor);
    }

    private void setNotificationDetails(NotificationDto dto, NotificationAggregate notificationAggregate) {
        if (notificationAggregate.getNotification() == null) {
            notificationAggregate.setNotification(new Notification());
        }
        Notification notification = notificationAggregate.getNotification();
        notification.setOrigin(dto.getOrigin());
        notification.setCommodity(dto.getCommodity());
        notification.setReasonForImport(dto.getReasonForImport());
        notification.setAdditionalDetails(dto.getAdditionalDetails());
        // Place of origin and the consignment contact are held as copies, so they are
        // stored as they arrive. The other four keep the reference alone.
        notification.setPlaceOfOrigin(ConsignmentParty.inlineOnly(dto.getPlaceOfOrigin()));
        notification.setConsignor(ConsignmentParty.forStorage(dto.getConsignor()));
        notification.setConsignee(ConsignmentParty.forStorage(dto.getConsignee()));
        notification.setImporter(ConsignmentParty.forStorage(dto.getImporter()));
        notification.setDestination(ConsignmentParty.forStorage(dto.getDestination()));
        notification.setCphNumber(dto.getCphNumber());
        notification.setTransport(dto.getTransport());
        notification.setConsignment(ConsignmentParty.inlineOnly(dto.getConsignment()));
        notificationAggregate.setFulfilments(dto.getFulfilments());
        notificationAggregate.setUpdated(LocalDateTime.now());
    }

    private void createNotificationAuditRecord(
        List<String> referenceNumbers, AuditContext auditContext, Result result) {
        Audit auditRecord = Audit.builder()
            .action(Action.DELETE_NOTIFICATIONS)
            .result(result)
            .notificationReferenceNumbers(referenceNumbers)
            .numberOfNotifications(referenceNumbers.size())
            .traceId(auditContext.traceId())
            .userId(auditContext.userId())
            .timestamp(LocalDateTime.now())
            .build();

        auditRepository.save(auditRecord);
    }

    /**
     * BSON round-trip deep clone of a fulfilments list. Callers need independence from the source
     * because amend snapshots the pre-amend fulfilments into {@code submittedFulfilmentsBaseline}
     * and cancel-amend restores from it; a shared reference at any nesting depth would let a
     * later in-memory mutation on one list surface on the other before the notification is persisted.
     * Callers are responsible for the {@code null} case — the helper always returns a fresh list.
     */
    static List<Document> deepCopyFulfilments(List<Document> source) {
        return source.stream()
            .map(d -> Document.parse(d.toJson()))
            .collect(Collectors.toCollection(ArrayList::new));
    }
}
