package org.deluge.ableton;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;

/**
 * Handles file reading, writing, Gzip decompression, Gzip compression, and XML Document parsing for
 * Ableton Live Set (.als) files.
 */
public class AbletonProjectManager {

  /**
   * Decompress an Ableton Live Set (.als) file into raw XML text.
   *
   * @param alsFile the source .als file
   * @return the raw decompressed XML string
   * @throws IOException if decompression or stream reading fails
   */
  public static String decompressAls(File alsFile) throws IOException {
    try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(alsFile));
        ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int len;
      while ((len = gis.read(buffer)) > 0) {
        baos.write(buffer, 0, len);
      }
      return baos.toString("UTF-8");
    }
  }

  /**
   * Compress raw XML text into an Ableton Live Set (.als) file.
   *
   * @param xmlContent the raw XML string content
   * @param targetFile the destination .als file
   * @throws IOException if writing or Gzip compression fails
   */
  public static void compressAls(String xmlContent, File targetFile) throws IOException {
    try (GZIPOutputStream gos = new GZIPOutputStream(new FileOutputStream(targetFile))) {
      gos.write(xmlContent.getBytes("UTF-8"));
    }
  }

  /**
   * Decompress and parse an Ableton Live Set (.als) file directly into an XML Document.
   *
   * @param alsFile the source .als file
   * @return the parsed XML Document
   * @throws Exception if decompression or XML parsing fails
   */
  public static Document parseAlsToXml(File alsFile) throws Exception {
    // Decompress directly to bytes, avoiding String→byte[] round-trip memory copy
    byte[] xmlBytes;
    try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(alsFile));
        ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int len;
      while ((len = gis.read(buffer)) > 0) {
        baos.write(buffer, 0, len);
      }
      xmlBytes = baos.toByteArray();
    }
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(new ByteArrayInputStream(xmlBytes));
  }
}
