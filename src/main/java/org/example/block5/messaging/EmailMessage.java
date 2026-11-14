
package org.example.block5.messaging;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
@Jacksonized
public class EmailMessage {

    private String recipient;

    private String subject;

    private String content;

    private String source;

    private Long sourceId;
}
