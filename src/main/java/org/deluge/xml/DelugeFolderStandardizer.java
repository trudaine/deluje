package org.deluge.xml;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class DelugeFolderStandardizer {

  public static void main(String[] args) throws IOException {
    Path downloadArea = Paths.get(System.getProperty("user.home"), "delugedownload");
    if (!Files.exists(downloadArea)) {
      System.err.println("Download area not found: " + downloadArea);
      return;
    }

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(downloadArea)) {
      for (Path entry : stream) {
        if (Files.isDirectory(entry)) {
          standardizeFolder(entry);
        }
      }
    }
    System.out.println("Standardization complete.");
  }

  private static void standardizeFolder(Path root) throws IOException {
    System.out.println("Processing: " + root.getFileName());

    Path songsDir = root.resolve("SONGS");
    Path kitsDir = root.resolve("KITS");
    Path synthsDir = root.resolve("SYNTHS");
    Path samplesDir = root.resolve("SAMPLES");

    Files.createDirectories(songsDir);
    Files.createDirectories(kitsDir);
    Files.createDirectories(synthsDir);
    Files.createDirectories(samplesDir);

    // 1. Move all audio files to SAMPLES (preserving existing sub-path if it starts with SAMPLES)
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (isAudioFile(file)) {
              moveToSamples(root, file, samplesDir);
            }
            return FileVisitResult.CONTINUE;
          }
        });

    // 2. Move and classify XML files
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (file.toString().toLowerCase().endsWith(".xml")) {
              classifyAndMoveXml(file, songsDir, kitsDir, synthsDir);
            }
            return FileVisitResult.CONTINUE;
          }
        });

    // 3. Cleanup empty directories (except the 4 target ones)
    cleanupEmptyDirs(root, Arrays.asList(songsDir, kitsDir, synthsDir, samplesDir));
  }

  private static boolean isAudioFile(Path file) {
    String name = file.toString().toLowerCase();
    return name.endsWith(".wav")
        || name.endsWith(".aif")
        || name.endsWith(".aiff")
        || name.endsWith(".mp3");
  }

  private static void moveToSamples(Path root, Path file, Path targetSamplesDir)
      throws IOException {
    if (file.startsWith(targetSamplesDir)) return;

    // Check if it's already in a subdirectory named SAMPLES
    Path relative = root.relativize(file);
    Path target;

    if (relative.toString().toUpperCase().contains("SAMPLES")) {
      // Try to extract the part after "SAMPLES"
      String relStr = relative.toString().replace("\\", "/");
      int idx = relStr.toUpperCase().indexOf("SAMPLES/");
      if (idx != -1) {
        String subPath = relStr.substring(idx + 8);
        target =
            targetSamplesDir.resolve(subPath.replace("/", root.getFileSystem().getSeparator()));
      } else {
        target = targetSamplesDir.resolve(file.getFileName());
      }
    } else {
      target = targetSamplesDir.resolve(file.getFileName());
    }

    Files.createDirectories(target.getParent());
    if (!Files.exists(target)) {
      Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
    } else if (!file.equals(target)) {
      Files.delete(file); // Duplicate or same file
    }
  }

  private static void classifyAndMoveXml(Path file, Path songs, Path kits, Path synths)
      throws IOException {
    if (file.getParent().equals(songs)
        || file.getParent().equals(kits)
        || file.getParent().equals(synths)) return;

    String content;
    try {
      content = Files.readString(file).toLowerCase();
    } catch (java.nio.charset.MalformedInputException e) {
      return; // Not a text/xml file
    }
    Path targetDir;

    if (content.contains("<song")) {
      targetDir = songs;
    } else if (content.contains("<kit")) {
      targetDir = kits;
    } else if (content.contains("<sound")) {
      targetDir = synths;
    } else {
      return; // Unknown XML type
    }

    Path target = targetDir.resolve(file.getFileName());
    if (!Files.exists(target)) {
      Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
    } else if (!file.equals(target)) {
      // Handle collision by prefixing with parent folder name if generic
      String newName =
          file.getParent().getFileName().toString() + "_" + file.getFileName().toString();
      Files.move(file, targetDir.resolve(newName), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void cleanupEmptyDirs(Path root, List<Path> exclude) throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (!dir.equals(root) && !exclude.contains(dir)) {
              try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
                if (!s.iterator().hasNext()) {
                  Files.delete(dir);
                }
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
