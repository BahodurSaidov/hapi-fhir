package ca.uhn.fhir.cli;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.provider.TerminologyUploaderProvider;
import ca.uhn.fhir.jpa.term.UploadStatistics;
import ca.uhn.fhir.jpa.term.api.ITermLoaderSvc;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.CapturingInterceptor;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.test.BaseTest;
import ca.uhn.fhir.test.utilities.JettyUtil;
import ca.uhn.fhir.test.utilities.server.RestfulServerExtension;
import com.google.common.base.Charsets;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.hamcrest.Matchers;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UploadTerminologyCommandTest extends BaseTest {

	static {
		System.setProperty("test", "true");
	}

	private Server myServer;
	private FhirContext myCtx = FhirContext.forR4();
	@Mock
	private ITermLoaderSvc myTermLoaderSvc;
	@Captor
	private ArgumentCaptor<List<ITermLoaderSvc.FileDescriptor>> myDescriptorListCaptor;

	private int myPort;
	private String myConceptsFileName = "target/concepts.csv";
	private File myConceptsFile = new File(myConceptsFileName);
	private String myHierarchyFileName = "target/hierarchy.csv";
	private File myHierarchyFile = new File(myHierarchyFileName);
	private String myCodeSystemFileName = "target/codesystem.json";
	private File myCodeSystemFile = new File(myCodeSystemFileName);
	private String myTextFileName = "target/hello.txt";
	private File myTextFile = new File(myTextFileName);
	private File myArchiveFile;
	private String myArchiveFileName;
	private String myPropertiesFileName = "target/hello.properties";
	private File myPropertiesFile = new File(myTextFileName);

	@Test
	public void testDeltaAdd() throws IOException {

		writeConceptAndHierarchyFiles();

		when(myTermLoaderSvc.loadDeltaAdd(eq("http://foo"), anyList(), any())).thenReturn(new UploadStatistics(100, new IdType("CodeSystem/101")));

		App.main(new String[]{
			UploadTerminologyCommand.UPLOAD_TERMINOLOGY,
			"-v", "r4",
			"-m", "ADD",
			"-t", "http://localhost:" + myPort,
			"-u", "http://foo",
			"-d", myConceptsFileName,
			"-d", myHierarchyFileName
		});

		verify(myTermLoaderSvc, times(1)).loadDeltaAdd(eq("http://foo"), myDescriptorListCaptor.capture(), any());

		List<ITermLoaderSvc.FileDescriptor> listOfDescriptors = myDescriptorListCaptor.getValue();
		assertEquals(1, listOfDescriptors.size());
		assertEquals("file:/files.zip", listOfDescriptors.get(0).getFilename());
		assertThat(IOUtils.toByteArray(listOfDescriptors.get(0).getInputStream()).length, greaterThan(100));
	}

	@Test
	public void testDeltaAddUsingCodeSystemResource() throws IOException {

		try (FileWriter w = new FileWriter(myCodeSystemFile, false)) {
			CodeSystem cs = new CodeSystem();
			cs.addConcept().setCode("CODE").setDisplay("Display");
			myCtx.newJsonParser().encodeResourceToWriter(cs, w);
		}

		when(myTermLoaderSvc.loadDeltaAdd(eq("http://foo"), anyList(), any())).thenReturn(new UploadStatistics(100, new IdType("CodeSystem/101")));

		App.main(new String[]{
			UploadTerminologyCommand.UPLOAD_TERMINOLOGY,
			"-v", "r4",
			"-m", "ADD",
			"-t", "http://localhost:" + myPort,
			"-u", "http://foo",
			"-d", myCodeSystemFileName
		});

		verify(myTermLoaderSvc, times(1)).loadDeltaAdd(eq("http://foo"), myDescriptorListCaptor.capture(), any());

		List<ITermLoaderSvc.FileDescriptor> listOfDescriptors = myDescriptorListCaptor.getValue();
		assertEquals(2, listOfDescriptors.size());
		assertEquals("concepts.csv", listOfDescriptors.get(0).getFilename());
		String uploadFile = IOUtils.toString(listOfDescriptors.get(0).getInputStream(), Charsets.UTF_8);
		assertThat(uploadFile, uploadFile, containsString("\"CODE\",\"Display\""));
	}

	@Test
	public void testDeltaAddInvalidResource() throws IOException {

		try (FileWriter w = new FileWriter(myCodeSystemFile, false)) {
			Patient patient = new Patient();
			patient.setActive(true);
			myCtx.newJsonParser().encodeResourceToWriter(patient, w);
		}

		try {
			App.main(new String[]{
				UploadTerminologyCommand.UPLOAD_TERMINOLOGY,
				"-v", "r4",
				"-m", "ADD",
				"-t", "http://localhost:" + myPort,
				"-u", "http://foo",
				"-d", myCodeSystemFileName
			});
			fail();
		} catch (Error e) {
			assertThat(e.toString(), containsString("Incorrect resource type found, expected \"CodeSystem\" but found \"Patient\""));
		}
	}

	@Test
	public void testDeltaAddInvalidFileType() throws IOException {

		try (FileWriter w = new FileWriter(myTextFileName, false)) {
			w.append("Help I'm a Bug");
		}

		try {
			App.main(new String[]{
				UploadTerminologyCommand.UPLOAD_TERMINOLOGY,
				"-v", "r4",
				"-m", "ADD",
				"-t", "http://localhost:" + myPort,
				"-u", "http://foo",
				"-d", myTextFileName
			});
			fail();
		} catch (Error e) {
			assertThat(e.toString(), containsString("Don't know how to handle file:"));
		}
	}

	@Test
	public void testDeltaAddUsingCompressedFile() throws IOException {

		writeConceptAndHierarchyFiles();
		writeArchiveFile(myConceptsFile, myHierarchyFile);

		when(myTermLoaderSvc.loadDeltaAdd(eq("http://foo"), anyList(), any())).thenReturn(new UploadStatistics(100, new IdType("CodeSystem/101")));

		App.main(new String[]{
			UploadTerminologyCommand.UPLOAD_TERMINOLOGY,
			"-v", "r4",
			"-m", "ADD",
			"-t", "http://localhost:" + myPort,
			"-u", "http://foo",
			"-d", myArchiveFileName
		});

		verify(myTermLoaderSvc, times(1)).loadDeltaAdd(eq("http://foo"), myDescriptorListCaptor.capture(), any());

		List<ITermLoaderSvc.FileDescriptor> listOfDescriptors = myDescriptorListCaptor.getValue();
		assertEquals(1, listOfDescriptors.size());
		assertThat(listOfDescriptors.get(0).getFilename(), matchesPattern("^file:.*temp.*\\.zip$"));
		assertThat(IOUtils.toByteArray(listOfDescriptors.get(0).getInputStream()).length, greaterThan(100));
	}

	@Test
	public void testDeltaAddInvalidFileName() throws IOException {

		writeConceptAndHierarchyFiles();

		try {
			App.main(new String[]{
				UploadTerminologyCommand.UPLOAD_TERMINOLOGY,
				"-v", "r4",
				"-m", "ADD",
				"-t", "http://localhost:" + myPort,
				"-u", "http://foo",
				"-d", myConceptsFileName + "/foo.csv",
				"-d", myHierarchyFileName
			});
		} catch (Error e) {
			assertThat(e.toString(), Matchers.containsString("FileNotFoundException: target/concepts.csv/foo.csv"));
		}
	}

	@Test
	public void testDeltaRemove() throws IOException {
		writeConceptAndHierarchyFiles();

		when(myTermLoaderSvc.loadDeltaRemove(eq("http://foo"), anyList(), any())).thenReturn(new UploadStatistics(100, new IdType("CodeSystem/101")));

		App.main(new String[]{
			UploadTerminologyCommand.UPLOAD_TERMINOLOGY,
			"-v", "r4",
			"-m", "REMOVE",
			"-t", "http://localhost:" + myPort,
			"-u", "http://foo",
			"-d", myConceptsFileName,
			"-d", myHierarchyFileName
		});

		verify(myTermLoaderSvc, times(1)).loadDeltaRemove(eq("http://foo"), myDescriptorListCaptor.capture(), any());

		List<ITermLoaderSvc.FileDescriptor> listOfDescriptors = myDescriptorListCaptor.getValue();
		assertEquals(1, listOfDescriptors.size());
		assertEquals("file:/files.zip", listOfDescriptors.get(0).getFilename());
		assertThat(IOUtils.toByteArray(listOfDescriptors.get(0).getInputStream()).length, greaterThan(100));

	}

	@Test
	public void testSnapshot() throws IOException {



		writeConceptAndHierarchyFiles();

		when(myTermLoaderSvc.loadCustom(any(), anyList(), any())).thenReturn(new UploadStatistics(100, new IdType("CodeSystem/101")));

		App.main(new String[]{
			UploadTerminologyCommand.UPLOAD_TERMINOLOGY,
			"-v", "r4",
			"-m", "SNAPSHOT",
			"-t", "http://localhost:" + myPort,
			"-u", "http://foo",
			"-d", myConceptsFileName,
			"-d", myHierarchyFileName
		});

		verify(myTermLoaderSvc, times(1)).loadCustom(any(), myDescriptorListCaptor.capture(), any());

		List<ITermLoaderSvc.FileDescriptor> listOfDescriptors = myDescriptorListCaptor.getValue();
		assertEquals(1, listOfDescriptors.size());
		assertEquals("file:/files.zip", listOfDescriptors.get(0).getFilename());
		assertThat(IOUtils.toByteArray(listOfDescriptors.get(0).getInputStream()).length, greaterThan(100));
	}

	@Test
	public void testPropertiesFile() throws IOException {
		try (FileWriter w = new FileWriter(myPropertiesFileName, false)) {
			w.append("a=b\n");
		}

		when(myTermLoaderSvc.loadCustom(any(), anyList(), any())).thenReturn(new UploadStatistics(100, new IdType("CodeSystem/101")));

		App.main(new String[]{
			UploadTerminologyCommand.UPLOAD_TERMINOLOGY,
			"-v", "r4",
			"-m", "SNAPSHOT",
			"-t", "http://localhost:" + myPort,
			"-u", "http://foo",
			"-d", myPropertiesFileName,
		});

		verify(myTermLoaderSvc, times(1)).loadCustom(any(), myDescriptorListCaptor.capture(), any());

		List<ITermLoaderSvc.FileDescriptor> listOfDescriptors = myDescriptorListCaptor.getValue();
		assertEquals(1, listOfDescriptors.size());
		assertThat(listOfDescriptors.get(0).getFilename(), matchesPattern(".*\\.zip$"));
		assertThat(IOUtils.toByteArray(listOfDescriptors.get(0).getInputStream()).length, greaterThan(100));


	}

	/**
	 * When transferring large files, we use a local file to store the binary instead of
	 * using HTTP to transfer a giant base 64 encoded attachment. Hopefully we can
	 * replace this with a bulk data import at some point when that gets implemented.
	 */
	@Test
	public void testSnapshotLargeFile() throws IOException {
		UploadTerminologyCommand.setTransferSizeLimitForUnitTest(10);

		writeConceptAndHierarchyFiles();

		when(myTermLoaderSvc.loadCustom(any(), anyList(), any())).thenReturn(new UploadStatistics(100, new IdType("CodeSystem/101")));

		App.main(new String[]{
			UploadTerminologyCommand.UPLOAD_TERMINOLOGY,
			"-v", "r4",
			"-m", "SNAPSHOT",
			"-t", "http://localhost:" + myPort,
			"-u", "http://foo",
			"-d", myConceptsFileName,
			"-d", myHierarchyFileName
		});

		verify(myTermLoaderSvc, times(1)).loadCustom(any(), myDescriptorListCaptor.capture(), any());

		List<ITermLoaderSvc.FileDescriptor> listOfDescriptors = myDescriptorListCaptor.getValue();
		assertEquals(1, listOfDescriptors.size());
		assertThat(listOfDescriptors.get(0).getFilename(), matchesPattern(".*\\.zip$"));
		assertThat(IOUtils.toByteArray(listOfDescriptors.get(0).getInputStream()).length, greaterThan(100));
	}

	@Nested
	public class HeaderPassthroughOptionTests {

		@RegisterExtension
		public final RestfulServerExtension myRestfulServerExtension = new RestfulServerExtension(myCtx);

		private final String headerKey1 = "test-header-key-1";
		private final String headerValue1 = "test header value-1";

		private final CapturingInterceptor myCapturingInterceptor = new CapturingInterceptor();
		private final UploadTerminologyCommand testedCommand =
			new RequestCapturingUploadTerminologyCommand(myCapturingInterceptor);

		@BeforeEach
		public void before() {
			when(myTermLoaderSvc.loadCustom(eq("http://foo"), anyList(), any()))
				.thenReturn(new UploadStatistics(100, new IdType("CodeSystem/101")));

			TerminologyUploaderProvider provider = new TerminologyUploaderProvider(myCtx, myTermLoaderSvc);
			myRestfulServerExtension.registerProvider(provider);
		}


		@Test
		public void oneHeader() throws Exception {
			String[] args = new String[] {
				"-v", "r4",
				"-m", "SNAPSHOT",
				"-t", "http://localhost:" + myRestfulServerExtension.getPort(),
				"-u", "http://foo",
				"-d", myConceptsFileName,
				"-d", myHierarchyFileName,
				"-hp", "\"" + headerKey1 + ":" + headerValue1 + "\""
			};

			writeConceptAndHierarchyFiles();
			final CommandLine commandLine = new DefaultParser().parse(testedCommand.getOptions(), args, true);
			testedCommand.run(commandLine);

			assertNotNull(myCapturingInterceptor.getLastRequest());
			Map<String, List<String>> allHeaders = myCapturingInterceptor.getLastRequest().getAllHeaders();
			assertFalse(allHeaders.isEmpty());

			assertTrue(allHeaders.containsKey(headerKey1));
			assertEquals(1, allHeaders.get(headerKey1).size());

			assertThat(allHeaders.get(headerKey1), hasItems(headerValue1));
		}


		@Test
		public void twoHeadersSameKey() throws Exception {
			final String headerValue2 = "test header value-2";

			String[] args = new String[] {
				"-v", "r4",
				"-m", "SNAPSHOT",
				"-t", "http://localhost:" + myRestfulServerExtension.getPort(),
				"-u", "http://foo",
				"-d", myConceptsFileName,
				"-d", myHierarchyFileName,
				"-hp", "\"" + headerKey1 + ":" + headerValue1 + "\"",
				"-hp", "\"" + headerKey1 + ":" + headerValue2 + "\""
			};

			writeConceptAndHierarchyFiles();
			final CommandLine commandLine = new DefaultParser().parse(testedCommand.getOptions(), args, true);
			testedCommand.run(commandLine);

			assertNotNull(myCapturingInterceptor.getLastRequest());
			Map<String, List<String>> allHeaders = myCapturingInterceptor.getLastRequest().getAllHeaders();
			assertFalse(allHeaders.isEmpty());
			assertEquals(2, allHeaders.get(headerKey1).size());

			assertTrue(allHeaders.containsKey(headerKey1));
			assertEquals(2, allHeaders.get(headerKey1).size());

			assertEquals(headerValue1, allHeaders.get(headerKey1).get(0));
			assertEquals(headerValue2, allHeaders.get(headerKey1).get(1));
		}

		@Test
		public void twoHeadersDifferentKeys() throws Exception {
			final String headerKey2 = "test-header-key-2";
			final String headerValue2 = "test header value-2";

			String[] args = new String[] {
				"-v", "r4",
				"-m", "SNAPSHOT",
				"-t", "http://localhost:" + myRestfulServerExtension.getPort(),
				"-u", "http://foo",
				"-d", myConceptsFileName,
				"-d", myHierarchyFileName,
				"-hp", "\"" + headerKey1 + ":" + headerValue1 + "\"",
				"-hp", "\"" + headerKey2 + ":" + headerValue2 + "\""
			};

			writeConceptAndHierarchyFiles();
			final CommandLine commandLine = new DefaultParser().parse(testedCommand.getOptions(), args, true);
			testedCommand.run(commandLine);

			assertNotNull(myCapturingInterceptor.getLastRequest());
			Map<String, List<String>> allHeaders = myCapturingInterceptor.getLastRequest().getAllHeaders();
			assertFalse(allHeaders.isEmpty());

			assertTrue(allHeaders.containsKey(headerKey1));
			assertEquals(1, allHeaders.get(headerKey1).size());
			assertThat(allHeaders.get(headerKey1), hasItems(headerValue1));

			assertTrue(allHeaders.containsKey(headerKey2));
			assertEquals(1, allHeaders.get(headerKey2).size());
			assertThat(allHeaders.get(headerKey2), hasItems(headerValue2));
		}


		private class RequestCapturingUploadTerminologyCommand extends UploadTerminologyCommand {
			private CapturingInterceptor myCapturingInterceptor;

			public RequestCapturingUploadTerminologyCommand(CapturingInterceptor theCapturingInterceptor) {
				myCapturingInterceptor = theCapturingInterceptor;
			}

			@Override
			protected IGenericClient newClient(CommandLine theCommandLine) throws ParseException {
				IGenericClient client = super.newClient(theCommandLine);
				client.getInterceptorService().registerInterceptor(myCapturingInterceptor);
				return client;
			}
		}
	}



	private void writeArchiveFile(File... theFiles) throws IOException {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream, Charsets.UTF_8);

		for (File next : theFiles) {
			ZipEntry nextEntry = new ZipEntry(UploadTerminologyCommand.stripPath(next.getAbsolutePath()));
			zipOutputStream.putNextEntry(nextEntry);

			try (FileInputStream fileInputStream = new FileInputStream(next)) {
				IOUtils.copy(fileInputStream, zipOutputStream);
			}

		}

		zipOutputStream.flush();
		zipOutputStream.close();

		myArchiveFile = File.createTempFile("temp", ".zip");
		myArchiveFile.deleteOnExit();
		myArchiveFileName = myArchiveFile.getAbsolutePath();
		try (FileOutputStream fos = new FileOutputStream(myArchiveFile, false)) {
			fos.write(byteArrayOutputStream.toByteArray());
		}
	}


	private void writeConceptAndHierarchyFiles() throws IOException {
		try (FileWriter w = new FileWriter(myConceptsFile, false)) {
			w.append("CODE,DISPLAY\n");
			w.append("ANIMALS,Animals\n");
			w.append("CATS,Cats\n");
			w.append("DOGS,Dogs\n");
		}

		try (FileWriter w = new FileWriter(myHierarchyFile, false)) {
			w.append("PARENT,CHILD\n");
			w.append("ANIMALS,CATS\n");
			w.append("ANIMALS,DOGS\n");
		}
	}


	@AfterEach
	public void after() throws Exception {
		JettyUtil.closeServer(myServer);

		FileUtils.deleteQuietly(myConceptsFile);
		FileUtils.deleteQuietly(myHierarchyFile);
		FileUtils.deleteQuietly(myArchiveFile);
		FileUtils.deleteQuietly(myCodeSystemFile);
		FileUtils.deleteQuietly(myTextFile);
		FileUtils.deleteQuietly(myPropertiesFile);

		UploadTerminologyCommand.setTransferSizeLimitForUnitTest(-1);
	}

	@BeforeEach
	public void before() throws Exception {
		myServer = new Server(0);

		TerminologyUploaderProvider provider = new TerminologyUploaderProvider(myCtx, myTermLoaderSvc);

		ServletHandler proxyHandler = new ServletHandler();
		RestfulServer servlet = new RestfulServer(myCtx);
		servlet.registerProvider(provider);
		ServletHolder servletHolder = new ServletHolder(servlet);
		proxyHandler.addServletWithMapping(servletHolder, "/*");
		myServer.setHandler(proxyHandler);
		JettyUtil.startServer(myServer);
		myPort = JettyUtil.getPortForStartedServer(myServer);

	}


}
