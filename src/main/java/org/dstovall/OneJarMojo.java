package org.dstovall;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.FileSet;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.FileUtils;

import br.bbts.segu.HashingUtil;

/**
 * Creates an executable one-jar version of the project's normal jar, including
 * all dependencies.
 *
 * @goal one-jar
 * @phase package
 * @requiresProject
 * @requiresDependencyResolution runtime
 */
public class OneJarMojo extends AbstractMojo {

	/**
	 * All the dependencies including trancient dependencies.
	 *
	 * @parameter default-value="${project.artifacts}"
	 * @required
	 * @readonly
	 */
	private Collection<Artifact> artifacts;

	/**
	 * All declared dependencies in this project, including system scoped
	 * dependencies.
	 *
	 * @parameter default-value="${project.dependencies}"
	 * @required
	 * @readonly
	 */
	private Collection<Dependency> dependencies;
	
	/**
	 * FileSet to be included in the "binlib" directory inside the one-jar. This is
	 * the place to include native libraries such as .dll files and .so files. They
	 * will automatically be loaded by the one-jar.
	 * 
	 * @parameter
	 */
	private FileSet[] binlibs;
	
	/**
	 * FileSet to be included in the "other" directory inside the one-jar.
	 * 
	 * @parameter
	 */
	private FileSet[] other;

	/**
	 * The directory for the resulting file.
	 *
	 * @parameter expression="${project.build.directory}"
	 * @required
	 * @readonly
	 */
	private File outputDirectory;

	/**
	 * Name of the main JAR.
	 *
	 * @parameter expression="${project.build.finalName}.jar"
	 * @readonly
	 * @required
	 */
	private String mainJarFilename;

	/**
	 * Implementation Version of the jar. Defaults to the build's version.
	 *
	 * @parameter expression="${project.version}"
	 * @required
	 */
	private String implementationVersion;

	/**
	 * Name of the generated JAR.
	 *
	 * @parameter expression="${project.build.finalName}.one-jar.jar"
	 * @required
	 */
	private String filename;

	/**
	 * The version of one-jar to use. Has a default, so typically no need to specify
	 * this.
	 *
	 * @parameter expression="${onejar-version}" default-value="0.97"
	 */
	private String onejarVersion;

	/**
	 * Whether to attach the generated one-jar to the build. You may also wish to
	 * set <code>classifier</code>.
	 *
	 * @parameter default-value=false
	 */
	private boolean attachToBuild;

	/**
	 * Classifier to use, if the one-jar is to be attached to the build. Set
	 * <code>&lt;attachToBuild&gt;true&lt;/attachToBuild&gt; if you want that.
	 *
	 * @parameter default-value="onejar"
	 */
	private String classifier;
	
	/**
	 * Whether to mark optional dependencies in the manifest
	 *
	 * @parameter default-value=false
	 */
	private boolean markOptional;

	/**
	 * Whether to include system dependencies
	 *
	 * @parameter default-value=true
	 */
	private boolean includeSystem;
	
	/**
	 * Whether to include native libs
	 *
	 * @parameter default-value=false
	 */
	private boolean includeNative;
	
	/**
	 * Whether to generate md5 hash for libs content folder, for validate update requirement
	 *
	 * @parameter default-value=false
	 */
	private boolean generateLibsHash;
	
	/**
	 * This Maven project.
	 *
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	private MavenProject project;

	/**
	 * For attaching artifacts etc.
	 *
	 * @component
	 * @readonly
	 */
	private MavenProjectHelper projectHelper;

	/**
	 * The main class that one-jar should activate
	 *
	 * @parameter expression="${onejar-mainclass}"
	 */
	private String mainClass;
	
	public void execute() throws MojoExecutionException {

		JarOutputStream out = null;
		JarInputStream template = null;
		
		File oneJarFile;
		File mainJarFile;
		try {
			
			// Create the target files
			oneJarFile = new File(outputDirectory, filename);
			mainJarFile = new File(outputDirectory, mainJarFilename);
			
			if (filename.equals(mainJarFilename)) {
				// Create the target file
				File original = new File(outputDirectory, mainJarFilename);
				mainJarFile = File.createTempFile(mainJarFilename, null);
				mainJarFile.deleteOnExit();
				
				FileUtils.copyFile(original, mainJarFile);
			}
			
			// Open a stream to write to the target file
			out = new JarOutputStream(new FileOutputStream(oneJarFile, false), getManifest());
			
			// Main jar
			if (getLog().isDebugEnabled()) {
				getLog().debug("Adding main jar main/[" + mainJarFilename + "]");
			}
			addToZip(mainJarFile, "main/", mainJarFilename, out);

			// All dependencies, including transient dependencies, but excluding system
			// scope dependencies
			List<File> dependencyJars = extractDependencyFiles(artifacts);
			if (getLog().isDebugEnabled()) {
				getLog().debug("Adding [" + dependencyJars.size() + "] dependency libraries...");
			}
			for (File jar : dependencyJars) {
				addToZip(jar, "lib/", out);
			}

			// System scope dependencies
			if (includeSystem) {
				List<File> systemDependencyJars = extractSystemDependencyFiles(dependencies);
				if (getLog().isDebugEnabled()) {
					getLog().debug("Adding [" + systemDependencyJars.size() + "] system dependency libraries...");
				}
				for (File jar : systemDependencyJars) {
					addToZip(jar, "lib/", out);
				}
			}
			
			// Other files
			if (other != null) {
				for (FileSet eachFileSet : other) {
					List<File> includedFiles = toFileList(eachFileSet);
					if (getLog().isDebugEnabled()) {
						getLog().debug("Adding [" + includedFiles.size() + "] other files...");
					}
					for (File eachIncludedFile : includedFiles) {
						String relativePath = eachIncludedFile.getAbsolutePath().replace(Paths.get(eachFileSet.getDirectory()).toString(), "");
						addToZip(eachIncludedFile, "other", relativePath.replaceAll("\\\\", "/"), out);
					}
				}
			}

			// Native libraries
			if (includeNative && binlibs != null) {
				for (FileSet eachFileSet : binlibs) {
					List<File> includedFiles = toFileList(eachFileSet);
					if (getLog().isDebugEnabled()) {
						getLog().debug("Adding [" + includedFiles.size() + "] native libraries...");
					}
					for (File eachIncludedFile : includedFiles) {
						String relativePath = eachIncludedFile.getAbsolutePath().replace(Paths.get(eachFileSet.getDirectory()).toString(), "");
						addToZip(eachIncludedFile, "binlib", relativePath.replaceAll("\\\\", "/"), out);
					}
				}
			}
			
			// One-jar stuff
			getLog().debug("Adding one-jar components...");
			template = openOnejarTemplateArchive();
			ZipEntry entry;
			while ((entry = template.getNextEntry()) != null) {
				// Skip the manifest file, no need to clutter...
				if (!"boot-manifest.mf".equals(entry.getName())) {
					addToZip(out, entry, template);
				}
			}
		} catch (IOException e) {
			getLog().error(e);
			throw new MojoExecutionException("One-jar Mojo failed.", e);
		} finally {
			IOUtils.closeQuietly(out);
			IOUtils.closeQuietly(template);
		}

		// Attach the created one-jar to the build.
		if (attachToBuild) {
			projectHelper.attachArtifact(project, "jar", classifier, oneJarFile);
		}
	}

	// ----- One-Jar Template
	// ------------------------------------------------------------------------------------------

	private String getOnejarArchiveName() {
		return "one-jar-boot-" + onejarVersion + ".jar";
	}

	private JarInputStream openOnejarTemplateArchive() throws IOException {
		return new JarInputStream(getClass().getClassLoader().getResourceAsStream(getOnejarArchiveName()));
	}
	
	private Manifest getManifest() throws IOException {
		// Copy the template's boot-manifest.mf file
		ZipInputStream zipIS = openOnejarTemplateArchive();
		Manifest manifest = new Manifest(getFileBytes(zipIS, "boot-manifest.mf"));
		IOUtils.closeQuietly(zipIS);

		// If the client has specified a mainClass argument, add the proper entry to the manifest
		if (mainClass != null) {
			manifest.getMainAttributes().putValue("One-Jar-Main-Class", mainClass);
		}
		
		if (!includeSystem) {
			StringBuilder classPath = new StringBuilder();
			for (File dependency : extractSystemDependencyFiles(dependencies)) {
				classPath.append("file:///" + dependency.getAbsolutePath().replaceAll(" ", "%20") + " ");
			}
			
			if (classPath.length() > 0) {
				manifest.getMainAttributes().putValue("Class-Path", classPath.toString());
			}
		}
		
		if (markOptional) {
			StringBuilder optional = new StringBuilder();
			for (Dependency dependency : dependencies) {
				if (dependency.isOptional()) {
					optional.append(dependency.getArtifactId() + "-" + dependency.getVersion() + ".jar ");
				}
			}
			
			if (optional.length() > 0) {
				manifest.getMainAttributes().putValue("Optional-Libs", optional.toString());
			}
		}
		
		if (generateLibsHash && binlibs != null) {
			for (FileSet eachFileSet : binlibs) {
				List<File> includedFiles = toFileList(eachFileSet);
				if (getLog().isDebugEnabled()) {
					getLog().debug("Calculating hash [" + includedFiles.size() + "] native libraries...");
				}
				String hash = HashingUtil.hashFiles(includedFiles, true);
				manifest.getMainAttributes().putValue("Native-Libs-Hash", hash);
			}
		}
		
		// If the client has specified an implementationVersion argument, add it also
		// (It's required and defaulted, so this always executes...)
		//
		// TODO: The format of this manifest entry is not "hard and fast". Some specs
		// call for "implementationVersion",
		// some for "implemenation-version", and others use various capitalizations of
		// these two. It's likely that a
		// better solution then this "brute-force" bit here is to allow clients to
		// configure these entries from the
		// Maven POM.
		if (implementationVersion != null) {
			manifest.getMainAttributes().putValue("ImplementationVersion", implementationVersion);
		}

		return manifest;
	}

	// ----- Zip-file manipulations
	// ------------------------------------------------------------------------------------
	
	private void addToZip(File sourceFile, String zipfilePath, String relativePath, JarOutputStream out) throws IOException {
		addToZip(out, new ZipEntry(zipfilePath + relativePath), new FileInputStream(sourceFile));
	}

	private void addToZip(File sourceFile, String zipfilePath, JarOutputStream out) throws IOException {
		addToZip(out, new ZipEntry(zipfilePath + sourceFile.getName()), new FileInputStream(sourceFile));
	}

	private final AtomicInteger alternativeEntryCounter = new AtomicInteger(0);

	private void addToZip(JarOutputStream out, ZipEntry entry, InputStream in) throws IOException {
		try {
			out.putNextEntry(entry);
			IOUtils.copy(in, out);
			out.closeEntry();
		} catch (ZipException e) {
			if (e.getMessage().startsWith("duplicate entry")) {
				// A Jar with the same name was already added. Let's add this one using a
				// modified name:
				final ZipEntry alternativeEntry = new ZipEntry(entry.getName() + "-DUPLICATE-FILENAME-" + alternativeEntryCounter.incrementAndGet() + ".jar");
				addToZip(out, alternativeEntry, in);
			} else {
				throw e;
			}
		}
	}

	private InputStream getFileBytes(ZipInputStream is, String name) throws IOException {
		ZipEntry entry = null;
		while ((entry = is.getNextEntry()) != null) {
			if (entry.getName().equals(name)) {
				byte[] data = IOUtils.toByteArray(is);
				return new ByteArrayInputStream(data);
			}
		}
		return null;
	}

	/**
	 * Returns a {@link File} object for each artifact.
	 *
	 * @param artifacts Pre-resolved artifacts
	 * @return <code>File</code> objects for each artifact.
	 */
	private List<File> extractDependencyFiles(Collection<Artifact> artifacts) {
		List<File> files = new ArrayList<File>();

		if (artifacts == null) {
			return files;
		}

		for (Artifact artifact : artifacts) {
			File file = artifact.getFile();

			if (file.isFile()) {
				files.add(file);
			}
		}
		return files;
	}

	/**
	 * Returns a {@link File} object for each system dependency.
	 * 
	 * @param dependencies a collection of dependencies
	 * @return <code>File</code> objects for each system dependency in the supplied
	 *         dependencies.
	 */
	private List<File> extractSystemDependencyFiles(Collection<Dependency> dependencies) {
		final ArrayList<File> files = new ArrayList<File>();

		if (dependencies == null) {
			return files;
		}

		for (Dependency systemDependency : dependencies) {
			if (systemDependency != null && "system".equals(systemDependency.getScope())) {
				files.add(new File(systemDependency.getSystemPath()));
			}
		}
		return files;
	}
	
	@SuppressWarnings("unchecked")
	private static List<File> toFileList(FileSet fileSet) throws IOException {
		File directory = new File(fileSet.getDirectory());
		String includes = toString(fileSet.getIncludes());
		String excludes = toString(fileSet.getExcludes());
		return FileUtils.getFiles(directory, includes, excludes);
	}

	private static String toString(List<String> strings) {
		StringBuilder sb = new StringBuilder();
		for (String string : strings) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(string);
		}
		return sb.toString();
	}
}