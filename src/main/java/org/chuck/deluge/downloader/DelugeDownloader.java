package org.chuck.deluge.downloader;

import java.io.*;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DelugeDownloader {

  private static final String[] CATEGORIES = {
    "https://forums.synthstrom.com/categories/deluge-sample-packs",
    "https://forums.synthstrom.com/categories/deluge-patches"
  };
  private static final Path BASE_DIR = Paths.get(System.getProperty("user.home"), "delugedownload");
  private static final Path LOG_FILE = BASE_DIR.resolve("download_log.md");

  private static final HttpClient client =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.ALWAYS)
          .cookieHandler(new CookieManager())
          .build();

  public static void main(String[] args) throws Exception {
    initializeLog();
    Set<String> allDiscussions = new TreeSet<>();

    for (String cat : CATEGORIES) {
      System.out.println("Crawling category: " + cat);
      for (int p = 1; p <= 4; p++) {
        String pageUrl = cat + "/p" + p;
        try {
          String content = fetchPage(pageUrl);
          Matcher matcher =
              Pattern.compile("href=\"(https://forums\\.synthstrom\\.com/discussion/[^\"]+)\"")
                  .matcher(content);
          while (matcher.find()) {
            String link = matcher.group(1);
            if (!link.endsWith("/p1") && !link.matches(".*/p\\d+$")) {
              allDiscussions.add(link);
            }
          }
        } catch (Exception e) {
          System.err.println("Error crawling " + pageUrl + ": " + e.getMessage());
        }
      }
    }

    for (String url : allDiscussions) {
      processDiscussion(url);
      Thread.sleep(1000); // Be nice to the server
    }
  }

  private static void processDiscussion(String url) {
    System.out.println("Processing Discussion: " + url);
    try {
      String content = fetchPage(url);
      String title = extractTitle(content, url);
      String dirName = title.replaceAll("[^\\w\\s-]", "").trim().replace(" ", "_");
      Path discussionDir = BASE_DIR.resolve(dirName);

      Matcher matcher = Pattern.compile("href=\"([^\"]+)\"").matcher(content);
      List<String> foundFiles = new ArrayList<>();
      Set<String> seenLinks = new HashSet<>();

      while (matcher.find()) {
        String link = matcher.group(1);
        if (!seenLinks.add(link)) continue;

        if (link.toLowerCase().matches(".*\\.(zip|rar|7z|xml).*")) {
          if (link.contains("uploads/editor") || link.matches(".*\\.(zip|rar|7z|xml)$")) {
            if (!link.startsWith("http")) link = "https://forums.synthstrom.com" + link;
            ensureDir(discussionDir);
            String filename = link.substring(link.lastIndexOf('/') + 1);
            if (downloadDirectFile(link, discussionDir.resolve(filename))) {
              foundFiles.add(filename);
            }
          }
        } else if (link.contains("dropbox.com/s/")) {
          ensureDir(discussionDir);
          String directLink = link.replace("?dl=0", "").replace("?dl=1", "") + "?dl=1";
          String filename = directLink.substring(directLink.lastIndexOf('/') + 1).split("\\?")[0];
          if (downloadDirectFile(directLink, discussionDir.resolve(filename))) {
            foundFiles.add(filename);
          }
        } else if (link.contains("drive.google.com/file/d/")
            || link.contains("drive.google.com/open?id=")) {
          String fileId = extractGDriveId(link);
          if (fileId != null) {
            ensureDir(discussionDir);
            if (downloadGDriveFile(fileId, discussionDir, foundFiles)) {
              // filename added inside helper
            }
          }
        }
      }

      logResults(title, url, foundFiles);

    } catch (Exception e) {
      System.err.println("Error processing " + url + ": " + e.getMessage());
    }
  }

  private static String fetchPage(String url) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "Mozilla/5.0").build();
    return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
  }

  private static String extractTitle(String content, String fallback) {
    Matcher matcher = Pattern.compile("<title>(.*?)</title>").matcher(content);
    if (matcher.find()) {
      return matcher.group(1).split(" — ")[0];
    }
    return fallback.substring(fallback.lastIndexOf('/') + 1);
  }

  private static void ensureDir(Path path) throws IOException {
    if (!Files.exists(path)) {
      Files.createDirectories(path);
    }
  }

  private static boolean downloadDirectFile(String url, Path path) {
    if (Files.exists(path)) return true;
    System.out.println(" -> Downloading to " + path + "...");
    try {
      HttpRequest request =
          HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "Mozilla/5.0").build();
      HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(path));
      return response.statusCode() == 200;
    } catch (Exception e) {
      System.err.println("    Download error: " + e.getMessage());
      return false;
    }
  }

  private static String extractGDriveId(String url) {
    Matcher m1 = Pattern.compile("/d/([^/]+)").matcher(url);
    if (m1.find()) return m1.group(1);
    Matcher m2 = Pattern.compile("id=([^&]+)").matcher(url);
    if (m2.find()) return m2.group(1);
    return null;
  }

  private static boolean downloadGDriveFile(
      String fileId, Path discussionDir, List<String> foundFiles) {
    String dUrl = "https://drive.google.com/uc?export=download&id=" + fileId;
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(dUrl))
              .header("User-Agent", "Mozilla/5.0")
              .build();
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());

      String confirmToken = null;

      // 1. Check cookies for download_warning
      List<String> cookies = response.headers().allValues("Set-Cookie");
      for (String cookie : cookies) {
        if (cookie.contains("download_warning")) {
          Matcher m = Pattern.compile("download_warning[^=]*=([^;]+)").matcher(cookie);
          if (m.find()) confirmToken = m.group(1);
        }
      }

      // 2. Check the body for confirm token if it's HTML
      byte[] firstBytes = new byte[8192];
      int read = 0;
      try (InputStream is = response.body()) {
        read = is.read(firstBytes);
      }

      if (read > 0) {
        String bodyPart = new String(firstBytes, 0, read);
        if (bodyPart.contains("confirm=")) {
          Matcher m = Pattern.compile("confirm=([^&\"]+)").matcher(bodyPart);
          if (m.find()) confirmToken = m.group(1);
        } else if (bodyPart.contains("name=\"confirm\" value=\"([^\"]+)\"")) {
          Matcher m = Pattern.compile("name=\"confirm\" value=\"([^\"]+)\"").matcher(bodyPart);
          if (m.find()) confirmToken = m.group(1);
        }

        if (confirmToken != null || bodyPart.contains("Google Drive - Virus scan warning")) {
          String finalUrl =
              dUrl + (confirmToken != null ? "&confirm=" + confirmToken : "&confirm=t");
          request =
              HttpRequest.newBuilder()
                  .uri(URI.create(finalUrl))
                  .header("User-Agent", "Mozilla/5.0")
                  .build();
          response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

          // Re-read first bytes to verify it's a zip (PK..)
          byte[] zipHeader = new byte[4];
          try (InputStream is = response.body()) {
            // We need to write the rest to a file if it is a zip
            // Actually, let's just use BodyHandlers.ofFile for the final attempt
          }
          // Restarting request for BodyHandlers.ofFile
          response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } else if (bodyPart.startsWith("PK")) {
          // It's already the zip file! We need to write what we read + the rest
          String filename = "gdrive_" + fileId + ".zip";
          Optional<String> cd = response.headers().firstValue("Content-Disposition");
          if (cd.isPresent()) {
            Matcher m = Pattern.compile("filename=\"(.+?)\"").matcher(cd.get());
            if (m.find()) filename = m.group(1);
          }
          Path path = discussionDir.resolve(filename);
          System.out.println(" -> Saving GDrive file to " + path + "...");
          try (OutputStream os = new FileOutputStream(path.toFile())) {
            os.write(firstBytes, 0, read);
            // We need the rest of the stream, but the first response.body() is closed.
            // This is why we should probably use a better stream handling or just re-request.
          }
          // Re-requesting to get a clean stream is safer
          request =
              HttpRequest.newBuilder()
                  .uri(URI.create(dUrl))
                  .header("User-Agent", "Mozilla/5.0")
                  .build();
          client.send(request, HttpResponse.BodyHandlers.ofFile(path));
          foundFiles.add(filename);
          return true;
        }
      }

      // Final attempt with whatever token we found
      if (confirmToken != null) {
        dUrl += "&confirm=" + confirmToken;
      }
      request =
          HttpRequest.newBuilder()
              .uri(URI.create(dUrl))
              .header("User-Agent", "Mozilla/5.0")
              .build();

      String filename = "gdrive_" + fileId + ".zip";
      // Get headers first
      HttpResponse<Void> headResponse =
          client.send(request, HttpResponse.BodyHandlers.discarding());
      Optional<String> cd = headResponse.headers().firstValue("Content-Disposition");
      if (cd.isPresent()) {
        Matcher m = Pattern.compile("filename=\"(.+?)\"").matcher(cd.get());
        if (m.find()) filename = m.group(1);
      }

      Path path = discussionDir.resolve(filename);
      if (!Files.exists(path)) {
        System.out.println(" -> Downloading GDrive file to " + path + "...");
        HttpResponse<Path> fileResponse =
            client.send(request, HttpResponse.BodyHandlers.ofFile(path));
        if (fileResponse.statusCode() == 200) {
          // Verify it's not HTML
          if (Files.size(path) > 100) {
            try (InputStream is = new FileInputStream(path.toFile())) {
              byte[] header = new byte[2];
              is.read(header);
              if (header[0] == 'P' && header[1] == 'K') {
                foundFiles.add(filename);
                return true;
              } else {
                Files.delete(path);
              }
            }
          }
        }
      }
    } catch (Exception e) {
      System.err.println("    GDrive error: " + e.getMessage());
    }
    return false;
  }

  private static void initializeLog() throws IOException {
    ensureDir(BASE_DIR);
    if (!Files.exists(LOG_FILE)) {
      try (PrintWriter out = new PrintWriter(new FileWriter(LOG_FILE.toFile()))) {
        out.println("# Deluge Download Log\n");
        out.println("| Title | URL | Status | Files |");
        out.println("|---|---|---|---|");
      }
    }
  }

  private static void logResults(String title, String url, List<String> files) throws IOException {
    try (PrintWriter out = new PrintWriter(new FileWriter(LOG_FILE.toFile(), true))) {
      String status = files.isEmpty() ? "No automated files" : "Downloaded";
      String filesStr = String.join(", ", files);
      out.printf("| %s | %s | %s | %s |\n", title, url, status, filesStr);
    }
  }
}
