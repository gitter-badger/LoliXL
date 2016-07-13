package org.to2mbn.lolixl.plugin.impl;

import static java.lang.String.format;
import static java.util.stream.Collectors.*;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.service.component.ComponentContext;
import org.to2mbn.lolixl.plugin.LocalPluginRepository;
import org.to2mbn.lolixl.plugin.Plugin;
import org.to2mbn.lolixl.plugin.PluginDescription;
import org.to2mbn.lolixl.plugin.PluginRepository;
import org.to2mbn.lolixl.plugin.PluginService;
import org.to2mbn.lolixl.plugin.gpg.GPGVerifier;
import org.to2mbn.lolixl.plugin.impl.resolver.DependencyAction;
import org.to2mbn.lolixl.plugin.impl.resolver.DependencyAction.InstallAction;
import org.to2mbn.lolixl.plugin.impl.resolver.DependencyAction.UninstallAction;
import org.to2mbn.lolixl.plugin.impl.resolver.DependencyAction.UpdateAction;
import org.to2mbn.lolixl.plugin.impl.resolver.DependencyResolver;
import org.to2mbn.lolixl.plugin.maven.ArtifactNotFoundException;
import org.to2mbn.lolixl.plugin.maven.LocalMavenRepository;
import org.to2mbn.lolixl.plugin.maven.MavenArtifact;
import org.to2mbn.lolixl.utils.AsyncUtils;

@Service({ PluginService.class })
@Component(immediate = true)
public class PluginServiceImpl implements PluginService {

	private static final Logger LOGGER = Logger.getLogger(PluginServiceImpl.class.getCanonicalName());

	private class PluginLoadingProcessor {

		private PluginRepository repository;
		private MavenArtifact artifact;

		private Map<MavenArtifact, ArtifactLoader> queried = new ConcurrentHashMap<>();

		public PluginLoadingProcessor(LocalPluginRepository repository, MavenArtifact artifact) {
			this.repository = repository;
			this.artifact = artifact;
		}

		public CompletableFuture<Plugin> invoke() {
			return new ArtifactLoader(gpgVerifier, repository, artifact).load()
					.thenCompose(pl -> AsyncUtils.asyncRun(() -> {
						queried.put(artifact, pl);
						return CompletableFuture.allOf(
								pl.getDescription().orElseThrow(() -> new ArtifactNotFoundException(artifact.toString()))
										.getDependencies().stream()
										.map(dependency -> new ArtifactLoader(gpgVerifier, repository, dependency).load()
												.thenAccept(plDependency -> queried.put(dependency, plDependency)))
										.toArray(CompletableFuture[]::new))
								.thenCompose(dummy -> AsyncUtils.asyncRun(() -> {
									updateDependenciesState(queried, Collections.singleton(pl.getDescription().get()), Collections.emptySet());
									return getPlugin(artifact).get();
								}, cpuComputePool));
					}, cpuComputePool))
					.thenCompose(f -> f);
		}

	}

	@Reference(target = "(m2repository.type=local)")
	private LocalMavenRepository localM2Repo;

	@Reference(target = "(pluginRepo.type=local)")
	private LocalPluginRepository localPluginRepo;

	@Reference(target = "(usage=cpu_compute)")
	private ExecutorService cpuComputePool;

	@Reference
	private GPGVerifier gpgVerifier;

	private BundleContext bundleContext;

	private Map<MavenArtifact, Bundle> artifact2bundle = new ConcurrentHashMap<>();
	private Map<Bundle, MavenArtifact> bundle2artifact = new ConcurrentHashMap<>();
	private Map<Bundle, Plugin> bundle2plugin = new ConcurrentHashMap<>();
	private Set<Plugin> loadedPlugins = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private Map<MavenArtifact, byte[]> artifact2data = new ConcurrentHashMap<>();

	private Set<Plugin> loadedPluginsView = Collections.unmodifiableSet(loadedPlugins);
	private DependencyResolver resolver = new DependencyResolver();
	private final Object lock = new Object();

	@Activate
	public void active(ComponentContext compCtx)
			throws ClassNotFoundException, IllegalAccessException, InvocationTargetException, NoSuchMethodException, InterruptedException, ExecutionException {
		bundleContext = compCtx.getBundleContext();
		Class<?> endpoint = Class.forName("org.to2mbn.lolixl.main.AccessEndpoint", true, ClassLoader.getSystemClassLoader());
		@SuppressWarnings("unchecked")
		Map<String, Bundle> gav2bootstrapBundles = (Map<String, Bundle>) endpoint.getDeclaredMethod("getGav2bootstrapBundles").invoke(null);
		Map<Bundle, CompletableFuture<Optional<PluginDescription>>> descriptionFutures = new HashMap<>();
		synchronized (lock) {
			for (Entry<String, Bundle> entry : gav2bootstrapBundles.entrySet()) {
				String gav = entry.getKey();
				Bundle bundle = entry.getValue();
				String[] splitedGAV = gav.split(":", 3);
				MavenArtifact artifact = new MavenArtifact(splitedGAV[0], splitedGAV[1], splitedGAV[2]);
				byte[] data = (byte[]) endpoint.getDeclaredMethod("getBootstrapBundleData", String.class)
						.invoke(null, gav);
				descriptionFutures.put(bundle, localPluginRepo.getPluginDescription(artifact));
				addBundle0(bundle, artifact, data);
			}
			for (Entry<Bundle, CompletableFuture<Optional<PluginDescription>>> entry : descriptionFutures.entrySet()) {
				Bundle bundle = entry.getKey();
				Optional<PluginDescription> optionalDescription = entry.getValue().get();
				if (!optionalDescription.isPresent())
					continue;
				PluginDescription description = optionalDescription.get();
				addPlugin0(bundle, description);
			}
			checkDependenciesState();
		}
		LOGGER.info("Plugin service initialized");
	}

	@Override
	public Set<Plugin> getLoadedPlugins() {
		return loadedPluginsView;
	}

	@Override
	public Optional<Plugin> getPlugin(MavenArtifact artifact) {
		Bundle bundle = artifact2bundle.get(artifact);
		if (bundle != null)
			return Optional.ofNullable(bundle2plugin.get(bundle));
		return Optional.empty();
	}

	@Override
	public Optional<Plugin> getPlugin(Bundle bundle) {
		return Optional.ofNullable(bundle2plugin.get(bundle));
	}

	@Override
	public Optional<Bundle> getBundle(MavenArtifact artifact) {
		return Optional.ofNullable(artifact2bundle.get(artifact));
	}

	@Override
	public Optional<MavenArtifact> getArtifact(Bundle bundle) {
		return Optional.ofNullable(bundle2artifact.get(bundle));
	}

	@Override
	public CompletableFuture<Plugin> loadPlugin(LocalPluginRepository repository, MavenArtifact artifact) {
		Objects.requireNonNull(repository);
		Objects.requireNonNull(artifact);
		return new PluginLoadingProcessor(repository, artifact).invoke();
	}

	CompletableFuture<Void> unloadPlugin(Plugin plugin) {
		return AsyncUtils.asyncRun(() -> {
			updateDependenciesState(Collections.emptyMap(), Collections.emptySet(), Collections.singleton(plugin.getDescription()));
			return null;
		}, cpuComputePool);
	}

	private void addBundle0(Bundle bundle, MavenArtifact artifact, byte[] data) {
		artifact2bundle.put(artifact, bundle);
		bundle2artifact.put(bundle, artifact);
		artifact2data.put(artifact, data);
	}

	private void addPlugin0(Bundle bundle, PluginDescription description) {
		PluginImpl plugin = new PluginImpl();
		plugin.bundle = bundle;
		plugin.description = description;
		plugin.container = this;

		bundle2plugin.put(bundle, plugin);
		loadedPlugins.add(plugin);
	}

	private void removeBundle0(MavenArtifact artifact) {
		Bundle bundle = artifact2bundle.remove(artifact);
		bundle2artifact.remove(bundle);
		artifact2data.remove(artifact);

		Plugin plugin = bundle2plugin.remove(bundle);
		if (plugin != null)
			loadedPlugins.remove(plugin);
	}

	private void checkDependenciesState() throws IllegalStateException {
		if (artifact2bundle.size() != bundle2artifact.size())
			throw new IllegalStateException(format("artifact2bundle(%d) & bundle2artifact(%d) sizes mismatch", artifact2bundle.size(), bundle2artifact.size()));
		artifact2bundle.forEach((artifact, bundle) -> {
			if (bundle2artifact.get(bundle) != artifact)
				throw new IllegalStateException(format("Missing/wrong bundle2artifact mapping: %s -> %s", bundle, artifact));
		});
		if (!new HashSet<>(bundle2plugin.values()).equals(loadedPlugins))
			throw new IllegalStateException(format("bundle2plugin & loadedPlugins mismatch\nbundle2plugin: %s\nloadedPlugins: %s", bundle2plugin, loadedPlugins));
		Set<Bundle> pluginBundleCheck = new HashSet<>(bundle2plugin.keySet());
		pluginBundleCheck.removeAll(bundle2artifact.keySet());
		if (!pluginBundleCheck.isEmpty())
			throw new IllegalStateException(format("%s are in bundle2plugin, but not in bundle2artifact", pluginBundleCheck));

		Set<MavenArtifact> current = artifact2bundle.keySet();
		Set<MavenArtifact> expected = computeCurrentExpectedState();
		if (!current.equals(expected))
			throw new IllegalStateException(format("Illegal dependencies state\ncurrent: %s\nexpected:%s", current, expected));
	}

	private void performActions(List<DependencyAction> actions, Map<MavenArtifact, ArtifactLoader> artifact2loader) throws BundleException {
		Stack<DependencyAction> performedActions = new Stack<>();
		DependencyAction lastAction = null;

		// prepare for rollback
		Map<MavenArtifact, byte[]> oldArtifact2data = new HashMap<>(artifact2data);
		Map<MavenArtifact, PluginDescription> oldArtifact2description = resolver.toArtifact2DescriptionMap(getCurrentPluginDescriptions());

		LOGGER.info("Perform actions: " + actions);
		try {
			for (DependencyAction action : actions) {
				lastAction = action;
				performAction(action, artifact2loader);
				performedActions.push(action);
			}
		} catch (Throwable e) {
			LOGGER.warning(format("Couldn't perform %s, rollback", lastAction));

			Map<MavenArtifact, ArtifactLoader> newArtifact2loader = new HashMap<>(artifact2loader);

			oldArtifact2data.forEach((artifact, data) -> {
				Optional<PluginDescription> description = Optional.ofNullable(oldArtifact2description.get(artifact));
				newArtifact2loader.put(artifact, new ArtifactLoader(data, description));
			});

			while (!performedActions.isEmpty()) {
				DependencyAction actionToRollback = performedActions.pop();
				try {
					performAction(actionToRollback.revert(), newArtifact2loader);
				} catch (Throwable exRollback) {
					LOGGER.log(Level.SEVERE, "Couldn't rollback " + actionToRollback, exRollback);
					e.addSuppressed(new IllegalStateException("Couldn't rollback " + actionToRollback, exRollback));
				}
			}
			throw e;
		}
	}

	private void performAction(DependencyAction action, Map<MavenArtifact, ArtifactLoader> artifact2loader) throws BundleException {
		if (action instanceof InstallAction) {
			performInstall(action.artifact, artifact2loader.get(action.artifact));
		} else if (action instanceof UpdateAction) {
			MavenArtifact target = ((UpdateAction) action).targetArtifact;
			performUpdate(action.artifact, target, artifact2loader.get(target));
		} else if (action instanceof UninstallAction) {
			performUninstall(action.artifact);
		} else {
			throw new IllegalArgumentException("Unknown action: " + action);
		}
	}

	private void performInstall(MavenArtifact artifact, ArtifactLoader loader) throws BundleException {
		Objects.requireNonNull(artifact);
		Objects.requireNonNull(loader);
		if (getBundle(artifact).isPresent())
			throw new IllegalStateException(artifact + " is already installed");

		LOGGER.info("Installing " + artifact);
		byte[] data = loader.getJar();
		Bundle bundle = bundleContext.installBundle(getBundleURI(artifact), new ByteArrayInputStream(data));
		addBundle0(bundle, artifact, data);
		loader.getDescription().ifPresent(description -> addPlugin0(bundle, description));
	}

	private void performUpdate(MavenArtifact src, MavenArtifact dest, ArtifactLoader loader) throws BundleException {
		Objects.requireNonNull(src);
		Objects.requireNonNull(dest);
		Objects.requireNonNull(loader);
		if (src.equals(dest))
			throw new IllegalArgumentException("Version not changed: " + src);
		if (!getBundle(src).isPresent())
			throw new IllegalStateException(src + " is not installed");

		LOGGER.info(format("Updating %s -> %s", src, dest));
		Bundle bundle = getBundle(src).get();
		byte[] data = loader.getJar();
		bundle.update(new ByteArrayInputStream(data));

		removeBundle0(src);
		addBundle0(bundle, dest, data);
		loader.getDescription().ifPresent(description -> addPlugin0(bundle, description));
	}

	private void performUninstall(MavenArtifact artifact) throws BundleException {
		Objects.requireNonNull(artifact);
		if (!getBundle(artifact).isPresent())
			throw new IllegalStateException(artifact + " is not installed");

		LOGGER.info("Uninstalling " + artifact);
		Bundle bundle = getBundle(artifact).get();
		bundle.uninstall();
		removeBundle0(artifact);
	}

	private String getBundleURI(MavenArtifact artifact) {
		return "lolixl:///bundles/" + artifact;
	}

	private List<DependencyAction> computeActions(Set<PluginDescription> toInstall, Set<PluginDescription> toUninstall) {
		Set<MavenArtifact> retained = toInstall.stream()
				.map(PluginDescription::getArtifact)
				.collect(toSet());
		retained.retainAll(toUninstall);
		if (!retained.isEmpty()) {
			throw new IllegalStateException("Artifacts cannot be installed and uninstalled at a time: " + retained);
		}

		checkDependenciesState();

		Map<MavenArtifact, PluginDescription> pluginArtifact2Description = resolver.toArtifact2DescriptionMap(getCurrentPluginDescriptions());
		pluginArtifact2Description.putAll(resolver.toArtifact2DescriptionMap(toInstall));
		Set<MavenArtifact> newPlugins = resolver.merge(new HashSet<>(pluginArtifact2Description.keySet()));
		toUninstall.stream()
				.map(PluginDescription::getArtifact)
				.forEach(newPlugins::remove);

		Set<MavenArtifact> newState = resolver.computeState(newPlugins.stream()
				.map(pluginArtifact2Description::get)
				.collect(toSet()));
		return resolver.transferState(computeCurrentExpectedState(), newState);
	}

	private Set<MavenArtifact> computeCurrentExpectedState() {
		return resolver.computeState(getCurrentPluginDescriptions());
	}

	private Set<PluginDescription> getCurrentPluginDescriptions() {
		return loadedPlugins.stream()
				.map(Plugin::getDescription)
				.collect(toSet());
	}

	private void updateDependenciesState(Map<MavenArtifact, ArtifactLoader> artifact2loader, Set<PluginDescription> toInstall, Set<PluginDescription> toUninstall) {
		Objects.requireNonNull(artifact2loader);
		Objects.requireNonNull(toInstall);
		Objects.requireNonNull(toUninstall);

		LOGGER.info(format("Update state: toInstall=%s, toUninstall=%s", toInstall, toUninstall));

		try {
			synchronized (lock) {
				List<DependencyAction> actionsToPerform = computeActions(toInstall, toUninstall);
				LOGGER.info("Actions: " + actionsToPerform);
				performActions(actionsToPerform, artifact2loader);
			}

			IllegalStateException startExCollection = null;
			for (Bundle bundle : bundle2artifact.keySet()) {
				if (bundle.getState() != Bundle.ACTIVE &&
						bundle.getState() != Bundle.STARTING) {
					try {
						bundle.start();
					} catch (Throwable exStart) {
						LOGGER.log(Level.WARNING, format("Bundle %s couldn't start", bundle), exStart);
						if (startExCollection == null)
							startExCollection = new IllegalStateException("One or more bundles couldn't start");
						startExCollection.addSuppressed(exStart);
					}
				}
			}
			if (startExCollection!=null)
				throw startExCollection;
			
		} catch (Throwable ex) {
			LOGGER.log(Level.SEVERE, "Couldn't finish updating dependencies state", ex);
			throw new IllegalStateException("Couldn't finish updating dependencies state", ex);
		}
	}

}
