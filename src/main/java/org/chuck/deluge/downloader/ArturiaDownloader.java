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

public class ArturiaDownloader {
  private static final String ARTURIA_ID = "1B-iAxaenW1NMO9fWgLSik5qSoH8ER20b";
  private static final Path BASE_DIR = Paths.get(System.getProperty("user.home"), "delugedownload");
  private static final Path TARGET_DIR = BASE_DIR.resolve("Arturia_V_Collection_samples");

  private static final HttpClient client =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.ALWAYS)
          .cookieHandler(new CookieManager())
          .build();

  public static void main(String[] args) throws Exception {
    if (!Files.exists(TARGET_DIR)) {
      Files.createDirectories(TARGET_DIR);
    }

    System.out.println("Targeting Arturia V Collection Re-download...");
    if (downloadGDriveFile(ARTURIA_ID, TARGET_DIR)) {
      System.out.println("Download successful. Unzipping...");
      unzipAll(TARGET_DIR);
      System.out.println("Reorganizing...");
      // We can manually call the logic or just run the standardizer again
    } else {
      System.err.println("Failed to download Arturia collection.");
    }
  }

  private static boolean downloadGDriveFile(String fileId, Path destDir) throws Exception {
    String dUrl = "https://drive.google.com/uc?export=download&id=" + fileId;

    // First attempt to get the confirm token
    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create(dUrl)).header("User-Agent", "Mozilla/5.0").build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    String body = response.body();
    String confirmToken = null;

    if (body.contains("confirm=")) {
      Matcher m = Pattern.compile("confirm=([^&\"]+)").matcher(body);
      if (m.find()) confirmToken = m.group(1);
    }

    if (confirmToken == null) {
      List<String> cookies = response.headers().allValues("Set-Cookie");
      for (String cookie : cookies) {
        if (cookie.contains("download_warning")) {
          Matcher m = Pattern.compile("download_warning[^=]*=([^;]+)").matcher(cookie);
          if (m.find()) confirmToken = m.group(1);
        }
      }
    }

    String finalUrl = dUrl + (confirmToken != null ? "&confirm=" + confirmToken : "");
    System.out.println("Final GDrive URL: " + finalUrl);

    Path zipPath = destDir.resolve("arturia_v_collection.zip");
    request =
        HttpRequest.newBuilder()
            .uri(URI.create(finalUrl))
            .header("User-Agent", "Mozilla/5.0")
            .build();

    HttpResponse<Path> fileResponse =
        client.send(request, HttpResponse.BodyHandlers.ofFile(zipPath));

    if (fileResponse.statusCode() == 200 && Files.size(zipPath) > 1000) {
      try (InputStream is = new FileInputStream(zipPath.toFile())) {
        byte[] header = new byte[2];
        if (is.read(header) == 2 && header[0] == 'P' && header[1] == 'K') {
          return true;
        }
      }
    }

    if (Files.exists(zipPath)) Files.delete(zipPath);
    return false;
  }

  private static void unzipAll(Path dir) throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.zip")) {
      for (Path zip : stream) {
        System.out.println("Unzipping " + zip);
        ProcessBuilder pb =
            new ProcessBuilder(
                "powershell.exe",
                "-Command",
                "Expand-Archive -Path '"
                    + zip.toAbsolutePath()
                    + "' -DestinationPath '"
                    + dir.toAbsolutePath()
                    + "' -Force");
        try {
          pb.inheritIO().start().waitFor();
          Files.delete(zip);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }
}
