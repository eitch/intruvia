// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.retention;

import java.util.List;
import li.strolch.model.Resource;
import li.strolch.persistence.api.StrolchTransaction;
import li.strolch.search.ResourceSearch;
import static li.intruvia.core.model.ModelConstants.*;

/** Internal search authorized with the invoking ingestion or maintenance service privilege. */
final class RetentionSearch extends ResourceSearch {
	private final String privilege;
	RetentionSearch(String privilege) { this.privilege = privilege; }
	@Override public String getPrivilegeName() { return this.privilege; }
	@Override public Object getPrivilegeValue() { return this.privilege; }

	List<Resource> resources(StrolchTransaction tx, String type) {
		types(type);
		return search(tx).toList();
	}

}
