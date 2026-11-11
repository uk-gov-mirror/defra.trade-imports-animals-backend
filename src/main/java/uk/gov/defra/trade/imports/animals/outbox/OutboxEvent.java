package uk.gov.defra.trade.imports.animals.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "outbox")
@CompoundIndex(name = "aggregate_version_uq",
    def = "{'aggregateId': 1, 'aggregateVersion': 1}",
    unique = true)
@CompoundIndex(name = "unpublished_poll_order",
    def = "{'publishedAt': 1, 'timestamp': 1, 'aggregateVersion': 1}",
    partialFilter = "{'publishedAt': null}")
@CompoundIndex(name = "aggregate_event_type",
    def = "{'aggregateId': 1, 'eventType': 1}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    private String eventId;

    @Indexed
    private String aggregateId;

    private String aggregateType;
    private String subType;
    private long aggregateVersion;
    private String eventType;
    private Instant timestamp;
    private Map<String, Object> data;
    private OutboxEventMetadata metadata;
    private Actor actor;
    private List<StatusChange> statusChanges;

    /** Owning organisation on the notification aggregate, not the session actor. */
    private String organisationId;

    /** Publish timestamp set before SNS publish. 
     * Ignored from published event but stores the published timestamp */
    @JsonIgnore
    private Instant publishedAt;
}
