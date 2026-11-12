// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

final class ProvisioningFiles {
	private ProvisioningFiles() {}

	static byte[] template(Path runtimeTemplates, String name) throws IOException {
		Path config = runtimeTemplates.resolve("config");
		Path file = config.resolve(name);
		if (!Files.isDirectory(runtimeTemplates, LinkOption.NOFOLLOW_LINKS) ||
				!Files.isDirectory(config, LinkOption.NOFOLLOW_LINKS) ||
				!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024)
			throw failure();
		return Files.readAllBytes(file);
	}

	static void directory(Path path) throws IOException {
		Files.createDirectory(path, PosixFilePermissions.asFileAttribute(RuntimeLock.DIRECTORY_PERMISSIONS));
	}

	static void writeNew(Path path, byte[] bytes) throws IOException {
		Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
		try (var channel = FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(RuntimeLock.FILE_PERMISSIONS))) {
			var buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) channel.write(buffer);
			channel.force(true);
		}
	}

	static void moveNew(Path source, Path target) throws IOException {
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw failure();
		Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		forceDirectory(target.getParent());
	}

	static void replaceStaged(Path path, byte[] bytes) throws IOException {
		Path replacement = path.resolveSibling(path.getFileName() + ".new");
		writeNew(replacement, bytes);
		Files.move(replacement, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		forceDirectory(path.getParent());
	}

	static void forceDirectory(Path path) throws IOException {
		try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
			channel.force(true);
		}
	}

	static Document xml(byte[] bytes) throws Exception {
		var factory = DocumentBuilderFactory.newInstance();
		factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
		factory.setXIncludeAware(false);
		factory.setExpandEntityReferences(false);
		var builder = factory.newDocumentBuilder();
		builder.setErrorHandler(new DefaultHandler() {
			@Override public void error(SAXParseException e) throws SAXException { throw e; }
			@Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
		});
		return builder.parse(new ByteArrayInputStream(bytes));
	}

	static byte[] xml(Document document) throws Exception {
		var factory = TransformerFactory.newInstance();
		factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
		var transformer = factory.newTransformer();
		transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		var output = new ByteArrayOutputStream();
		transformer.transform(new DOMSource(document), new StreamResult(output));
		return output.toByteArray();
	}

	static ConfigurationFailure failure() {
		return new ConfigurationFailure("Initialization failed; preserve the runtime and database and follow recovery instructions");
	}
}