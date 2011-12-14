package gov.loc.repository.bagit.impl;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


import gov.loc.repository.bagit.Bag;
import gov.loc.repository.bagit.BagFactory;
import gov.loc.repository.bagit.BagFile;
import gov.loc.repository.bagit.BagHelper;
import gov.loc.repository.bagit.BagInfoTxt;
import gov.loc.repository.bagit.BagItTxt;
import gov.loc.repository.bagit.BagVisitor;
import gov.loc.repository.bagit.DeclareCloseable;
import gov.loc.repository.bagit.FetchTxt;
import gov.loc.repository.bagit.ManifestHelper;
import gov.loc.repository.bagit.Manifest;
import gov.loc.repository.bagit.BagFactory.Version;
import gov.loc.repository.bagit.Manifest.Algorithm;
import gov.loc.repository.bagit.filesystem.DirNode;
import gov.loc.repository.bagit.filesystem.FileNode;
import gov.loc.repository.bagit.filesystem.FileSystemFactory;
import gov.loc.repository.bagit.filesystem.FileSystemFactory.UnsupportedFormatException;
import gov.loc.repository.bagit.filesystem.FileSystemNode;
import gov.loc.repository.bagit.filesystem.filter.FileNodeFileSystemNodeFilter;
import gov.loc.repository.bagit.filesystem.filter.IgnoringFileSystemNodeFilter;
import gov.loc.repository.bagit.transformer.Completer;
import gov.loc.repository.bagit.transformer.HolePuncher;
import gov.loc.repository.bagit.transformer.impl.DefaultCompleter;
import gov.loc.repository.bagit.transformer.impl.HolePuncherImpl;
import gov.loc.repository.bagit.utilities.BagVerifyResult;
import gov.loc.repository.bagit.utilities.CancelUtil;
import gov.loc.repository.bagit.utilities.FilenameHelper;
import gov.loc.repository.bagit.utilities.FormatHelper;
import gov.loc.repository.bagit.utilities.FormatHelper.UnknownFormatException;
import gov.loc.repository.bagit.utilities.SimpleResult;
import gov.loc.repository.bagit.verify.ManifestChecksumVerifier;
import gov.loc.repository.bagit.verify.ValidVerifier;
import gov.loc.repository.bagit.verify.Verifier;
import gov.loc.repository.bagit.verify.impl.CompleteVerifierImpl;
import gov.loc.repository.bagit.verify.impl.ParallelManifestChecksumVerifier;
import gov.loc.repository.bagit.verify.impl.ValidVerifierImpl;
import gov.loc.repository.bagit.writer.Writer;

public abstract class AbstractBag implements Bag {
		
	private static final Log log = LogFactory.getLog(AbstractBag.class);
	
	private Map<String, BagFile> tagMap = new HashMap<String, BagFile>();
	private Map<String, BagFile> payloadMap = new HashMap<String, BagFile>();
	private File fileForBag = null;
	private BagPartFactory bagPartFactory = null;
	private BagConstants bagConstants = null;
	private BagFactory bagFactory = null;
	private Set<Closeable> closeables = new HashSet<Closeable>();
	
	/**
	 * Constructor for a new bag.
	 * Payload should be added to the bag by calling addPayload().
	 */	
	public AbstractBag(BagPartFactory bagPartFactory, BagConstants bagConstants, BagFactory bagFactory) {
		this.bagPartFactory = bagPartFactory;
		this.bagConstants = bagConstants;
		this.bagFactory = bagFactory;
		log.debug(MessageFormat.format("Creating new bag. Version is {0}.", this.getBagConstants().getVersion().toString()));
	}
	
	@Override
	public File getFile() {
		return this.fileForBag;
	}
	
	@Override
	public void setFile(File file) {
		this.fileForBag = file;
		
	}
	
	@Override
	public Version getVersion() {
		return this.getBagConstants().getVersion();
	}
	
	@Override
	public void loadFromPayloadManifests() {
		log.debug(MessageFormat.format("Loading from {0} using payload manifests", this.fileForBag));
		this.tagMap.clear();
		this.payloadMap.clear();
		
		DirNode bagFileDirNode;
		try {
			bagFileDirNode = FileSystemFactory.getDirNodeForBag(this.fileForBag);
		} catch (UnknownFormatException e) {
			throw new RuntimeException(e);
		} catch (UnsupportedFormatException e) {
			throw new RuntimeException(e);
		}
		log.trace(MessageFormat.format("BagFileDirNode has filepath {0} and is a {1}", bagFileDirNode.getFilepath(), bagFileDirNode.getClass().getSimpleName()));
		
		//Load tag map
		for(FileSystemNode node : bagFileDirNode.listChildren()) {
			if (node instanceof FileNode) {
				FileNode tagFileNode = (FileNode)node;
				String filepath = FilenameHelper.removeBasePath(bagFileDirNode.getFilepath(), tagFileNode.getFilepath());
				log.trace(MessageFormat.format("Loading tag {0} using filepath {1}", tagFileNode.getFilepath(), filepath));
				BagFile bagFile = new FileSystemBagFile(filepath, tagFileNode);
				this.putBagFile(bagFile);
			}
		}
		//Find manifests to load payload map
		List<Manifest> payloadManifests = this.getPayloadManifests();
		for(Manifest manifest : payloadManifests) {
			for(String filepath : manifest.keySet()) {
				String fullFilepath = FilenameHelper.concatFilepath(bagFileDirNode.getFilepath(), filepath);
				FileNode payloadFileNode = bagFileDirNode.getFileSystem().resolve(fullFilepath);
				BagFile bagFile = new FileSystemBagFile(filepath, payloadFileNode);
				log.trace(MessageFormat.format("Loading payload {0} using filepath {1}", payloadFileNode.getFilepath(), filepath));
				this.putBagFile(bagFile);
			}
		}
	}

	@Override
	public void loadFromPayloadFiles() {
		this.loadFromPayloadFiles(new ArrayList<String>());
	}
	
	@Override
	public void loadFromPayloadFiles(List<String> ignoreAdditionalDirectories) {
		log.debug(MessageFormat.format("Loading from {0} using payload files", this.fileForBag));

		this.tagMap.clear();
		this.payloadMap.clear();
		
		DirNode bagFileDirNode;
		try {
			bagFileDirNode = FileSystemFactory.getDirNodeForBag(this.fileForBag);
		} catch (UnknownFormatException e) {
			throw new RuntimeException(e);
		} catch (UnsupportedFormatException e) {
			throw new RuntimeException(e);
		}
		log.trace(MessageFormat.format("BagFileDirNode has filepath {0} and is a {1}", bagFileDirNode.getFilepath(), bagFileDirNode.getClass().getSimpleName()));
		
		IgnoringFileSystemNodeFilter descentFilter = new IgnoringFileSystemNodeFilter(ignoreAdditionalDirectories, false);
		descentFilter.setRelativeFilepath(bagFileDirNode.getFilepath());
		Collection<FileSystemNode> nodes = bagFileDirNode.listDescendants(new FileNodeFileSystemNodeFilter(), descentFilter);
		log.trace(MessageFormat.format("{0} files found", nodes.size()));
		
		for(FileSystemNode node : nodes) {
			log.trace("Reading " + node.getFilepath());
			String filepath = FilenameHelper.removeBasePath(bagFileDirNode.getFilepath(), node.getFilepath());
			BagFile bagFile = new FileSystemBagFile(filepath, (FileNode)node);
			log.trace(MessageFormat.format("Loading {0} using filepath {1}", node.getFilepath(), filepath));
			this.putBagFile(bagFile);			
		}			
	}

	
	@Override
	public List<Manifest> getPayloadManifests() {
		List<Manifest> manifests = new ArrayList<Manifest>();
		for(BagFile bagFile : this.tagMap.values()) {
			if (bagFile instanceof Manifest) {
				Manifest manifest = (Manifest)bagFile;
				if (manifest.isPayloadManifest()) {
					manifests.add(manifest);
				}
			}
			
		}
		return manifests;			
	}

	@Override
	public List<Manifest> getTagManifests() {
		log.debug("Getting tag manifests");
		List<Manifest> manifests = new ArrayList<Manifest>();
		for(BagFile bagFile : this.tagMap.values()) {
			log.trace(MessageFormat.format("Checking if {0} is a tag manifest", bagFile.getFilepath()));
			if (bagFile instanceof Manifest) {
				log.trace(MessageFormat.format("{0} is a manifest", bagFile.getFilepath()));
				Manifest manifest = (Manifest)bagFile;
				if (manifest.isTagManifest()) {
					log.trace(MessageFormat.format("{0} is a tag manifest", bagFile.getFilepath()));
					manifests.add(manifest);
				}
			}
			
		}
		return manifests;			
	}
	
	
	@Override
	public void putBagFile(BagFile bagFile) {
		if (bagFile instanceof DeclareCloseable) {
			this.closeables.add(((DeclareCloseable)bagFile).declareCloseable());
		}
		
		if (BagHelper.isPayload(bagFile.getFilepath(), this.getBagConstants())) {
			this.payloadMap.put(bagFile.getFilepath(), bagFile);
		} else {
			//Is a Manifest
			if ((! (bagFile instanceof Manifest)) && (ManifestHelper.isPayloadManifest(bagFile.getFilepath(), this.getBagConstants()) || ManifestHelper.isTagManifest(bagFile.getFilepath(), this.getBagConstants()))) {
				tagMap.put(bagFile.getFilepath(), this.getBagPartFactory().createManifest(bagFile.getFilepath(), bagFile));
			}
			//Is a BagItTxt
			else if ((! (bagFile instanceof BagItTxt)) && bagFile.getFilepath().equals(this.getBagConstants().getBagItTxt())) {
				tagMap.put(bagFile.getFilepath(), this.getBagPartFactory().createBagItTxt(bagFile));
			}
			//Is a BagInfoTxt
			else if ((! (bagFile instanceof BagInfoTxt)) && bagFile.getFilepath().equals(this.getBagConstants().getBagInfoTxt())) {
				tagMap.put(bagFile.getFilepath(), this.getBagPartFactory().createBagInfoTxt(bagFile));
			}
			//Is a FetchTxt
			else if ((! (bagFile instanceof FetchTxt)) && bagFile.getFilepath().equals(this.getBagConstants().getFetchTxt())) {
				tagMap.put(bagFile.getFilepath(), this.getBagPartFactory().createFetchTxt(bagFile));
			}
			else {
				tagMap.put(bagFile.getFilepath(), bagFile);	
			}				

		}
				
	}
	
	@Override
	public void putBagFiles(Collection<BagFile> bagFiles) {
		for(BagFile bagFile : bagFiles) {
			this.putBagFile(bagFile);
		}
		
	}
		
	@Override
	public void removeBagFile(String filepath) {
		if (BagHelper.isPayload(filepath, this.getBagConstants())) {
			if (! this.payloadMap.containsKey(filepath)) {
				throw new RuntimeException(MessageFormat.format("Payload file {0} not contained in bag.", filepath));			
			}
			this.payloadMap.remove(filepath);					
		} else {
			if (! this.tagMap.containsKey(filepath)) {
				throw new RuntimeException(MessageFormat.format("Tag file {0} not contained in bag.", filepath));			
			}
			this.tagMap.remove(filepath);								
		}
	}
	
	
	@Override
	public void addFileToPayload(File file) {
		new AddFilesToPayloadOperation(this).addFileToPayload(file);
	}
	
	@Override
	public void addFilesToPayload(List<File> files) {
		new AddFilesToPayloadOperation(this).addFilesToPayload(files);
	}
	
	
	@Override
	public Collection<BagFile> getPayload() {
		return this.payloadMap.values();
	}
	
	@Override
	public Collection<BagFile> getTags() {
		return this.tagMap.values();
	}
	
	@Override
	public BagFile getBagFile(String filepath) {
		if (BagHelper.isPayload(filepath, this.getBagConstants())) {
			return this.payloadMap.get(filepath);
		} else {
			return this.tagMap.get(filepath);
		}
	}
	
	@Override
	public void addFileAsTag(File file) {
		if (! file.exists()) {
			throw new RuntimeException(MessageFormat.format("{0} does not exist.", file));
		}
		if (! file.canRead()) {
			throw new RuntimeException(MessageFormat.format("Can't read {0}.", file));
		}
		
		String filepath = file.getName();
		log.debug(MessageFormat.format("Adding {0} to payload.", filepath));
		this.putBagFile(new FileBagFile(filepath, file));
	}
		
	@Override
	public BagItTxt getBagItTxt() {
		return (BagItTxt)this.getBagFile(this.getBagConstants().getBagItTxt());
	}

	@Override
	public SimpleResult verifyComplete() {
		return this.verify(new CompleteVerifierImpl());
	}
	
	@Override
	public SimpleResult verifyTagManifests() {		
		ManifestChecksumVerifier verifier = new ParallelManifestChecksumVerifier();
		return verifier.verify(this.getTagManifests(), this);
	}
	
	@Override
	public SimpleResult verifyPayloadManifests() {
		ManifestChecksumVerifier verifier = new ParallelManifestChecksumVerifier();
		return verifier.verify(this.getPayloadManifests(), this);
	}
	
	@Override
	public SimpleResult verifyValid() {
		ValidVerifier verifier = new ValidVerifierImpl(new CompleteVerifierImpl(), new ParallelManifestChecksumVerifier());
		return this.verify(verifier);
	}
	
	@Override
	public BagVerifyResult verifyValidFailSlow() {
		ValidVerifierImpl verifier = new ValidVerifierImpl(new CompleteVerifierImpl(), new ParallelManifestChecksumVerifier());
		return verifier.verifyFailSlow(this);
	}
		
	@Override
	public void accept(BagVisitor visitor) {
		if (CancelUtil.isCancelled(visitor)) return;
		
		visitor.startBag(this);

		if (CancelUtil.isCancelled(visitor)) return;

		visitor.startTags();
		
		if (CancelUtil.isCancelled(visitor)) return;
		
		for(String filepath : this.tagMap.keySet()) {
			if (CancelUtil.isCancelled(visitor)) return;
			visitor.visitTag(this.tagMap.get(filepath));
		}
		
		if (CancelUtil.isCancelled(visitor)) return;

		visitor.endTags();

		if (CancelUtil.isCancelled(visitor)) return;
		
		visitor.startPayload();
		
		if (CancelUtil.isCancelled(visitor)) return;
		
		for(String filepath : this.payloadMap.keySet()) {
			if (CancelUtil.isCancelled(visitor)) return;
			visitor.visitPayload(this.payloadMap.get(filepath));
		}
		
		if (CancelUtil.isCancelled(visitor)) return;

		visitor.endPayload();
	
		if (CancelUtil.isCancelled(visitor)) return;
		
		visitor.endBag();
	}
			
	@Override
	public FetchTxt getFetchTxt() {
		return (FetchTxt)this.getBagFile(this.getBagConstants().getFetchTxt());
	}
	
	@Override
	public Format getFormat() {
		if (this.fileForBag == null) {
			return null;
		}
		try {
			return FormatHelper.getFormat(this.fileForBag);
		} catch (UnknownFormatException e) {
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public BagInfoTxt getBagInfoTxt() {
		return (BagInfoTxt)this.getBagFile(this.getBagConstants().getBagInfoTxt());
	}
			
	@Override
	public SimpleResult verify(Verifier verifier) {
		return verifier.verify(this);
	}
			
	@Override
	public BagConstants getBagConstants() {
		return this.bagConstants;
	}
	
	@Override
	public BagPartFactory getBagPartFactory() {
		return this.bagPartFactory;
	}
		
	@Override
	public Bag write(Writer writer, File file) {
		return writer.write(this, file);
	}
		
	@Override
	public void removePayloadDirectory(String filepath) {
		if (! filepath.endsWith("/")) {
			filepath += "/";
		}
		if (! filepath.startsWith(bagConstants.getDataDirectory())) {
			filepath = bagConstants.getDataDirectory() + "/" + filepath;
		}
		
		if ((bagConstants.getDataDirectory() + "/").equals(filepath)) {
			return;
		}

		log.debug("Removing payload directory " + filepath);
		
		List<String> deleteFilepaths = new ArrayList<String>();
		
		for(BagFile bagFile : this.getPayload()) {
			if (bagFile.getFilepath().startsWith(filepath)) {
				deleteFilepaths.add(bagFile.getFilepath());
			}
		}
		
		for(String deleteFilepath : deleteFilepaths) {
			log.debug("Removing " + deleteFilepath);
			this.removeBagFile(deleteFilepath);			
		}
	}
	
	@Override
	public Map<Algorithm, String> getChecksums(String filepath) {
		Map<Algorithm, String> checksumMap = new HashMap<Algorithm, String>();
		if (BagHelper.isPayload(filepath, this.bagConstants)) {
			for(Manifest manifest : this.getPayloadManifests()) {
				if (manifest.containsKey(filepath)) {
					checksumMap.put(manifest.getAlgorithm(), manifest.get(filepath));
				}
			}
		} else {
			for(Manifest manifest : this.getTagManifests()) {
				if (manifest.containsKey(filepath)) {
					checksumMap.put(manifest.getAlgorithm(), manifest.get(filepath));
				}
			}
			
		}		
		return checksumMap;
	}
	
	@Override
	public Manifest getPayloadManifest(Algorithm algorithm) {
		return (Manifest)this.getBagFile(ManifestHelper.getPayloadManifestFilename(algorithm, this.bagConstants));
	}
	
	@Override
	public Manifest getTagManifest(Algorithm algorithm) {
		return (Manifest)this.getBagFile(ManifestHelper.getTagManifestFilename(algorithm, this.bagConstants));
	}
	
	@Override
	public Bag makeComplete(Completer completer) {
		return completer.complete(this);
	}
	
	@Override
	public Bag makeComplete() {
		Completer completer = new DefaultCompleter(this.bagFactory);
		return completer.complete(this);
	}
	
	@Override
	public Bag makeHoley(HolePuncher holePuncher, String baseUrl, boolean includePayloadDirectoryInUrl, boolean includeTags, boolean resume) {
		return holePuncher.makeHoley(this, baseUrl, includePayloadDirectoryInUrl, includeTags, resume);
	}
	
	@Override
	public Bag makeHoley(String baseUrl, boolean includePayloadDirectoryInUrl, boolean includeTags, boolean resume) {
		HolePuncher holePuncher = new HolePuncherImpl(this.bagFactory);
		return holePuncher.makeHoley(this, baseUrl, includePayloadDirectoryInUrl, includeTags, resume);
	}
	
	@Override
	public void close() {
		for(Closeable closeable : this.closeables) {
			try {
				closeable.close();
			} catch (IOException e) {
				//Ignore the closing
				log.warn("Error closing", e);
			}
		}
		
	}
}