package org.to2mbn.lolixl.plugin.impl.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.to2mbn.lolixl.plugin.impl.ReadToFileProcessor;
import org.to2mbn.lolixl.plugin.maven.ArtifactNotFoundException;
import org.to2mbn.lolixl.plugin.maven.ArtifactSnapshot;
import org.to2mbn.lolixl.plugin.maven.ArtifactVersioning;
import org.to2mbn.lolixl.plugin.maven.LocalMavenRepository;
import org.to2mbn.lolixl.plugin.maven.MavenArtifact;
import org.to2mbn.lolixl.plugin.maven.MavenRepository;
import org.to2mbn.lolixl.plugin.util.MavenUtils;
import org.to2mbn.lolixl.plugin.util.PathUtils;
import org.to2mbn.lolixl.utils.AsyncUtils;
import com.google.gson.Gson;
import static java.lang.String.format;

@Component
@Service({ LocalMavenRepository.class })
@Properties({
		@Property(name = "m2repository.type", value = "local")
})
public class LocalMavenRepositoryImpl implements LocalMavenRepository {

	private static final Logger LOGGER = Logger.getLogger(LocalMavenRepositoryImpl.class.getCanonicalName());

	@Reference(target = "(usage=local_io)")
	private ExecutorService localIOPool;

	@Reference
	private Gson gson;

	private Path m2dir = new File(".lolixl/m2/repo").toPath();

	@Override
	public CompletableFuture<Void> downloadArtifact(MavenArtifact artifact, String classifier, String type, Supplier<WritableByteChannel> output) {
		Objects.requireNonNull(artifact);
		Objects.requireNonNull(output);

		return asyncReadArtifact(getArtifactPath(artifact, classifier, type), output);
	}

	@Override
	public CompletableFuture<ArtifactVersioning> getVersioning(String groupId, String artifactId) {
		Objects.requireNonNull(groupId);
		Objects.requireNonNull(artifactId);

		return asyncReadMetadataJson(ArtifactVersioning.class, getVersioningMetadataPath(groupId, artifactId));
	}

	@Override
	public CompletableFuture<ArtifactSnapshot> getSnapshot(MavenArtifact artifact) {
		Objects.requireNonNull(artifact);

		return asyncReadMetadataJson(ArtifactSnapshot.class, getSnapshotMetadataPath(artifact));
	}

	@Override
	public Path getArtifactPath(MavenArtifact artifact, String classifier, String type) {
		Objects.requireNonNull(artifact);

		return getVersionDir(artifact).resolve(MavenUtils.getArtifactFileName(artifact, classifier, type));
	}

	@Override
	public CompletableFuture<Void> deleteArtifact(MavenArtifact artifact) {
		Objects.requireNonNull(artifact);

		return AsyncUtils.asyncRun(() -> {
			PathUtils.deleteRecursively(getVersionDir(artifact));
			return null;
		}, localIOPool);
	}

	@Override
	public CompletableFuture<Void> deleteArtifactAllVersions(String groupId, String artifactId) {
		Objects.requireNonNull(groupId);
		Objects.requireNonNull(artifactId);

		return AsyncUtils.asyncRun(() -> {
			PathUtils.deleteRecursively(getArtifactDir(groupId, artifactId));
			return null;
		}, localIOPool);
	}

	@Override
	public CompletableFuture<Void> install(MavenRepository from, MavenArtifact artifact, String classifier, String type) {
		Objects.requireNonNull(from);
		Objects.requireNonNull(artifact);

		return CompletableFuture.allOf(
				updateVersioning(from, artifact.getGroupId(), artifact.getArtifactId()),
				updateSnapshot(from, artifact).handle((result, ex) -> {
					if (ex != null && !AsyncUtils.exceptionInstanceof(ArtifactNotFoundException.class, ex))
						LOGGER.log(Level.WARNING, ex, () -> format("Couldn't download snapshot metadata %s from %s, skipping", artifact, from));
					return null;
				}),
				processDownloading(getArtifactPath(artifact, classifier, type), output -> from.downloadArtifact(artifact, classifier, type, output)));
	}

	private Path getArtifactDir(String groupId, String artifactId) {
		Path p = m2dir;
		for (String gid : groupId.split("\\."))
			p = p.resolve(gid);
		return p.resolve(artifactId);
	}

	private Path getVersionDir(MavenArtifact artifact) {
		return getArtifactDir(artifact.getGroupId(), artifact.getArtifactId())
				.resolve(artifact.getVersion());
	}

	private CompletableFuture<Void> asyncReadArtifact(Path path, Supplier<WritableByteChannel> output) {
		return AsyncUtils.asyncRun(() -> {
			checkArtifactExisting(path);

			try (FileChannel in = FileChannel.open(path, StandardOpenOption.READ);
					WritableByteChannel out = output.get();) {
				in.transferTo(0, in.size(), out);
			}

			return null;
		}, localIOPool);
	}

	private <T> CompletableFuture<T> asyncReadMetadataJson(Class<T> clazz, Path path) {
		return AsyncUtils.asyncRun(() -> {
			checkArtifactExisting(path);

			try (Reader reader = new InputStreamReader(Files.newInputStream(path), "UTF-8")) {
				return gson.fromJson(reader, clazz);
			}
		}, localIOPool);
	}

	private void checkArtifactExisting(Path path) throws ArtifactNotFoundException {
		if (!Files.exists(path)) {
			throw new ArtifactNotFoundException("Artifact file not found: " + path);
		}
	}

	private Path getVersioningMetadataPath(String groupId, String artifactId) {
		return getArtifactDir(groupId, artifactId).resolve("maven-metadata.json");
	}

	private Path getSnapshotMetadataPath(MavenArtifact artifact) {
		return getVersionDir(artifact).resolve("maven-metadata.json");
	}

	private CompletableFuture<Void> processDownloading(Path to, Function<Supplier<WritableByteChannel>, CompletableFuture<Void>> operation) {
		return new ReadToFileProcessor(to, operation).invoke();
	}

	private CompletableFuture<ArtifactVersioning> updateVersioning(MavenRepository from, String groupId, String artifactId) {
		return from.getVersioning(groupId, artifactId)
				.thenApplyAsync(versioning -> {
					writeMetadataJson(getVersioningMetadataPath(groupId, artifactId), versioning);
					return versioning;
				}, localIOPool);
	}

	private CompletableFuture<ArtifactSnapshot> updateSnapshot(MavenRepository from, MavenArtifact artifact) {
		return from.getSnapshot(artifact)
				.thenApplyAsync(snapshot -> {
					writeMetadataJson(getSnapshotMetadataPath(artifact), snapshot);
					return snapshot;
				});
	}

	private void writeMetadataJson(Path path, Object metadata) throws UncheckedIOException {
		try {
			PathUtils.tryMkdirsParent(path);
			try (Writer writer = new OutputStreamWriter(Files.newOutputStream(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE), "UTF-8")) {
				gson.toJson(metadata, writer);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
