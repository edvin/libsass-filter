package no.tornado.libsass;

import wrm.libsass.SassCompiler;
import wrm.libsass.SassCompiler.InputSyntax;
import wrm.libsass.SassCompiler.OutputStyle;
import wrm.libsass.SassCompilerOutput;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static java.nio.file.StandardWatchEventKinds.*;

@WebFilter(urlPatterns = {"*.css", "*.xhtml"})
public class SassFilter implements Filter {
	private static final Pattern JsfResourcePattern = Pattern.compile(".*/javax\\.faces\\.resource/(.*)\\.css.xhtml");
	private SassCompiler compiler;
	private Boolean cache;
	private Map<String, byte[]> compiledCache;
	private WatchService watcher;
	private Boolean autoprefix;

	public void init(FilterConfig cfg) throws ServletException {
		compiler = new SassCompiler();
		compiler.setEmbedSourceMapInCSS(booleanSetting(cfg, "embedSourceMapInCSS", false));
		compiler.setEmbedSourceContentsInSourceMap(booleanSetting(cfg, "embedSourceContentsInSourceMap", false));
		compiler.setGenerateSourceComments(booleanSetting(cfg, "generateSourceComments", false));
		compiler.setGenerateSourceMap(booleanSetting(cfg, "generateSourceMap", false));
		compiler.setImagePath(cfg.getInitParameter("imagePath"));
		compiler.setIncludePaths(cfg.getInitParameter("includePaths"));
		String inputSyntax = cfg.getInitParameter("inputSyntax");
		compiler.setInputSyntax(inputSyntax == null ? InputSyntax.scss : InputSyntax.valueOf(inputSyntax));
		String outputStyle = cfg.getInitParameter("outputStyle");
		compiler.setOutputStyle(outputStyle == null ? OutputStyle.compact : OutputStyle.valueOf(outputStyle));
		compiler.setOmitSourceMappingURL(booleanSetting(cfg, "omitSourceMappingURL", true));
		String precision = cfg.getInitParameter("precision");
		compiler.setPrecision(precision != null ? Integer.valueOf(precision) : 5);
		autoprefix = booleanSetting(cfg, "autoprefix", false);
		cache = booleanSetting(cfg, "cache", false);

		if (cache) {
			compiledCache = new HashMap<>();
			if (booleanSetting(cfg, "watch", false))
				startWatchingForChanges(cfg);
		}
	}

	public void doFilter(ServletRequest _request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
		HttpServletRequest request = ((HttpServletRequest) _request);

		String relative = null;

		String servletPath = request.getServletPath();

		if (servletPath.endsWith(".css")) {
			relative = servletPath.replaceAll("\\.css$", ".scss");
		} else {
			Matcher matcher = JsfResourcePattern.matcher(servletPath);
			if (matcher.matches()) {
				String ln = request.getParameter("ln");
				relative = ln == null ? format("/resources/%s.scss", matcher.group(1))
					: format("/resources/%s/%s.scss", ln, matcher.group(1));
			}
		}

		if (relative != null) {
			String absolute = request.getServletContext().getRealPath(relative);
			if (absolute != null) {
				if (cache && compiledCache.containsKey(absolute))
					outputCss(response, compiledCache.get(absolute));
				else if (Files.exists(Paths.get(absolute)))
					outputCss(response, compile(absolute));
			} else {
				chain.doFilter(request, response);
			}
		} else {
			chain.doFilter(request, response);
		}
	}

	private void startWatchingForChanges(FilterConfig cfg) throws ServletException {
		try {
			watcher = FileSystems.getDefault().newWatchService();
			Path dir = Paths.get(cfg.getServletContext().getRealPath("/"));
			dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY);
			new Thread(() -> {
				while (compiler != null) {
					WatchKey key;
					try {
						key = watcher.take();
					} catch (InterruptedException ex) {
						return;
					}

					for (WatchEvent<?> event : key.pollEvents()) {
						WatchEvent.Kind<?> kind = event.kind();
						if (kind == OVERFLOW)
							continue;

						WatchEvent<Path> ev = (WatchEvent<Path>) event;
						Path fullPath = dir.resolve(ev.context());
						String filename = fullPath.toString();
						try {
							if (filename.endsWith(".scss") || pathIsFolderAndContainsScss(fullPath)) {
								for (String absolute : compiledCache.keySet())
									compile(absolute);
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
					}

					boolean valid = key.reset();
					if (!valid)
						break;
				}
			}).start();
		} catch (IOException e) {
			throw new ServletException("Unable to start watch service", e);
		}
	}

	/**
	 * MacOS X only reports the directory that has been changed, so we will check if the
	 * changed directory contains scss-files.
	 */
	private boolean pathIsFolderAndContainsScss(Path path) throws IOException {
		return Files.isDirectory(path)
			&& Files.list(path).filter(p -> p.getFileName().toString().endsWith(".scss")).findAny().isPresent();
	}

	private boolean booleanSetting(FilterConfig cfg, String name, boolean defaultValue) {
		String value = cfg.getInitParameter(name);
		return value != null ? "true".equalsIgnoreCase(value) : defaultValue;
	}

	private byte[] compile(String absolute) throws ServletException {
		try {

			SassCompilerOutput output = compiler.compileFile(absolute, null, null);

			byte[] data;

			if (autoprefix) {
				Path tmpCss = Files.createTempFile("stylesheet", "css");
				Files.write(tmpCss, output.getCssOutput().getBytes("UTF-8"));

				Path tmpPrefixed = Files.createTempFile("prefixed", "css");
				ProcessBuilder pb = new ProcessBuilder("autoprefixer", "-o", tmpPrefixed.toAbsolutePath().toString(), tmpCss.toString());
				Process p = pb.start();
				int result = p.waitFor();
				if (result != 0)
					throw new ServletException("Autoprefixer failed with error code " + result);

				data = Files.readAllBytes(tmpPrefixed);

				try {
					Files.deleteIfExists(tmpCss);
					Files.deleteIfExists(tmpPrefixed);
				} catch (IOException ignored) {
				}

			} else {
				try {
					data = output.getCssOutput().getBytes("UTF-8");
				} catch (UnsupportedEncodingException noUtf8) {
					throw new RuntimeException(noUtf8);
				}
			}

			if (cache)
				compiledCache.put(absolute, data);

			return data;
		} catch (ServletException e) {
			throw e;
		} catch (Exception e) {
			throw new ServletException("Autoprefixer failed", e);
		}
	}

	private void outputCss(ServletResponse response, byte[] data) throws IOException {
		response.setContentType("text/css");
		response.getOutputStream().write(data);
	}

	public void destroy() {
		compiler = null;
		if (watcher != null) {
			try {
				watcher.close();
				watcher = null;
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}