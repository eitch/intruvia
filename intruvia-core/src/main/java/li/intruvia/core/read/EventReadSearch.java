// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.read;

import li.intruvia.core.model.EventModelMapper;
import li.intruvia.core.model.SecurityEvent;
import li.strolch.model.Resource;
import li.strolch.persistence.api.StrolchTransaction;
import li.strolch.search.ResourceSearch;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import static li.intruvia.core.model.ModelConstants.*;

/**
 * Scoped search: no generic search/admin privilege or mutable resources escape to a viewer.
 */
final class EventReadSearch extends ResourceSearch {
	@Override
	public String getPrivilegeName() {
		return EventReadService.READ_PRIVILEGE;
	}

	@Override
	public Object getPrivilegeValue() {
		return EventReadService.READ_PRIVILEGE;
	}

	List<SecurityEvent> window(StrolchTransaction tx, long after, long through, int limit, boolean latest) {
		types(TYPE_SECURITY_EVENT);
		where(resource -> sequence(resource) > after && sequence(resource) <= through);
		Comparator<Resource> order = Comparator.comparingLong(EventReadSearch::sequence);
		// Keep only limit candidates while scanning the cached realm, rather than sorting all retained resources.
		var candidates = new PriorityQueue<Resource>(limit, latest ? order : order.reversed());
		search(tx).forEach(resource -> {
			candidates.add(resource);
			if (candidates.size() > limit)
				candidates.remove();
		});
		var mapper = new EventModelMapper();
		return candidates.stream().sorted(order).map(mapper::toEvent).toList();
	}

	private static long sequence(Resource resource) {
		return resource.getLong(BAG_ENVELOPE, PARAM_SEQUENCE);
	}
}
