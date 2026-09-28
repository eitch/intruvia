// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.model;

import java.util.Objects;
import java.util.UUID;

public record IngestReceipt(String id, String instanceId, UUID sourceEventId, String payloadDigest,
		UUID eventId, String sequence, String expiresAt) {
	public IngestReceipt {
		Objects.requireNonNull(id);
		Objects.requireNonNull(instanceId);
		Objects.requireNonNull(sourceEventId);
		Objects.requireNonNull(payloadDigest);
		Objects.requireNonNull(eventId);
		sequence = ContractValues.sequence(sequence);
		if (sequence.equals("0"))
			throw new IllegalArgumentException("Receipt sequence must be positive");
		expiresAt = ContractValues.timestamp(Objects.requireNonNull(expiresAt));
	}
}
