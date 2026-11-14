package org.example.block5.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "email-messages")
public class EmailData {

    @Id
    private String id;

    private String recipient;

    private String subject;

    private String content;

    private EmailStatus status;

    private String errorMessage;

    private int attemptCount;

    @Field(type = FieldType.Date, pattern = "epoch_millis")
    private Instant createdAt;

    @Field(type = FieldType.Date, pattern = "epoch_millis")
    private Instant lastAttemptAt;

}