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

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.logging.*;
import java.util.logging.Formatter;

import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

import jdk.jfr.Recording;
// import jdk.jfr.consumer.RecordedEvent;

@Command(
        name = "liftylines",
        mixinStandardHelpOptions = true,
        versionProvider = Main.VersionProvider.class,
        description = "Generates map layer for XCTrack to display locally exposed areas",
        showDefaultValues = true
)

public class Main implements Runnable {
    @Spec
    CommandSpec spec;

    @Option(names = "--bbox",
            split = ",",
            description = "Bounding box coordinates: minLat,minLon,maxLat,maxLon",
            defaultValue = "47,12,48,13")
    double[] bbox; // must be length 4 if provided

    @Option(names = "--mountain-cutoff",
            description = "Mountain cutoff for prominence calculation in meters",
            defaultValue = "1000")
    float mountainCutoff;

    @Option(names = "--map-name",
            description = "Output file name for .map or .osm, defaults to directory name")
    String outputFiles;

    @Option(names = "--working-dir",
            description = "Working directory, defaults to current dir in your cmd",
            defaultValue = "${sys:user.dir}")
    String workingDir;

    @Option(names = "--tpi-cutoffs",
            split = ",",
            description = "TPI cutoffs, the lower the more areas marked as lifty",
            defaultValue = "10,15,20")
    double[] promCutoffs;

    @Option(names = "--radius-small",
            description = "Small radius for tpi calculation in arc seconds lat",
            defaultValue = "5.0")
    float radiusSmall;

    @Option(names = "--radius-large",
            description = "Large radius for tip calculation in arc seconds lat",
            defaultValue = "15.0")
    float radiusLarge;

    @Option(names = "--zoom-min",
            description = "Defines minimum zoom level for liftylines to show up in XCTrack",
            defaultValue = "11")
    int zoomMin;

    @Option(names = "--config",
            description = "Load parameters from a config file in current dir")
    boolean loadConfigFile = false;

    @Option(names = "--write-config",
            description = "Write current parameters to a config file in current dir")
    boolean writeConfigFile = false;

    @Option(names = "--osmosis-mode",
            description = "Run in alternative Osmosis mode")
    boolean osmosisMode = false;

    @Option(names = "--debug",
            description = "Enable debug mode, provides technical information in case of errors")
    boolean debug = false;

    @Option(names = "--zoom-string",
            description = "Defines zoom-level configuration for mapsforge map-writer",
            defaultValue = "5,0,7,10,8,10,11,11,21")
    String zoomString;

    @Option(names = "--simplification",
            description = "Defines simplification configuration for mapsforge map-writer",
            defaultValue = "11")
    int simplification;

    @Option(names = "--simplification-max-zoom",
            description = "Defines simplification of max zoom level for mapsforge map-writer",
            defaultValue = "11")
    byte simplificationMaxZoom;

    public static Logger LOG;
    public static long startTime = System.nanoTime();

    public static void main(String[] args) {
        Logger noisy = Logger.getLogger("org.mapsforge.map.writer.BaseTileBasedDataProcessor");
        noisy.setLevel(Level.OFF);
        Logger alsoNoisy = Logger.getLogger("org.pulce.liftylines.MapsforgeMapFileWriter");
        alsoNoisy.setLevel(Level.OFF);
        Main app = new Main();
        CommandLine cmd = new CommandLine(app);

        // handle parameter parsing errors
        cmd.setParameterExceptionHandler((pex, pexArgs) -> {
            System.err.println(pex.getMessage());
            // print usage for the command that failed
            pex.getCommandLine().usage(System.err);
            return pex.getCommandLine().getCommandSpec().exitCodeOnInvalidInput();
        });
        // handle execution errors
        cmd.setExecutionExceptionHandler((ex, cmdLine, parseResult) -> {
            System.err.println("Error: " + ex.getMessage());
            if (app.debug) {
                ex.printStackTrace(System.err);
            }
            return cmdLine.getCommandSpec().exitCodeOnExecutionException();
        });
        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine cmd = spec.commandLine();
        ParseResult pr = cmd.getParseResult();
        if (loadConfigFile) {
            String[] args = mergeArgs(pr.originalArgs()
                    .toArray(new String[0]));
            cmd.parseArgs(args);
        } else {
            cmd.parseArgs(pr.originalArgs().toArray(new String[0]));
        }

        configureLogging(debug);
        configureErrorHandling(debug);

        // initialize fileManager to get all file paths and directories sorted out
        FileManager fileManager = new FileManager(workingDir, outputFiles);

        // check if bbox is really array of 4 before initializing BoundingBox
        if (bbox.length != 4) {
            throw new IllegalArgumentException("Invalid bounding box " + Arrays.toString(bbox) + " -- you must provide a bounding box with 4 parameters.");
        }
        LiftyBoundingBox boundingBox = new LiftyBoundingBox(bbox[0], bbox[1], bbox[2], bbox[3]);
        if (boundingBox.getDimension() > 50) {
            Console console = System.console();
            String warning = "⚠️  Warning: huge bounding will result in huge data downloads, and probably memory issues. Continue? (yes/no): ";
            String input;
            if (console == null) {
                System.out.print(warning);
                try (Scanner sc = new Scanner(System.in)) {
                    input = sc.nextLine();
                }
            } else {
                input = console.readLine(warning);
            }
            if (!"yes".equalsIgnoreCase(input.trim())) {
                LOG.info("Aborted by user.");
                System.exit(2);
            }
        }
        LOG.fine("Bounding Box: " + boundingBox + " with dimension + " + boundingBox.getDimension());

        // Write out config if requested
        if (writeConfigFile) {
            FileManager.writeConfFile(spec);
        }
        //Recording rec = record();

        // Load elevation data
        short[][] elev = HgtFileReader.readElevationData(fileManager.tileDir, boundingBox);
        System.gc();
        LOG.info("Loading data finished after " + getComputationTime());
        // Prominence calculation: generate masks from hgt
        boolean[][][] masks = TpiCalculator.createMasksFromElevationData(elev, promCutoffs, boundingBox, radiusSmall, radiusLarge, mountainCutoff); // Create masks from HGT data
        elev = null;
        System.gc();
        LOG.info("TPI and masks calculated after " + getComputationTime());
        // Generate .map files from masks
        if (!osmosisMode) { // default .map workflow: iterate over masks, create and write polygons on-the-fly
            LiftyMapFileWriter mapFileWriter = new LiftyMapFileWriter(fileManager, boundingBox, zoomString, simplification, simplificationMaxZoom);
            mapFileWriter.writeMapFileFromMasks(masks);
            LOG.info("Writing " + fileManager.mapOutputFile + " finished after " + getComputationTime());
        } else { // .osm workflow: creates polygons from masks and writes to OSM format, after that converts to MAP.
            // We have to analyze the whole mask first due to donut problem. Donut hole polys will be childs of the parent poly.
            // Slower and more memory intensive way
            LiftyOsmFileWriter osmWriter = new LiftyOsmFileWriter(fileManager, boundingBox);
            osmWriter.writeOsmFileFromMasks(masks);
            LOG.info("Writing " + fileManager.osmOutputFile + " finished after " + getComputationTime());
            osmWriter.writeMapFileFromOsm(zoomString);
            LOG.info("Writing " + fileManager.mapOutputFile + " finished: after " + getComputationTime());
        }
        //rec.stop();

        // Provide a render theme for the generated .map
        fileManager.provideRenderTheme(promCutoffs, zoomMin);
        LOG.info("XCTrack render theme written to " + fileManager.themeOutputFile);
        LOG.info("Job done. Took " + getComputationTime());
        System.exit(0);
    }

    public static String[] mergeArgs(String[] rawArgs) {
        // Load config tokens first
        String[] cfgArgs = FileManager.readConfFileIntoArgs();
        cfgArgs = Arrays.stream(cfgArgs)
                .map(String::trim)       // knock off any leading/trailing spaces
                .filter(tok -> !tok.isEmpty()) // toss tokens that are now blank
                .toArray(String[]::new);

        // Figure out which options the user actually passed on the CLI
        Set<String> cliOpts = new HashSet<>();
        for (String tok : rawArgs) {
            if (tok.startsWith("--")) {
                // handle both "--key=value" and "--key"
                String key = tok.contains("=") ? tok.substring(0, tok.indexOf('=')) : tok;
                cliOpts.add(key);
            }
        }

        // Walk the config tokens, skipping any option that the CLI already has
        List<String> merged = new ArrayList<>();
        for (int i = 0; i < cfgArgs.length; i++) {
            String tok = cfgArgs[i];
            if (tok.startsWith("--")) {
                String key = tok.contains("=") ? tok.substring(0, tok.indexOf('=')) : tok;
                if (cliOpts.contains(key)) {
                    // skip this option **and** all its parameters
                    // (we assume “value” tokens don’t start with “--”)
                    while (i + 1 < cfgArgs.length && !cfgArgs[i + 1].startsWith("--")) {
                        i++;
                    }
                    continue;  // go to next config token after skipping values
                }
            }
            merged.add(tok);
        }

        // Finally tack on the real CLI args (they override anything left from cfg)
        merged.addAll(Arrays.asList(rawArgs));

        // Return the one-and-only array to feed into picocli
        return merged.toArray(new String[0]);
    }

    private static String getComputationTime() {
        return Math.round((System.nanoTime() - startTime) / 1_000_000.0) + " ms";
    }

    //Error Handling
    public void configureErrorHandling(boolean debug) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (throwable instanceof UncheckedIOException) {
                LOG.severe("I/O error: " + throwable.getCause().getMessage());
            } else if (throwable instanceof IllegalArgumentException) {
                LOG.severe("Data processing error: " + throwable.getMessage());
            } else if (throwable instanceof OutOfMemoryError) {
                LOG.severe(("Out of memory error. Check documentation on github for more info on what to do."));
            } else {
                LOG.severe("Unexpected error: " + throwable.getMessage());
            }
            if (debug) {
                throwable.printStackTrace(System.err);
            }
            System.exit(1);
        });
    }

    // Logging
    private void configureLogging(boolean debug) {
        LogManager.getLogManager().reset();  // 🔥 Kill all existing handlers
        Logger root = Logger.getLogger(""); // Root logger (parent of all loggers)
        root.setUseParentHandlers(false);   // Just in case

        String PROG = "org.pulce.liftylines.progress";
        Logger progressLogger = Logger.getLogger(PROG);
        progressLogger.setUseParentHandlers(false);  // don’t bubble to the root handlers

        Handler progressHandler = getProgressHandler();
        progressLogger.addHandler(progressHandler);
        progressLogger.setLevel(Level.INFO);
        try {
            Field f = LiftyMapFileWriter.class.getDeclaredField("PROGRESS");
            f.setAccessible(true);
            f.set(null, progressLogger);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException("Could not set up progress logger.", e);
        }

        // Silencing annoying loggers
        Logger noisy = Logger.getLogger("org.mapsforge.map.writer.BaseTileBasedDataProcessor");
        noisy.setLevel(debug ? Level.INFO : Level.WARNING);
        Logger alsoNoisy = Logger.getLogger("org.pulce.liftylines.MapsforgeMapFileWriter");
        alsoNoisy.setLevel(debug ? Level.INFO : Level.WARNING);

        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(debug ? Level.FINE : Level.INFO);
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                return record.getLevel() + ": " + record.getMessage() + System.lineSeparator();
            }
        });
        root.addHandler(handler);
        root.setLevel(debug ? Level.FINE : Level.INFO); // Set actual log level

        LOG = Logger.getLogger(Main.class.getName());
        LOG.setLevel(debug ? Level.FINE : Level.INFO);  // Just to be sure
    }

    private @NotNull Handler getProgressHandler() {
        Handler progressHandler = new StreamHandler(System.err, new Formatter() {
            @Override
            public String format(LogRecord record) {
                // we already include the \r in the message
                return "Progress: " + record.getMessage() + "\r";
            }

            @Override
            public synchronized String getHead(Handler h) {
                return "";
            }

            @Override
            public synchronized String getTail(Handler h) {
                return "";
            }
        }) {
            @Override
            public synchronized void publish(LogRecord record) {
                super.publish(record);
                flush();
            }
        };
        progressHandler.setLevel(Level.INFO);
        return progressHandler;
    }

    public static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() throws Exception {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("version.properties")) {
                Properties props = new Properties();
                props.load(in);
                return new String[]{props.getProperty("version", "unknown")};
            }
        }
    }

    // To check memory usage
    public Recording record() {
        Recording rec;
        try {
        rec = new Recording();
        rec.setName("MyRecording");
        rec.enable("jdk.CPULoad");
        rec.enable("jdk.JavaMonitorEnter");
        rec.enable("jdk.ThreadSleep");
        rec.enable("jdk.ThreadStart");
        rec.enable("jdk.ThreadEnd");
        rec.enable("jdk.GarbageCollection");
        rec.enable("jdk.ObjectAllocationOutsideTLAB")
                .withThreshold(Duration.ofSeconds(0))
                .withStackTrace();
        rec.enable("jdk.ObjectAllocationInNewTLAB")
                .withThreshold(Duration.ofSeconds(0))
                .withStackTrace();
        rec.enable("jdk.GCHeapSummary");
        rec.enable("jdk.GarbageCollection");
        rec.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(20));
        rec.setToDisk(true);
        rec.setDestination(Path.of("/home/pulce/tmp/liftyrec.jfr"));
        rec.start();
        } catch (IOException | IllegalStateException | SecurityException e) {
            throw new RuntimeException(e);
        }
        return rec;
    }
}