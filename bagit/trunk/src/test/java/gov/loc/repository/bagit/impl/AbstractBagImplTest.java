package gov.loc.repository.bagit.impl;

import static org.junit.Assert.*;

import org.apache.commons.io.FileUtils;
import org.junit.Ignore;
import org.junit.Test;

import gov.loc.repository.bagit.Bag;
import gov.loc.repository.bagit.BagFactory;
import gov.loc.repository.bagit.BagInfoTxt;
import gov.loc.repository.bagit.BagInfoTxtWriter;
import gov.loc.repository.bagit.BagItTxt;
import gov.loc.repository.bagit.Manifest;
import gov.loc.repository.bagit.ManifestHelper;
import gov.loc.repository.bagit.ManifestWriter;
import gov.loc.repository.bagit.Bag.BagConstants;
import gov.loc.repository.bagit.Bag.BagPartFactory;
import gov.loc.repository.bagit.Bag.Format;
import gov.loc.repository.bagit.BagFactory.Version;
import gov.loc.repository.bagit.Manifest.Algorithm;
import gov.loc.repository.bagit.bag.DummyCancelIndicator;
import gov.loc.repository.bagit.utilities.ResourceHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.text.MessageFormat;
import java.util.List;


public abstract class AbstractBagImplTest {

	public abstract Version getVersion();
	
	public BagPartFactory factory = BagFactory.getBagPartFactory(this.getVersion());
	public BagConstants constants = BagFactory.getBagConstants(this.getVersion());

	private Bag getBag(Bag.Format format) throws Exception {
		return this.getBag(this.getVersion(), format);  
	}

	private Bag getBag(Version version, Bag.Format format) throws Exception {
		return BagFactory.createBag(this.getBagDir(version, format), version, true);  
	}	
	
	private File getBagDir(Version version, Bag.Format format) throws Exception {
		return ResourceHelper.getFile(MessageFormat.format("bags/{0}/bag{1}", version.toString().toLowerCase(), format.extension));		
	}
	
	private File createTestBag() throws Exception {
		return this.createTestBag(true);
	}

	private File createTestBag(boolean deleteTagManifest) throws Exception {
		File sourceBagDir = ResourceHelper.getFile(MessageFormat.format("bags/{0}/bag", this.getVersion().toString().toLowerCase()));
		File testBagDir = new File(sourceBagDir.getParentFile(), "test_bag");
		if (testBagDir.exists()) {
			FileUtils.forceDelete(testBagDir);
		}
		FileUtils.copyDirectory(sourceBagDir, testBagDir);
		if (deleteTagManifest) {
			File tagManifestFile = new File(testBagDir, ManifestHelper.getTagManifestFilename(Algorithm.MD5, this.constants));
			FileUtils.forceDelete(tagManifestFile);
		}
		return testBagDir;
	}

	private void testBag(Bag.Format format) throws Exception {
		Bag bag = this.getBag(format);
		assertEquals(format, bag.getFormat());
		
		List<Manifest> payloadManifests = bag.getPayloadManifests();
		assertEquals(1, payloadManifests.size());
		assertEquals("manifest-md5.txt", payloadManifests.get(0).getFilepath());
		
		List<Manifest> tagManifests = bag.getTagManifests();
		assertEquals(1, tagManifests.size());
		assertEquals("tagmanifest-md5.txt", tagManifests.get(0).getFilepath());
				
		assertEquals(4, bag.getTags().size());
		assertNotNull(bag.getBagFile("bagit.txt"));
		assertNull(bag.getBagFile("xbagit.txt"));
				
		assertEquals(5, bag.getPayload().size());
		assertNotNull(bag.getBagFile("data/dir1/test3.txt"));
		assertNull(bag.getBagFile("xdata/dir1/test3.txt"));		
		
		BagItTxt bagIt = bag.getBagItTxt();
		assertEquals("UTF-8", bagIt.getCharacterEncoding());
		assertEquals(this.getVersion().versionString, bagIt.getVersion());
		
		BagInfoTxt bagInfo = bag.getBagInfoTxt();
		assertEquals("Spengler University", bagInfo.getSourceOrganization());

		assertTrue(bag.checkComplete().isSuccess());
		assertTrue(bag.checkValid().isSuccess());
		assertTrue(bag.checkTagManifests().isSuccess());
		assertTrue(bag.checkPayloadManifests().isSuccess());
	}
	
	@Test
	public void testFileSystemBag() throws Exception {
		this.testBag(Bag.Format.FILESYSTEM);
	}

	@Test
	public void testZipBag() throws Exception {
		this.testBag(Bag.Format.ZIP);
	}

	@Test
	public void testTarBag() throws Exception {
		this.testBag(Bag.Format.TAR);
	}

	@Test
	public void testTarGz() throws Exception {
		this.testBag(Bag.Format.TAR_GZ);
	}

	@Test
	public void testTarBz2() throws Exception {
		this.testBag(Bag.Format.TAR_BZ2);
	}

	@Test
	public void testBagWithTwoEqualManifests() throws Exception {
		File testBagDir = this.createTestBag();
		File sha1ManifestFile = new File(testBagDir, ManifestHelper.getPayloadManifestFilename(Algorithm.SHA1, this.constants));
		ManifestWriter writer = this.factory.createManifestWriter(new FileOutputStream(sha1ManifestFile));
		writer.write("data/dir1/test3.txt", "3ebfa301dc59196f18593c45e519287a23297589");
		writer.write("data/dir2/dir3/test5.txt", "911ddc3b8f9a13b5499b6bc4638a2b4f3f68bf23");
		writer.write("data/dir2/test4.txt", "1ff2b3704aede04eecb51e50ca698efd50a1379b");
		writer.write("data/test1.txt", "b444ac06613fc8d63795be9ad0beaf55011936ac");
		writer.write("data/test2.txt", "109f4b3c50d7b0df729d299bc6f8e9ef9066971f");
		writer.close();
		Bag bag = BagFactory.createBag(testBagDir, this.getVersion(), true);
		assertEquals(2, bag.getPayloadManifests().size());

		assertTrue(bag.checkComplete().isSuccess());
		assertTrue(bag.checkValid().isSuccess());
		assertTrue(bag.checkTagManifests().isSuccess());
		assertTrue(bag.checkPayloadManifests().isSuccess());

	}

	@Test
	public void testBagWithTwoUnequalManifests() throws Exception {
		File testBagDir = this.createTestBag();
		File sha1ManifestFile = new File(testBagDir, ManifestHelper.getPayloadManifestFilename(Algorithm.SHA1, this.constants));
		ManifestWriter sha1Writer = this.factory.createManifestWriter(new FileOutputStream(sha1ManifestFile));
		sha1Writer.write("data/dir1/test3.txt", "3ebfa301dc59196f18593c45e519287a23297589");
		sha1Writer.write("data/dir2/dir3/test5.txt", "911ddc3b8f9a13b5499b6bc4638a2b4f3f68bf23");
		sha1Writer.write("data/dir2/test4.txt", "1ff2b3704aede04eecb51e50ca698efd50a1379b");
		sha1Writer.close();
		File md5ManifestFile = new File(testBagDir, ManifestHelper.getPayloadManifestFilename(Algorithm.MD5, this.constants));
		ManifestWriter md5Writer = this.factory.createManifestWriter(new FileOutputStream(md5ManifestFile));
		md5Writer.write("data/dir1/test3.txt", "8ad8757baa8564dc136c1e07507f4a98");
		md5Writer.write("data/test1.txt", "5a105e8b9d40e1329780d62ea2265d8a");
		md5Writer.write("data/test2.txt", "ad0234829205b9033196ba818f7a872b");
		md5Writer.close();

		Bag bag = BagFactory.createBag(testBagDir, this.getVersion(), true);
		assertEquals(2, bag.getPayloadManifests().size());

		assertTrue(bag.checkComplete().isSuccess());
		assertTrue(bag.checkValid().isSuccess());
		assertTrue(bag.checkTagManifests().isSuccess());
		assertTrue(bag.checkPayloadManifests().isSuccess());

	}

	@Test
	public void testBagWithNoBagItTxt() throws Exception {
		File testBagDir = this.createTestBag();
		File bagItTxtFile = new File(testBagDir, this.constants.getBagItTxt());
		assertTrue(bagItTxtFile.exists());
		FileUtils.forceDelete(bagItTxtFile);
		assertFalse(bagItTxtFile.exists());
		
		Bag bag = BagFactory.createBag(testBagDir, this.getVersion(), true);
		assertNull(bag.getBagItTxt());

		assertFalse(bag.checkComplete().isSuccess());
		assertFalse(bag.checkValid().isSuccess());				
		
		assertTrue(bag.checkComplete(true, null).isSuccess());
		assertTrue(bag.checkValid(true, null).isSuccess());

		assertTrue(bag.checkTagManifests().isSuccess());
		assertTrue(bag.checkPayloadManifests().isSuccess());
		
	}
	
	@Test
	public void testBagWithChangedPayloadFile() throws Exception {
		File testBagDir = this.createTestBag();
		File test1File = new File(testBagDir, "data/test1.txt");
		assertTrue(test1File.exists());
		FileWriter writer = new FileWriter(test1File);
		writer.write("xtest1");
		writer.close();
		
		Bag bag = BagFactory.createBag(testBagDir, this.getVersion(), true);

		assertTrue(bag.checkComplete().isSuccess());
		assertFalse(bag.checkValid().isSuccess());
		
		assertTrue(bag.checkTagManifests().isSuccess());
		assertFalse(bag.checkPayloadManifests().isSuccess());
		
	}

	@Test
	public void testBagWithChangedTagFile() throws Exception {
		File testBagDir = this.createTestBag(false);
		File bagInfoTxtFile = new File(testBagDir, this.constants.getBagInfoTxt());
		BagInfoTxtWriter writer = this.factory.createBagInfoTxtWriter(new FileOutputStream(bagInfoTxtFile), this.constants.getBagEncoding());
		writer.write("foo", "bar");
		writer.close();
		assertTrue(bagInfoTxtFile.exists());
		
		Bag bag = BagFactory.createBag(testBagDir, this.getVersion(), true);

		assertTrue(bag.checkComplete().isSuccess());
		assertFalse(bag.checkValid().isSuccess());
		
		assertFalse(bag.checkTagManifests().isSuccess());
		assertTrue(bag.checkPayloadManifests().isSuccess());
		
	}
	
	
	@Test
	public void testBagWithMissingPayloadFile() throws Exception {
		File testBagDir = this.createTestBag();
		File test1File = new File(testBagDir, "data/test1.txt");
		assertTrue(test1File.exists());
		FileUtils.forceDelete(test1File);
		assertFalse(test1File.exists());
		
		Bag bag = BagFactory.createBag(testBagDir, this.getVersion(), true);
		assertFalse(bag.checkComplete().isSuccess());
		assertFalse(bag.checkValid().isSuccess());
		
		assertTrue(bag.checkTagManifests().isSuccess());
		assertFalse(bag.checkPayloadManifests().isSuccess());

	}

	@Test
	public void testBagWithMissingTagFile() throws Exception {
		File testBagDir = this.createTestBag(false);
		File bagInfoTxtFile = new File(testBagDir, this.constants.getBagInfoTxt());
		assertTrue(bagInfoTxtFile.exists());
		FileUtils.forceDelete(bagInfoTxtFile);
		assertFalse(bagInfoTxtFile.exists());
		
		Bag bag = BagFactory.createBag(testBagDir, this.getVersion(), true);
		assertFalse(bag.checkComplete().isSuccess());
		assertFalse(bag.checkValid().isSuccess());
		
		assertFalse(bag.checkTagManifests().isSuccess());
		assertTrue(bag.checkPayloadManifests().isSuccess());

	}

	@Test
	public void testBagWithExtraPayloadFile() throws Exception {
		File testBagDir = this.createTestBag();
		File extraFile = new File(testBagDir, "data/extra.txt");
		FileWriter writer = new FileWriter(extraFile);
		writer.write("extra");
		writer.close();
		
		Bag bag = BagFactory.createBag(testBagDir, this.getVersion(), true);

		assertFalse(bag.checkComplete().isSuccess());
		assertFalse(bag.checkValid().isSuccess());
		
		assertTrue(bag.checkTagManifests().isSuccess());
		assertTrue(bag.checkPayloadManifests().isSuccess());

	}

	@Test
	public void testBagWithExtraTagFile() throws Exception {
		File testBagDir = this.createTestBag(false);
		File extraFile = new File(testBagDir, "extra.txt");
		FileWriter writer = new FileWriter(extraFile);
		writer.write("extra");
		writer.close();
		
		Bag bag = BagFactory.createBag(testBagDir, this.getVersion(), true);

		assertTrue(bag.checkComplete().isSuccess());
		assertTrue(bag.checkValid().isSuccess());
		
		assertTrue(bag.checkTagManifests().isSuccess());
		assertTrue(bag.checkPayloadManifests().isSuccess());

	}

	@Test
	public void testBagWithNoPayloadManifests() throws Exception {
		File testBagDir = this.createTestBag();
		File manifestFile = new File(testBagDir, ManifestHelper.getPayloadManifestFilename(Algorithm.MD5, this.constants));
		assertTrue(manifestFile.exists());
		FileUtils.forceDelete(manifestFile);
		assertFalse(manifestFile.exists());
		
		Bag bag = BagFactory.createBag(testBagDir, this.getVersion(), true);
		assertFalse(bag.checkComplete().isSuccess());
		assertFalse(bag.checkValid().isSuccess());
		
		assertTrue(bag.checkTagManifests().isSuccess());
		assertTrue(bag.checkPayloadManifests().isSuccess());

	}

	@Test
	public void testBagWithExtraDirectory() throws Exception {
		File testBagDir = this.createTestBag();
		File extraDir = new File(testBagDir, "extra");
		assertFalse(extraDir.exists());
		FileUtils.forceMkdir(extraDir);
		assertTrue(extraDir.exists());
		
		Bag bag = BagFactory.createBag(testBagDir, this.getVersion(), true);
		assertFalse(bag.checkComplete().isSuccess());
		assertFalse(bag.checkValid().isSuccess());
		
		assertTrue(bag.checkTagManifests().isSuccess());
		assertTrue(bag.checkPayloadManifests().isSuccess());

	}
	
	@Test
	@Ignore
	public void testBagWithSpecialCharacters() throws Exception {
		File testBagDir = this.createTestBag();
		File specialCharFile = new File(testBagDir, "data/testü.txt");
		FileWriter writer = new FileWriter(specialCharFile);
		writer.write("test1");
		writer.close();

		File sha1ManifestFile = new File(testBagDir, ManifestHelper.getPayloadManifestFilename(Algorithm.SHA1, this.constants));
		ManifestWriter manifestWriter = this.factory.createManifestWriter(new FileOutputStream(sha1ManifestFile));
		manifestWriter.write("data/testü.txt", "b444ac06613fc8d63795be9ad0beaf55011936ac");
		manifestWriter.close();

		Bag bag = BagFactory.createBag(testBagDir, this.getVersion(), true);
		assertTrue(bag.checkComplete().isSuccess());
		assertTrue(bag.checkValid().isSuccess());
		
		assertTrue(bag.checkTagManifests().isSuccess());
		assertTrue(bag.checkPayloadManifests().isSuccess());
		
	}
	
	@Test
	public void testWrongVersion() throws Exception {
		Version otherVersion = null;
		for(Version checkVersion : Version.values()) {
			if (! this.getVersion().equals(checkVersion)) {
				otherVersion = checkVersion;
			}
		}
		
		//May throw a RuntimeException if contains a \ in manifest
		try {
			File bagDir = this.getBagDir(otherVersion, Bag.Format.FILESYSTEM); 
			
			Bag bag = BagFactory.createBag(bagDir, this.getVersion(), true);
			assertFalse(bag.checkComplete().isSuccess());
			assertFalse(bag.checkValid().isSuccess());
		} catch (RuntimeException ex) {}

	}
		
	@Test
	public void testCreateBag() throws Exception {
		Bag bag = BagFactory.createBag(this.getVersion());
		bag.addFilesToPayload(ResourceHelper.getFile(MessageFormat.format("bags/{0}/bag/data/dir1", this.getVersion().toString().toLowerCase())));
		bag.addFilesToPayload(ResourceHelper.getFile(MessageFormat.format("bags/{0}/bag/data/dir2", this.getVersion().toString().toLowerCase())));
		bag.addFilesToPayload(ResourceHelper.getFile(MessageFormat.format("bags/{0}/bag/data/test1.txt", this.getVersion().toString().toLowerCase())));
		bag.addFilesToPayload(ResourceHelper.getFile(MessageFormat.format("bags/{0}/bag/data/test2.txt", this.getVersion().toString().toLowerCase())));

		BagInfoTxt bagInfo = bag.getBagPartFactory().createBagInfoTxt();
		bag.putBagFile(bagInfo);
		final String BAG_COUNT = "1 of 5";
		final String BAG_SIZE = "10 gb";
		final String BAGGING_DATE = "10.20.2008";
		bagInfo.setBagCount(BAG_COUNT);
		bagInfo.setBagSize(BAG_SIZE);
		bagInfo.setBaggingDate(BAGGING_DATE);
		
		assertEquals(5, bag.getPayload().size());
		assertNotNull(bag.getBagFile("data/dir1/test3.txt"));
		assertNotNull(bag.getBagFile("data/test1.txt"));
		assertNull(bag.getBagFile("xdata/dir1/test3.txt"));
		assertNotNull(bag.getBagInfoTxt());
		assertEquals(BAG_COUNT, bag.getBagInfoTxt().getBagCount());
		assertEquals(BAG_SIZE, bagInfo.getBagSize());
		assertEquals(BAGGING_DATE, bagInfo.getBaggingDate());
		this.addlTestCreateBag(bag);
				
	}
	
	public void addlTestCreateBag(Bag bag){};

	@Test
	public void testCancel() throws Exception {
		Bag bag = this.getBag(Format.FILESYSTEM);
		assertNull(bag.checkComplete(false, new DummyCancelIndicator(5)));
		assertNull(bag.checkValid(false, new DummyCancelIndicator(10)));
		assertNull(bag.checkTagManifests(new DummyCancelIndicator(3)));
		assertNull(bag.checkPayloadManifests(new DummyCancelIndicator(5)));
	}

}