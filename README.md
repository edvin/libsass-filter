# libsass-filter

## Compiles SASS to CSS on-the-fly

libsass-filter uses [libsass-maven-plugin](https://github.com/warmuuh/libsass-maven-plugin)
 which uses [libsass](https://github.com/sass/libsass) under the covers via Jna native bindings.
 You are however not required to use Maven, we just use the sass bindings from libsass-maven-plugin to access libsass.
 
 It also supports autoprefixing via [autoprefixer](https://github.com/postcss/autoprefixer).
 
### Installation
 
Include the following Maven dependency in your pom.xml:

    <dependency>
        <groupId>no.tornado</groupId>
        <artifactId>libsass-filter</artifactId>
        <version>0.1.2.8</version>
    </dependency>

The default settings are good for development, as your stylesheets will be recompiled
on every request. This takes less than 100ms on my test setup with over 40 imports.

For production use you should use `libsass-maven-plugin` to precompile your stylesheets.
 Optionally, configure this filter to cache and compress in web.xml:
 
     <filter>
         <filter-name>SassFilter</filter-name>
         <filter-class>no.tornado.libsass.SassFilter</filter-class>
         <init-param>
             <param-name>outputStyle</param-name>
             <param-value>compressed</param-value>
         </init-param>
         <init-param>
             <param-name>cache</param-name>
             <param-value>true</param-value>
         </init-param>
         <!-- Autoprefix CSS, requires npm install -g autoprefixer -->
         <init-param>
             <param-name>autoprefix</param-name>
             <param-value>true</param-value>
         </init-param>
         <init-param>
             <param-name>autoprefixBrowsers</param-name>
             <param-value>last 2 versions, ie 10</param-value>
         </init-param>
         <init-param>
             <param-name>autoprefixerPath</param-name>
             <param-value>/my/path/to/autoprefixer</param-value>
         </init-param>
     </filter>
 
     <filter-mapping>
         <filter-name>SassFilter</filter-name>
         <url-pattern>*.css</url-pattern>
     </filter-mapping>
 
     <!-- Optional support for JSF resources -->
     <filter-mapping>
         <filter-name>SassFilter</filter-name>
         <url-pattern>*.xhtml</url-pattern>
     </filter-mapping>

### Configuration
 
 All configuration options are set via init-params in web.xml.
 
 * `cache` (true|false) - Cache after the initial request. Default: false
 * `watch` (true|false) - Watch for file changes and recompile. Useful together with `cache`. Default: false
 * `outputStyle` (nested, expanded, compact, compressed) - Output CSS style. Default: compact.
 * `includePaths` - Additional include paths
 * `autoprefix` (true|false) - Add prefixes using [autoprefixer](https://github.com/postcss/autoprefixer) (requires `npm install --global autoprefixer`)
 * `autoprefixBrowsers` - What browsers to prefix for, defaults to `last 2 versions, ie 10`
 * `autoprefixerPath` - Path to autoprefixer, defaults to `autoprefixer`. On Windows, you need either fullpath or for example `autoprefixer.cmd`
 
### Usage

 All requests to `.css` and optionally `.css.xhtml` are checked for a `.scss` counterpart,
 and if found, the SASS resources are compiled and returned as a CSS stylesheet.
 
 The default no-cache mode is so fast that you would probably never need to cache the
 result during development, but if you want to use this filter in production instead of
 precompiling your stylesheets, make sure you set `cache` to `true`.