/*
 * Copyright (c) 2021 Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e. V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.fraunhofer.iais.eis.generate;

import fr.inria.acacia.corese.exceptions.EngineException;
import fr.inria.edelweiss.kgraph.core.Graph;
import fr.inria.edelweiss.kgraph.query.QueryProcess;
import fr.inria.edelweiss.kgtool.load.Load;
import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class Generator {

  private final Collection<String> vocabDirectories;
  private final Collection<String> templateDirectories;
  private final String outputDirs;
  private final Collection<String> vocabDirPathPatterns;
  private final int maxDepth;
  private final Graph graph = Graph.create();

  private Generator(Collection<String> vocabDirectories,
                    Collection<String> templateDirectories,
                    String OutputDirs,
                    Collection<String> vocabDirPathPatterns,
                    int maxDepth) {
    this.vocabDirectories = vocabDirectories;
    this.templateDirectories = templateDirectories;
    this.outputDirs = OutputDirs;
    this.vocabDirPathPatterns = vocabDirPathPatterns;
    this.maxDepth = maxDepth;
  }

  public static void main(String[] args) throws EngineException {
    CommandLineParser parser = new DefaultParser();
    Options options = createOptions();

    try {
      CommandLine line = parser.parse(options, args);
      Collection<String> vocabDirs = Arrays.asList(line.getOptionValues("vocabdirectory"));
      Collection<String> templateDirs = Arrays.asList(line.getOptionValues("templatedirectory"));
      Collection<String> vocabdirpathpattern = Arrays.asList(line.getOptionValues("vocabdirpathpattern"));
      String outputDirs = line.getOptionValue("outputdirectory");
      int maxdepth = line.hasOption("maxdepth") ? Integer.parseInt(line.getOptionValue("maxdepth")) : 5;

      new Generator(vocabDirs, templateDirs, outputDirs, vocabdirpathpattern, maxdepth).generate();
    } catch (ParseException | IOException exp) {
      System.err.println("Parsing failed.  Reason: " + exp.getMessage());

      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("Generator", options);
    }
  }

  private static Options createOptions() {
    Options options = new Options();

    Option vocabDirs = Option.builder("vd").argName("directory")
      .longOpt("vocabdirectory")
      .hasArg()
      .required()
      .desc("a directory containing vocabulary files")
      .build();
    Option templateDirs = Option.builder("td").argName("directory")
      .longOpt("templatedirectory")
      .hasArg()
      .required()
      .desc("a directory containing sparql template files")
      .build();
    Option pathPattern = Option.builder("pp").argName("vocab directory path pattern")
      .longOpt("vocabdirpathpattern")
      .hasArg()
      .desc("glob pattern used to select matching vocabulary files, e.g. '**/*.ttl'")
      .build();

    Option outputDirs = Option.builder("od").argName("directory")
      .longOpt("outputdirectory")
      .hasArg()
      .required()
      .desc("Output directory for the generated java source files")
      .build();

    Option maxDepth = Option.builder("md").argName("maximum depth for vocab directory file search")
      .longOpt("maxdepth")
      .hasArg()
      .desc("integer number defining the maximum depth (hierarchy levels) to descend into the vocabulary directory for finding files")
      .build();

    options.addOption(vocabDirs);
    options.addOption(templateDirs);
    options.addOption(outputDirs);
    options.addOption(pathPattern);
    options.addOption(maxDepth);

    return options;
  }

  public static boolean deleteDirectories(File dir) {
    File[] files = dir.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.isDirectory()) {
          deleteDirectories(file);
        } else {
          file.delete();
        }
      }
    }
    return dir.delete();
  }

  private void createDirectories(String dirPath) {
    try {
      Path path = Paths.get(dirPath);
      Files.createDirectories(path);

      System.out.println(dirPath+ " is created!");

    } catch (IOException e) {

      System.err.println("Failed to create directory!" + e.getMessage());
    }
  }

  private void generate() throws EngineException, IOException {

    List<String> dirList = List.of("/tmp/io/adminshell/aas/v3/model/",
      "/tmp/io/adminshell/aas/v3/model/impl",
      "/tmp/io/adminshell/aas/v3/model/annotations",
      "/tmp/io/adminshell/aas/v3/model/builder");
    dirList.forEach(this::createDirectories);
    Iterator<String> dirIterator = vocabDirectories.iterator();
    Iterator<String> dirPatternIterator = vocabDirPathPatterns.iterator();

    while (dirIterator.hasNext()) {
      String currentDir = dirIterator.next();
      String currentPattern = dirPatternIterator.next();
      try {
        loadVocabularyFiles(currentDir, currentPattern);
      } catch (IOException e) {
        System.err.println("Recursively getting vocabulary files failed. Skipping directory '" + currentDir + "' with pattern '" + currentPattern + "'. Reason: " + e.getMessage());
      }
    }

    for (String templateDir : templateDirectories) {
      doQuery("template {st:apply-templates-with(\"" + templateDir + "\")} where {}");
    }

    File source = new File("/tmp/io");
    String destinationPath = outputDirs + "/io";

    File tempDirectory = new File(destinationPath);
    if (tempDirectory.exists()) {
      deleteDirectories(new File(destinationPath));
      System.out.println("Cleaned destination folder");
    } else {
      createDirectories(destinationPath);
      System.out.println("Created destination folder");
    }

    File dest = new File(destinationPath);
    try {
      FileUtils.copyDirectory(source, dest);
      System.out.println("Copied in destination folder");
    } catch (IOException e) {
      e.printStackTrace();
    }
    boolean deleteResult = deleteDirectories(new File("/tmp/io"));
    if (deleteResult)
      System.out.println("Cleaned tmp folders");
  }

  private void loadVocabularyFiles(String rootDir, String pathPattern) throws IOException {
    Collection<File> files = recursivelyGetRdfFiles(rootDir, pathPattern);
    Load ld = Load.create(graph);
    files.forEach(file -> ld.load(file.getAbsolutePath()));
  }

  private Collection<File> recursivelyGetRdfFiles(String rootDir, String pathPattern) throws IOException {
    Collection<File> files = new ArrayList<>();

    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pathPattern);

    Files.find(Paths.get(rootDir),
        maxDepth,
        (filePath, fileAttr) -> matcher.matches(filePath)
      )
      .forEach(filePath -> files.add(filePath.toFile()));

    return files;
  }

  private void doQuery(String query) throws EngineException {
    QueryProcess exec = QueryProcess.create(graph);
    exec.query(query);
  }

}
