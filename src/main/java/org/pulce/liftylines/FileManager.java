/*
 * Copyright 2025 liftyLines
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.pulce.liftylines;

import picocli.CommandLine;

import java.awt.*;
import java.io.*;
import java.lang.reflect.Array;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class FileManager {

    public Path workingDir;
    public Path tileDir;
    public Path mapOutputFile;
    public Path osmOutputFile;
    public Path themeOutputFile;
    public Path userDir;
    public Path tagMappingFile;

    public static Path CONF_FILE = Paths.get(System.getProperty("user.dir"), ".liftylines.conf");
    public static String THEME_BASE_FILE = "liftylines_theme_base.xml";

    public static Logger LOG = Logger.getLogger(FileManager.class.getName());

    public FileManager(String workingDirString, String outputFileName) {
        // check WD path
        workingDir = Paths.get(workingDirString).toAbsolutePath().normalize();
        if (!Files.exists(workingDir)) {
            throw new IllegalArgumentException("Working directory does not exist: " + workingDirString);
        }
        directoryRwCheck(workingDir);
        LOG.fine("Working directory set to: " + workingDirString);

        if (outputFileName == null || outputFileName.isEmpty()) {
            outputFileName = "" + workingDir.getName(workingDir.getNameCount() - 1);
            LOG.fine("No output file name provided, falling back to directory name: " + outputFileName);
        }

        // Generate .map output file path
        mapOutputFile = workingDir.resolve(outputFileName + ".map");
        LOG.fine("Target .map file set to: " + mapOutputFile);
        if (Files.exists(mapOutputFile)) LOG.fine(mapOutputFile + " exists, but will be overwritten.");

        // Generate .osm output file path
        osmOutputFile = workingDir.resolve(outputFileName + ".osm");
        LOG.fine("Target .osm file set to: " + osmOutputFile);
        if (Files.exists(osmOutputFile)) LOG.fine(osmOutputFile + " exists, but will be overwritten.");

        // Check tile directory
        tileDir = workingDir.resolve("tiles");
        LOG.fine("Tile directory set to: " + tileDir);

        if (!Files.exists(tileDir)) {
            try {
                LOG.fine("Tile directory does not exist, will create it now.");
                Files.createDirectories(tileDir);
            } catch (IOException e) {
                throw new UncheckedIOException("Creating tiles directory " + tileDir + " failed.", e);
            }
        }
        directoryRwCheck(tileDir);

        // Generate .xml theme file path
        themeOutputFile = workingDir.resolve("liftylines_theme.xml");
        LOG.fine("Target theme file set to: " + themeOutputFile);
        if (Files.exists(themeOutputFile)) LOG.fine(themeOutputFile + " exists, but will be overwritten.");

        // Check out userDir path
        userDir = Paths.get(System.getProperty("user.dir"));
        LOG.fine("You are running liftylines from userdir: " + userDir);
        if (!Files.isWritable(userDir)) {
            LOG.warning("Cannot write to userdir, thus cannot write a config file.");
        } else {
            LOG.fine("In case of --write-config, conf file will be written to: " + CONF_FILE);
        }

        // Check out tag-mapping file path
        tagMappingFile = locateTagMappingFile("lifty-tag-mapping.xml");
        LOG.fine("Tag-mapping file found: " + tagMappingFile);
    }

    private void directoryRwCheck(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException(dir + " exists but is not a directory.");
        }
        if (!Files.isReadable(dir)) {
            throw new IllegalArgumentException("No read access to directory: " + dir);
        }
        if (!Files.isWritable(dir)) {
            throw new IllegalArgumentException("No write access to directory: " + dir);
        }
    }

    public static String[] readConfFileIntoArgs() {
        List<String> tokens = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(CONF_FILE, StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String key;
                String val;
                int idx = line.indexOf('=');
                if (idx < 0) {
                    key = line;
                    val = "";
                } else {
                    key = line.substring(0, idx);
                    val = line.substring(idx + 1);
                }
                tokens.add("--" + key);
                if (!val.isEmpty()) {
                    tokens.add(val);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config file " + CONF_FILE, e);
        }
        return tokens.toArray(new String[0]);    }

    public static void writeConfFile(CommandLine.Model.CommandSpec spec) {
        try (BufferedWriter writer = Files.newBufferedWriter(CONF_FILE, StandardCharsets.UTF_8)) {
            writer.write("# Generated by liftylines");
            writer.newLine();

            for (CommandLine.Model.OptionSpec opt : spec.options()) {
                String name = opt.longestName();
                if (opt.hidden() || opt.usageHelp()
                        || "--config".equals(name) || "--write-config".equals(name)) {
                    continue;
                }
                Object val = opt.getValue();
                if (val == null) continue;

                String key = name.replaceFirst("^--", "");

                // 1) Boolean flags: write just the key if true
                if (val instanceof Boolean) {
                    if ((Boolean) val) {
                        writer.write(key);
                        writer.newLine();
                    }
                    continue;
                }

                // 2) Arrays: comma-join
                String str;
                if (val.getClass().isArray()) {
                    int n = Array.getLength(val);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < n; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(Array.get(val, i).toString());
                    }
                    str = sb.toString();
                } else {
                    // 3) Everything else: toString()
                    str = val.toString();
                }

                writer.write(key + "=" + str);
                writer.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write config file to " + CONF_FILE, e);
        }
        LOG.info("Wrote config to " + CONF_FILE);
    }

    public Path locateTagMappingFile(String tagMappingFile) {
        // 1) IDE/dev mode: src/main/resources
        Path devPath = Paths.get("src", "main", "resources", tagMappingFile);
        if (Files.exists(devPath)) {
            return devPath;
        }
        // 2) Installed mode: next to JAR in install root
        CodeSource cs = Main.class.getProtectionDomain().getCodeSource();
        if (cs != null && cs.getLocation() != null) {
            try {
                Path codeLoc = Paths.get(cs.getLocation().toURI());
                Path installRoot;
                installRoot = codeLoc.getParent();    // .../install/lib
                Path prodPath = installRoot.resolve(tagMappingFile);
                if (Files.exists(prodPath)) {
                    return prodPath;
                }
            } catch (URISyntaxException e) {
                // fall through to next
            }
        }
        throw new RuntimeException("Tag mapping file not found in expected locations. Should be in the lib folder of liftylines or in the resources folder if you work in an IDE.");
    }


    public void provideRenderTheme(double[] cutoffs, int zoomMin) {
        // 1) Load the base template from resources
        String template;
        try (InputStream in = Main.class.getResourceAsStream("/" + THEME_BASE_FILE)) {
            if (in == null) {
                throw new IOException("Template not found on classpath!");
            }
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load render theme template: " + THEME_BASE_FILE, e);
        }

        // 2) Compute the gradient of colors
        Color start = Color.decode("#ff7f00");  // light
        Color end = Color.decode("#804000");  // dark
        int n = cutoffs.length;
        StringBuilder mBlocks = new StringBuilder();

        for (int i = 0; i < n; i++) {
            double t = (n == 1) ? 0.0 : (double) i / (n - 1);
            int r = (int) Math.round(start.getRed() + t * (end.getRed() - start.getRed()));
            int g = (int) Math.round(start.getGreen() + t * (end.getGreen() - start.getGreen()));
            int b = (int) Math.round(start.getBlue() + t * (end.getBlue() - start.getBlue()));
            String hex = String.format("#%02x%02x%02x", r, g, b);

            // 3) Build the <m> block
            mBlocks.append("  <m k=\"liftyline\" v=\"").append(i + 1)
                    .append("\" k2=\"layer\" v2=\"5\" zoom-min=\"").append(zoomMin)
                    .append("\" zoom-max=\"24\" display-size-min=\"0\">\n")
                    .append("    <area fill=\"").append(hex).append("\"/>\n")
                    .append("    <line stroke=\"").append(hex).append("\" stroke-width=\"2\"/>\n")
                    .append("  </m>\n");
        }

        // 4) Inject into the template and write out
        String result = template.replace("LIFTYLINE", mBlocks.toString());
        try {
            Files.writeString(themeOutputFile, result, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write render theme to " + themeOutputFile, e);
        }
    }


}
