package uk.gov.defra.trade.imports.animals.notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;

/**
 * Interface projection backing {@code GET /notifications?…}. SpEL accessors unwrap
 * {@code notification.*} content fields to preserve the pre-refactor flat wire shape.
 *
 * <p>The {@code @Value} accessors force Spring Data into an open projection, so the full
 * aggregate document (including the opaque {@code fulfilments} payload) is loaded per row.
 * Accepted trade-off: this endpoint is being replaced by an event-populated dashboard service
 * and there are no live users to notice the cost.
 *
 * <p>{@link Data} is the concrete carrier Jackson deserializes into on the client side; Spring
 * Data returns proxy instances on the server side.
 */
@JsonDeserialize(as = NotificationView.Data.class)
public interface NotificationView {

    String getReferenceNumber();

    Long getConcurrencyToken();

    NotificationStatus getStatus();

    LocalDateTime getCreated();

    @Value("#{target.notification?.origin}")
    Origin getOrigin();

    @Value("#{target.notification?.commodity}")
    Commodity getCommodity();

    @Value("#{target.notification?.consignor}")
    ConsignmentParty getConsignor();

    @Value("#{target.notification?.consignee}")
    ConsignmentParty getConsignee();

    @Value("#{target.notification?.transport}")
    Transport getTransport();

    /**
     * The content frozen at submit. Server-only — the raw material for {@link #forDashboard()},
     * never serialized. Free to load: the projection is already open.
     */
    @JsonIgnore
    Notification getSubmittedNotificationBaseline();

    /**
     * This row as the dashboard should read it.
     *
     * <p>A submitted notification is part of the legal record, so its parties come from the
     * snapshot frozen at submit rather than from a reference the caller would resolve against
     * today's address book. They are handed over <em>inline</em> — details without an
     * {@code addressId} — so a consumer that resolves references simply reads the frozen name and
     * makes no lookup at all. Drafts and in-flight amendments keep their reference, which is the
     * whole point of the reference: they are meant to reflect edits.
     *
     * <p>The baseline itself is dropped on the way out either way.
     */
    default NotificationView forDashboard() {
        Notification frozen = getSubmittedNotificationBaseline();
        boolean useFrozen = getStatus() == NotificationStatus.SUBMITTED && frozen != null;
        return new Data(
            getReferenceNumber(),
            getConcurrencyToken(),
            getStatus(),
            getCreated(),
            getOrigin(),
            getCommodity(),
            useFrozen ? ConsignmentParty.inlineOnly(frozen.getConsignor()) : getConsignor(),
            useFrozen ? ConsignmentParty.inlineOnly(frozen.getConsignee()) : getConsignee(),
            getTransport(),
            null);
    }

    /** Jackson deserialization target — flat, matches the on-wire JSON produced by the projection. */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class Data implements NotificationView {
        private String referenceNumber;
        private Long concurrencyToken;
        private NotificationStatus status;
        private LocalDateTime created;
        private Origin origin;
        private Commodity commodity;
        private ConsignmentParty consignor;
        private ConsignmentParty consignee;
        private Transport transport;
        @JsonIgnore
        private Notification submittedNotificationBaseline;
    }
}
