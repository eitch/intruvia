// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.model;

public record EventStreamState(String lastCommittedSequence, String minAfter) {
	public EventStreamState {
		lastCommittedSequence = ContractValues.sequence(lastCommittedSequence);
		minAfter = ContractValues.sequence(minAfter);
		if (Long.parseLong(minAfter) > Long.parseLong(lastCommittedSequence))
			throw new IllegalArgumentException("Replay minimum exceeds stream head");
	}
}
