package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import java.util.List;
import org.bson.Document;

/**
 * Spring Data interface projection over the {@code notification} collection backing
 * {@code GET /notifications/{ref}/fulfilments}. Server-only fields
 * ({@code submittedFulfilmentsBaseline}, {@code expireAt}) are intentionally omitted so they
 * aren't loaded on read.
 *
 * <p>{@code submittedNotificationBaseline} <em>is</em> exposed, narrowed to its parties by
 * {@link FrozenParties}. It used to be omitted with the rest; a submitted notification now reads
 * its addresses from the freeze rather than resolving live, so the parties have to travel with
 * the read. Only the parties — the snapshot's other content is not needed here and stays behind.
 */
public interface NotificationFulfilmentsView {

    String getReferenceNumber();

    Long getConcurrencyToken();

    NotificationStatus getStatus();

    LocalDateTime getCreated();

    LocalDateTime getSubmittedAt();

    List<Document> getFulfilments();

    /**
     * The parties as they were at submit, or {@code null} for a notification that has never been
     * submitted. Present for SUBMITTED and for an in-flight AMEND; only a submitted notification
     * should be rendered from it — an amendment is meant to show live details.
     */
    FrozenParties getSubmittedNotificationBaseline();

    /**
     * The six role fields of the submitted freeze, carrying resolved details rather than a
     * reference, so a reader renders them without touching the address book.
     */
    interface FrozenParties {

        ConsignmentParty getPlaceOfOrigin();

        ConsignmentParty getConsignor();

        ConsignmentParty getConsignee();

        ConsignmentParty getImporter();

        ConsignmentParty getDestination();

        ConsignmentParty getConsignment();
    }
}
