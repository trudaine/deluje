package org.deluge.ui;

import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

/**
 * A beautiful, spacious, and fully resizeable dark JDialog operations manual viewer. Loads the
 * embedded DELUGE_GUIDEBOOK.md from the classpath, converts Markdown syntax to compatible HTML
 * line-by-line natively, and displays it with high-contrast styles and dynamic JAR image loading.
 */
public class SwingHelpDialog extends JDialog {

  public SwingHelpDialog(Frame parent) {
    super(parent, "ChucK-Java Deluge Workstation — Operations Manual", false);
    setSize(920, 750);
    setMinimumSize(new Dimension(640, 500));
    setLocationRelativeTo(parent);
    setResizable(true);
    setLayout(new BorderLayout());

    // Main scrollable HTML Editor Pane
    JEditorPane editor = new JEditorPane();
    editor.setEditable(false);
    editor.setContentType("text/html");
    editor.setBackground(new Color(0x12, 0x12, 0x14));

    // Custom dark-neon styling for standard HTML tags
    HTMLEditorKit kit = new HTMLEditorKit();
    editor.setEditorKit(kit);

    String stylesheet =
        "body { color: #d2d2d8; font-family: sans-serif; font-size: 12px; margin: 15px; background-color: #121214; }"
            + "h1 { color: #ffb300; font-size: 18px; margin-top: 20px; border-bottom: 1px solid #2d2d35; padding-bottom: 4px; }"
            + "h2 { color: #00e676; font-size: 15px; margin-top: 18px; border-bottom: 1px dashed #2d2d35; padding-bottom: 2px; }"
            + "h3 { color: #00b0ff; font-size: 13px; margin-top: 14px; }"
            + "p { line-height: 1.5; margin-bottom: 10px; }"
            + "code { font-family: monospace; color: #00ffcc; background-color: #1e1e22; padding: 2px 4px; }"
            + "pre { font-family: monospace; color: #a2a2ab; background-color: #1a1a1e; border: 1px solid #2d2d32; padding: 10px; margin-bottom: 12px; }"
            + "li { margin-bottom: 4px; line-height: 1.4; }"
            + "table { border-collapse: collapse; width: 100%; margin-top: 8px; margin-bottom: 15px; background-color: #1a1a1e; }"
            + "th { background-color: #2d2d32; color: #ffb300; font-weight: bold; text-align: left; padding: 6px 8px; border: 1px solid #3d3d45; }"
            + "td { padding: 6px 8px; border: 1px solid #2d2d32; }"
            + "hr { border: 0; height: 1px; background-color: #2d2d35; margin-top: 20px; margin-bottom: 20px; }";
    kit.getStyleSheet().addRule(stylesheet);

    // Dynamic Classpath Base URL configuration to resolve relative images!
    URL base = SwingHelpDialog.class.getResource("/docs/");
    if (base == null) {
      try {
        // Dev CWD file fallback
        File devDir = new File("deluge/src/main/resources/docs/");
        if (!devDir.exists()) devDir = new File("src/main/resources/docs/");
        base = devDir.toURI().toURL();
      } catch (Exception ex) {
        System.err.println("[HelpDialog] Dev base URL fallback failed: " + ex.getMessage());
      }
    }
    if (base != null) {
      HTMLDocument doc = (HTMLDocument) editor.getDocument();
      doc.setBase(base);
    }

    // Load and convert the guidebook Markdown stream
    try {
      String mdText = loadGuidebookContent();
      String htmlText = convertMarkdownToHtml(mdText);
      editor.setText(htmlText);

      // Force scroll bar position to the very top initially!
      SwingUtilities.invokeLater(() -> editor.setCaretPosition(0));
    } catch (Exception ex) {
      editor.setText(
          "<html><body><h2>⚠️ FAILED TO LOAD GUIDEBOOK RESOURCE</h2><p>"
              + ex.getMessage()
              + "</p></body></html>");
    }

    JScrollPane scroll = new JScrollPane(editor);
    scroll.setBorder(null);
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    SwingRandomizerDialog.styleScrollBar(scroll.getVerticalScrollBar());
    SwingRandomizerDialog.styleScrollBar(scroll.getHorizontalScrollBar());
    add(scroll, BorderLayout.CENTER);

    // Close Action row
    JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 8));
    bottomBar.setBackground(new Color(0x1a, 0x1a, 0x1e));
    bottomBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2d, 0x2d, 0x32)));

    JButton closeBtn = new JButton("Close Manual");
    closeBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
    styleButton(closeBtn, new Color(0x2d, 0x2d, 0x32), Color.WHITE);
    closeBtn.addActionListener(e -> dispose());
    bottomBar.add(closeBtn);
    add(bottomBar, BorderLayout.SOUTH);
  }

  private String loadGuidebookContent() throws Exception {
    InputStream is = SwingHelpDialog.class.getResourceAsStream("/docs/DELUGE_GUIDEBOOK.md");
    if (is == null) {
      File devFile = new File("deluge/src/main/resources/docs/DELUGE_GUIDEBOOK.md");
      if (!devFile.exists()) devFile = new File("src/main/resources/docs/DELUGE_GUIDEBOOK.md");
      if (devFile.exists()) {
        is = new FileInputStream(devFile);
      }
    }
    if (is == null) {
      throw new Exception(
          "Guidebook file resource '/docs/DELUGE_GUIDEBOOK.md' not found on the system classpath.");
    }

    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append("\n");
      }
    }
    return sb.toString();
  }

  /**
   * A simple, robust, line-by-line Markdown-to-HTML JEditorPane-compatible parser. Successfully
   * parses GFM list blocks, table columns, code blocks, alerts, bold tags, and images!
   */
  private String convertMarkdownToHtml(String markdown) {
    if (markdown == null) return "";

    String[] lines = markdown.split("\n");
    StringBuilder sb = new StringBuilder();
    sb.append("<html><body>");

    boolean inList = false;
    boolean inCode = false;
    boolean inTable = false;
    boolean inQuote = false;
    List<String> tableHeaders = null;

    for (String line : lines) {
      String trimmed = line.trim();

      // ── Fenced Code Blocks (```) ──
      if (trimmed.startsWith("```")) {
        if (inCode) {
          sb.append("</pre>");
          inCode = false;
        } else {
          sb.append("<pre>");
          inCode = true;
        }
        continue;
      }
      if (inCode) {
        sb.append(escapeHtml(line)).append("\n");
        continue;
      }

      // ── Lists (Unordered - * or -) ──
      if (trimmed.startsWith("* ") || trimmed.startsWith("- ")) {
        if (!inList) {
          sb.append("<ul>");
          inList = true;
        }
        String content = trimmed.substring(2);
        sb.append("<li>").append(parseInlineMarkdown(content)).append("</li>");
        continue;
      } else {
        if (inList) {
          sb.append("</ul>");
          inList = false;
        }
      }

      // ── Blockquotes / Alerts (>) ──
      if (trimmed.startsWith(">")) {
        if (!inQuote) {
          sb.append(
              "<blockquote style='border-left: 4px solid #00b0ff; background-color: #162432; padding: 8px 12px; margin: 10px 0;'>");
          inQuote = true;
        }
        String content = trimmed.substring(1).trim();
        if (content.startsWith("[!NOTE]")) content = "<b>💡 NOTE:</b> " + content.substring(7);
        if (content.startsWith("[!TIP]")) content = "<b>💡 TIP:</b> " + content.substring(6);
        if (content.startsWith("[!IMPORTANT]"))
          content = "<b>⚠️ IMPORTANT:</b> " + content.substring(12);
        if (content.startsWith("[!WARNING]"))
          content = "<b>⚠️ WARNING:</b> " + content.substring(10);

        sb.append("<p style='margin: 0;'>").append(parseInlineMarkdown(content)).append("</p>");
        continue;
      } else {
        if (inQuote) {
          sb.append("</blockquote>");
          inQuote = false;
        }
      }

      // ── Tables (| Col | Col |) ──
      if (trimmed.startsWith("|")) {
        if (trimmed.contains("---")) {
          // Skip divider rows!
          continue;
        }

        String[] parts = trimmed.split("\\|");
        List<String> cols = new ArrayList<>();
        // Strip out trailing/starting split boundaries
        for (String p : parts) {
          String sTrim = p.trim();
          if (!sTrim.isEmpty() || parts[0] != p) {
            cols.add(sTrim);
          }
        }
        if (cols.isEmpty()) continue;
        if (cols.get(0).isEmpty() && cols.size() > 1) {
          cols.remove(0); // strip left empty side split
        }

        if (!inTable) {
          sb.append("<table cellpadding='5' cellspacing='0'>");
          inTable = true;
          tableHeaders = cols;

          sb.append("<tr>");
          for (String header : cols) {
            sb.append("<th>").append(parseInlineMarkdown(header)).append("</th>");
          }
          sb.append("</tr>");
        } else {
          sb.append("<tr>");
          for (int i = 0; i < cols.size(); i++) {
            sb.append("<td>").append(parseInlineMarkdown(cols.get(i))).append("</td>");
          }
          sb.append("</tr>");
        }
        continue;
      } else {
        if (inTable) {
          sb.append("</table>");
          inTable = false;
          tableHeaders = null;
        }
      }

      // ── Headers (#, ##, ###) ──
      if (trimmed.startsWith("# ")) {
        sb.append("<h1>").append(parseInlineMarkdown(trimmed.substring(2))).append("</h1>");
        continue;
      }
      if (trimmed.startsWith("## ")) {
        sb.append("<h2>").append(parseInlineMarkdown(trimmed.substring(3))).append("</h2>");
        continue;
      }
      if (trimmed.startsWith("### ")) {
        sb.append("<h3>").append(parseInlineMarkdown(trimmed.substring(4))).append("</h3>");
        continue;
      }

      // ── Horizontal Rules (---) ──
      if (trimmed.equals("---")) {
        sb.append("<hr />");
        continue;
      }

      // ── Blank Lines / Paragraphs ──
      if (trimmed.isEmpty()) {
        sb.append("<br />");
      } else {
        sb.append("<p>").append(parseInlineMarkdown(line)).append("</p>");
      }
    }

    // Wrap remaining trailing tags state boundaries
    if (inList) sb.append("</ul>");
    if (inTable) sb.append("</table>");
    if (inQuote) sb.append("</blockquote>");

    sb.append("</body></html>");
    return sb.toString();
  }

  private String parseInlineMarkdown(String text) {
    if (text == null) return "";

    // 1. Escaping basic HTML safety tags
    String escaped = escapeHtml(text);

    // 2. Bold / Double asterisks (**text**)
    Pattern boldPattern = Pattern.compile("\\*\\*(.*?)\\*\\*");
    Matcher boldMatcher = boldPattern.matcher(escaped);
    StringBuffer sb = new StringBuffer();
    while (boldMatcher.find()) {
      boldMatcher.appendReplacement(sb, "<b>" + boldMatcher.group(1) + "</b>");
    }
    boldMatcher.appendTail(sb);
    String step1 = sb.toString();

    // 3. Inline Code / Backticks (`code`)
    Pattern codePattern = Pattern.compile("`(.*?)`");
    Matcher codeMatcher = codePattern.matcher(step1);
    sb = new StringBuffer();
    while (codeMatcher.find()) {
      codeMatcher.appendReplacement(sb, "<code>" + codeMatcher.group(1) + "</code>");
    }
    codeMatcher.appendTail(sb);
    String step2 = sb.toString();

    // 4. GFM Images parser: ![alt](path) ➔ <img src="path" alt="alt" align="middle" />
    // We add a center block wrap style for screenshots so they look stunningly premium!
    Pattern imgPattern = Pattern.compile("!\\[(.*?)\\]\\((.*?)\\)");
    Matcher imgMatcher = imgPattern.matcher(step2);
    sb = new StringBuffer();
    while (imgMatcher.find()) {
      String alt = imgMatcher.group(1);
      String src = imgMatcher.group(2);
      imgMatcher.appendReplacement(
          sb,
          "<div align='center' style='margin: 12px 0;'><img src='"
              + src
              + "' alt='"
              + alt
              + "' border='1' style='border-color: #2d2d35;' /></div>");
    }
    imgMatcher.appendTail(sb);
    String step3 = sb.toString();

    // 5. standard GFM links: [text](url) ➔ <a href="url" style="color: #00b0ff;">text</a>
    Pattern linkPattern = Pattern.compile("\\[(.*?)\\]\\((.*?)\\)");
    Matcher linkMatcher = linkPattern.matcher(step3);
    sb = new StringBuffer();
    while (linkMatcher.find()) {
      String linkText = linkMatcher.group(1);
      String url = linkMatcher.group(2);
      linkMatcher.appendReplacement(
          sb,
          "<a href='"
              + url
              + "' style='color: #00b0ff; text-decoration: none;'>"
              + linkText
              + "</a>");
    }
    linkMatcher.appendTail(sb);

    return sb.toString();
  }

  private String escapeHtml(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private void styleButton(AbstractButton btn, Color bg, Color fg) {
    btn.setOpaque(true);
    btn.setBorderPainted(true);
    btn.setContentAreaFilled(true);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFocusable(false);
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.brighter(), 1),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)));
  }
}
