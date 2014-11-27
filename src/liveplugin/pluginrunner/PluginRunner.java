package liveplugin.pluginrunner;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.ide.plugins.cl.PluginClassLoader;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import groovy.lang.GroovyClassLoader;
import org.apache.oro.io.GlobFilenameFilter;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public interface PluginRunner {
	String IDE_STARTUP = "IDE_STARTUP";
	String ADD_TO_CLASSPATH_KEYWORD = "add-to-classpath ";
	String DEPENDS_ON_PLUGIN_KEYWORD = "depends-on-plugin ";

	/**
	 * @param pathToPluginFolder absolute path to plugin folder
	 * @return true if {@link PluginRunner} can run plugin in this folder
	 */
	boolean canRunPlugin(String pathToPluginFolder);

	/**
	 * @param pathToPluginFolder absolute path to plugin folder
	 * @param pluginId plugin id, e.g. to distinguish it from other plugins in error messages
	 * @param binding map with implicit variables available in plugin script
	 * @param runOnEDTCallback callback which should be used to run plugin code on EDT
	 */
	void runPlugin(String pathToPluginFolder, String pluginId, Map<String, ?> binding, Function<Runnable, Void> runOnEDTCallback);

	String scriptName();

	class ClasspathAddition {
		private static final Logger LOG = Logger.getInstance(ClasspathAddition.class);

		public static ClassLoader createClassLoaderWithDependencies(List<String> pathsToAdd, List<String> pluginsToAdd, String mainScriptUrl, String pluginId, ErrorReporter errorReporter) {
			ClassLoader parentLoader = createParentClassLoader(pluginsToAdd, pluginId, errorReporter);
			GroovyClassLoader classLoader = new GroovyClassLoader(parentLoader);
			try {

				for (String path : pathsToAdd) {
					if (path.startsWith("file:/")) {
						URL url = new URL(path);
						classLoader.addURL(url);
						classLoader.addClasspath(url.getFile());
					} else {
						classLoader.addURL(new URL("file:///" + path));
						classLoader.addClasspath(path);
					}
				}

			} catch (IOException e) {
				errorReporter.addLoadingError(pluginId, "Error while looking for dependencies in '" + mainScriptUrl + "'. " + e.getMessage());
			}
			return classLoader;
		}

		public static ClassLoader createParentClassLoader(List<String> pluginsToAdd, final String pluginId, final ErrorReporter errorReporter) {
			List<ClassLoader> parentLoaders = getParentLoaders(pluginsToAdd, new Function<String, Void>() {
				@Override
				public Void fun(String dependentPluginId) {
					errorReporter.addLoadingError(pluginId, "Couldn't find dependent plugin '" + dependentPluginId + "'");
					return null;
				}
			});
			parentLoaders.add(PluginRunner.class.getClassLoader());

			String pluginVersion = "1.0.0";
			return new PluginClassLoader(new ArrayList<URL>(), parentLoaders.toArray(new ClassLoader[parentLoaders.size()]),
										 PluginId.getId(pluginId), pluginVersion, null);
		}

		private static List<ClassLoader> getParentLoaders(List<String> pluginIds, Function<String, Void> onError) {
			final List<ClassLoader> classLoaders = new ArrayList<ClassLoader>();
			for (String rawPluginId : pluginIds) {
				final PluginId pluginId = PluginId.getId(rawPluginId);
				final IdeaPluginDescriptor pluginDescriptor = PluginManager.getPlugin(pluginId);

				if (pluginDescriptor == null) {
					onError.fun(rawPluginId);
				} else {
					classLoaders.add(pluginDescriptor.getPluginClassLoader());
				}
			}
			return classLoaders;
		}

		public static List<String> findPluginDependencies(String[] lines, String prefix) {
			List<String> pluginsToAdd = new ArrayList<String>();
			for (String line : lines) {
				if (!line.startsWith(prefix)) continue;

				String pluginId = line.replace(prefix, "").trim();
				pluginsToAdd.add(pluginId);
			}
			return pluginsToAdd;
		}

		public static List<String> findClasspathAdditions(String[] lines, String prefix, Map<String, String> environment, Function<String, Void> onError) throws IOException {
			List<String> pathsToAdd = new ArrayList<String>();
			for (String line : lines) {
				if (!line.startsWith(prefix)) continue;

				String path = line.replace(prefix, "").trim();
				path = inlineEnvironmentVariables(path, environment);

				List<String> matchingFiles = findMatchingFiles(path);
				if (matchingFiles.isEmpty()) {
					onError.fun(path);
				} else {
					pathsToAdd.addAll(matchingFiles);
				}
			}
			return pathsToAdd;
		}

		private static List<String> findMatchingFiles(String pathAndPattern) {
			if (new File(pathAndPattern).exists()) return Collections.singletonList(pathAndPattern);

			int separatorIndex = pathAndPattern.lastIndexOf(File.separator);
			String path = pathAndPattern.substring(0, separatorIndex + 1);
			String pattern = pathAndPattern.substring(separatorIndex + 1);

			File[] files = new File(path).listFiles((FileFilter) new GlobFilenameFilter(pattern));
			files = (files == null ? new File[0] : files);
			return ContainerUtil.map(files, new Function<File, String>() {
				@Override
				public String fun(File file) {
					return file.getAbsolutePath();
				}
			});
		}

		private static String inlineEnvironmentVariables(String path, Map<String, String> environment) {
			boolean wasModified = false;
			for (Map.Entry<String, String> entry : environment.entrySet()) {
				path = path.replace("$" + entry.getKey(), entry.getValue());
				wasModified = true;
			}
			if (wasModified) LOG.info("Additional classpath with inlined env variables: " + path);
			return path;
		}
	}
}
