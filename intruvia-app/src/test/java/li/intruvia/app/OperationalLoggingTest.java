// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import static org.junit.Assert.*;

public class OperationalLoggingTest {
	@Test public void productionPolicySuppressesFrameworkValuesAndActuallyRollsFiles() throws Exception {
		Path folder = Files.createTempDirectory(Path.of("target"), "bounded-logs-").toAbsolutePath();
		var context = new LoggerContext();
		context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
		var compression = context.getExecutorService();
		var cleanup = context.getAlternateExecutorService();
		try {
			context.putProperty("intruvia.log.dir", folder.toString());
			var configurator = new JoranConfigurator();
			configurator.setContext(context);
			// Exercise the shipped policy with smaller thresholds; do not generate megabytes for a unit check.
			String xml = Files.readString(Path.of("src/main/resources/logback.xml")).replace("10MB", "1KB").replace("50MB", "3KB");
			configurator.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
			var logger = context.getLogger("li.intruvia.app.RequestLifecycle");
			var appender = (RollingFileAppender<?>) context.getLogger("li.intruvia").getAppender("FILE");
			assertTrue(appender.isStarted());
			assertTrue(appender.getRollingPolicy() instanceof SizeAndTimeBasedRollingPolicy);
			context.getLogger("li.strolch.persistence").error("CANARY-credential CANARY-payload");
			for (int index = 0; index < 150; index++)
				logger.warn("request_failed requestId=00000000-0000-0000-0000-000000000000 status=503");
			appender.stop();
			compression.shutdown();
			cleanup.shutdown();
			assertTrue("Compression tasks finished", compression.awaitTermination(30, TimeUnit.SECONDS));
			assertTrue("Cleanup tasks finished", cleanup.awaitTermination(30, TimeUnit.SECONDS));
		} finally {
			context.stop();
			compression.shutdownNow();
			cleanup.shutdownNow();
		}
		boolean archived = false;
		try (var files = Files.list(folder)) {
			for (Path file : files.toList()) {
				String text;
				if (file.toString().endsWith(".gz")) {
					archived = true;
					try (var stream = new GZIPInputStream(Files.newInputStream(file))) {
						text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
					}
				} else text = Files.readString(file);
				assertFalse(text.contains("CANARY"));
				assertTrue(Files.size(file) < 2048);
			}
		}
		assertTrue("Size rollover created an archive", archived);
	}
}
