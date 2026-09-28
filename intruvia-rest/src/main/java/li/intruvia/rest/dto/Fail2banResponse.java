// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.dto;

import java.util.UUID;
import li.intruvia.core.model.ContractValues;
import li.intruvia.core.model.GeoStatus;
import java.util.Objects;

public record Fail2banResponse(UUID id, String sequence, boolean duplicate, GeoStatus geoStatus) {
	public Fail2banResponse {
		Objects.requireNonNull(id);
		sequence = ContractValues.sequence(sequence);
		if (sequence.equals("0"))
			throw new IllegalArgumentException("Response sequence must be positive");
		Objects.requireNonNull(geoStatus);
	}
}
