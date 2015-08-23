package no.tornado.libsass;

import wrm.libsass.SassCompiler;
import wrm.libsass.SassCompiler.InputSyntax;
import wrm.libsass.SassCompiler.OutputStyle;
import wrm.libsass.SassCompilerOutput;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.file.StandardWatchEventKinds.*;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static javax.servlet.http.HttpServletResponse.SC_NOT_MODIFIED;

public class SassFilter implements Filter {
	private static final Pattern JsfResourcePattern = Pattern.compile(".*/javax\\.faces\\.resource/(.*)\\.s?css.xhtml");
	private SassCompiler compiler;
	private ConcurrentHashMap<String, byte[]> cache;
	private WatchService watcher;
	private Boolean autoprefix;
	private String autoprefixBrowsers;
	private String autoprefixerPath;

	public void init(FilterConfig cfg) throws ServletException {
		compiler = new SassCompiler();
		compiler.setEmbedSourceMapInCSS(booleanSetting(cfg, "embedSourceMapInCSS", false));
		compiler.setEmbedSourceContentsInSourceMap(booleanSetting(cfg, "embedSourceContentsInSourceMap", false));
		compiler.setGenerateSourceComments(booleanSetting(cfg, "generateSourceComments", false));
		compiler.setGenerateSourceMap(booleanSetting(cfg, "generateSourceMap", false));
		compiler.setIncludePaths(cfg.getInitParameter("includePaths"));
		String inputSyntax = cfg.getInitParameter("inputSyntax");
		compiler.setInputSyntax(inputSyntax == null ? InputSyntax.scss : InputSyntax.valueOf(inputSyntax));
		String outputStyle = cfg.getInitParameter("outputStyle");
		compiler.setOutputStyle(outputStyle == null ? OutputStyle.compact : OutputStyle.valueOf(outputStyle));
		compiler.setOmitSourceMappingURL(booleanSetting(cfg, "omitSourceMappingURL", true));
		String precision = cfg.getInitParameter("precision");
		compiler.setPrecision(precision != null ? Integer.valueOf(precision) : 5);
		autoprefix = booleanSetting(cfg, "autoprefix", false);
		autoprefixBrowsers = cfg.getInitParameter("autoprefixBrowsers");
		autoprefixerPath = cfg.getInitParameter("autoprefixerPath");

		if (autoprefixerPath == null)
			autoprefixerPath = "autoprefixer";

		if (autoprefixBrowsers == null)
			autoprefixBrowsers = "last 2 versions, ie 10";

		if (booleanSetting(cfg, "cache", false)) {
			cache = new ConcurrentHashMap<>();
			if (booleanSetting(cfg, "watch", false))
				startWatchingForChanges(cfg);
		}
	}

	public void doFilter(ServletRequest _request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
		HttpServletRequest request = ((HttpServletRequest) _request);

		String servletPath = request.getServletPath();
		String absolute = null;

		Matcher matcher = JsfResourcePattern.matcher(servletPath);

		if (matcher.matches()) {
			String ln = request.getParameter("ln");
			String relative = ln == null ? String.format("/resources/%s.scss", matcher.group(1))
				: String.format("/resources/%s/%s.scss", ln, matcher.group(1));

			absolute = request.getServletContext().getRealPath(relative);
		} else if(servletPath.endsWith(".css")) {
			absolute = request.getServletContext().getRealPath(servletPath.replaceAll("\\.css$", ".scss"));
		} else if (servletPath.endsWith(".scss"))
			absolute = request.getServletContext().getRealPath(servletPath);

		if (absolute != null) {
			if (cache != null) {
				byte[] data = cache.computeIfAbsent(absolute, this::compile);
				outputCss(response, data);
				addCacheHeaders(Paths.get(absolute), request, (HttpServletResponse) response);
				return;
			} else if (Files.exists(Paths.get(absolute))){
				outputCss(response, compile(absolute));
				return;
			}
		}

		chain.doFilter(request, response);
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

						@SuppressWarnings("unchecked")
						WatchEvent<Path> ev = (WatchEvent<Path>) event;
						Path fullPath = dir.resolve(ev.context());
						String filename = fullPath.toString();
						try {
							if (filename.endsWith(".scss") || pathIsFolderAndContainsScss(fullPath))
								cache.keySet().forEach(this::compile);
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

	private byte[] compile(String absolute) {
		try {
			SassCompilerOutput output = compiler.compileFile(absolute, null, null);

			byte[] data;

			if (autoprefix) {
				Path tmpCss = Files.createTempFile("stylesheet", "css");
				Files.write(tmpCss, output.getCssOutput().getBytes("UTF-8"));
				ProcessBuilder pb = new ProcessBuilder(autoprefixerPath, tmpCss.toString(), "-b", autoprefixBrowsers);
				Process p = pb.start();
				int result = p.waitFor();
				if (result != 0)
					throw new ServletException("Autoprefixer failed with error code " + result);

				data = Files.readAllBytes(tmpCss);

				try {
					Files.deleteIfExists(tmpCss);
				} catch (IOException ignored) {
				}

			} else {
				try {
					data = output.getCssOutput().getBytes("UTF-8");
				} catch (UnsupportedEncodingException noUtf8) {
					throw new RuntimeException(noUtf8);
				}
			}
			return data;
		} catch (Exception e) {
			throw new RuntimeException("Autoprefixer failed", e);
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

	private void addCacheHeaders(Path path, HttpServletRequest request, HttpServletResponse response) throws IOException {
		Instant modified = Files.getLastModifiedTime(path).toInstant();
		Long ifModifiedSinceValue;

		try {
			ifModifiedSinceValue = request.getDateHeader("If-Modified-Since");
		} catch (Exception malformedDateHeaderFromClient) {
			ifModifiedSinceValue = -1l;
		}

		if (ifModifiedSinceValue > -1) {
			Instant ifModifiedSince = new Date(ifModifiedSinceValue).toInstant();

			if (modified.equals(ifModifiedSince) || modified.isBefore(ifModifiedSince)) {
				response.setStatus(SC_NOT_MODIFIED);
				return;
			}
		}

		response.setHeader("Expires", RFC_1123_DATE_TIME.format(ZonedDateTime.now().plusHours(3)));
		response.setHeader("Last-Modified", RFC_1123_DATE_TIME.format(ZonedDateTime.from(modified.atZone(ZoneId.systemDefault()))));
	}

}