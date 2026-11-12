// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ManagedChildTest {
	@Test
	public void returnsTheChildExitStatus() throws Exception {
		assertEquals(7, ManagedChild.run(new ProcessBuilder("/bin/sh", "-c", "exit 7"), 5, TimeUnit.SECONDS));
	}

	@Test(timeout = 10000)
	public void terminatesAnOverdueChild() {
		var failure = assertThrows(ConfigurationFailure.class,
				() -> ManagedChild.run(new ProcessBuilder("/bin/sh", "-c", "exec sleep 30"), 100, TimeUnit.MILLISECONDS));
		assertFalse(failure.getMessage().contains("sleep"));
	}
}