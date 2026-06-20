package org.deluge.xml2;

import java.io.IOException;
import java.io.Writer;

/**
 * A literal, line-by-line Java port of the Synthstrom Deluge firmware's C++ XMLSerializer.
 * Writes formatted XML character streams directly to a java.io.Writer with perfect tab-indent
 * and newline parity.
 */
public class XMLSerializer {
  private final Writer writer;
  private int indentAmount = 0;

  public XMLSerializer(Writer writer) {
    this.writer = writer;
  }

  public void reset() {
    this.indentAmount = 0;
  }

  public void write(String output) throws IOException {
    writer.write(output);
  }

  public void printIndents() throws IOException {
    for (int i = 0; i < indentAmount; i++) {
      writer.write("\t");
    }
  }

  public void writeOpeningTagBeginning(String tag) throws IOException {
    writeOpeningTagBeginning(tag, true);
  }

  public void writeOpeningTagBeginning(String tag, boolean newLineBefore) throws IOException {
    if (newLineBefore) {
      // Typically C++ prints indents before opening tag
    }
    printIndents();
    write("<");
    write(tag);
    indentAmount++;
  }

  public void writeOpeningTagEnd() throws IOException {
    writeOpeningTagEnd(true);
  }

  public void writeOpeningTagEnd(boolean startNewLineAfter) throws IOException {
    if (startNewLineAfter) {
      write(">\n");
    } else {
      write(">");
    }
  }

  public void writeOpeningTag(String tag) throws IOException {
    writeOpeningTag(tag, true);
  }

  public void writeOpeningTag(String tag, boolean startNewLineAfter) throws IOException {
    writeOpeningTagBeginning(tag);
    writeOpeningTagEnd(startNewLineAfter);
  }

  public void closeTag() throws IOException {
    write(" /");
    writeOpeningTagEnd(true);
    indentAmount--;
  }

  public void writeClosingTag(String tag) throws IOException {
    writeClosingTag(tag, true);
  }

  public void writeClosingTag(String tag, boolean shouldPrintIndents) throws IOException {
    indentAmount--;
    if (shouldPrintIndents) {
      printIndents();
    }
    write("</");
    write(tag);
    write(">\n");
  }

  public void writeAttribute(String name, String value) throws IOException {
    writeAttribute(name, value, true);
  }

  public void writeAttribute(String name, String value, boolean onNewLine) throws IOException {
    if (onNewLine) {
      write("\n");
      printIndents();
    } else {
      write(" ");
    }
    write(name);
    write("=\"");
    write(value);
    write("\"");
  }

  public void writeAttribute(String name, int number) throws IOException {
    writeAttribute(name, number, true);
  }

  public void writeAttribute(String name, int number, boolean onNewLine) throws IOException {
    writeAttribute(name, String.valueOf(number), onNewLine);
  }

  public void writeAttributeHex(String name, int number, int numChars) throws IOException {
    writeAttributeHex(name, number, numChars, true);
  }

  public void writeAttributeHex(String name, int number, int numChars, boolean onNewLine) throws IOException {
    String hex = String.format("%0" + numChars + "X", number);
    writeAttribute(name, "0x" + hex, onNewLine);
  }

  public void writeAttributeHexBytes(String name, byte[] data, int numBytes) throws IOException {
    writeAttributeHexBytes(name, data, numBytes, true);
  }

  public void writeAttributeHexBytes(String name, byte[] data, int numBytes, boolean onNewLine) throws IOException {
    if (onNewLine) {
      write("\n");
      printIndents();
    } else {
      write(" ");
    }
    write(name);
    write("=\"0x");
    for (int i = 0; i < numBytes; i++) {
      write(String.format("%02X", data[i]));
    }
    write("\"");
  }

  public void writeArrayStart(String tag) throws IOException {
    printIndents();
    write("<");
    write(tag);
    write(">\n");
    indentAmount++;
  }

  public void writeArrayEnding(String tag) throws IOException {
    indentAmount--;
    printIndents();
    write("</");
    write(tag);
    write(">\n");
  }

  public void writeTag(String tag, String contents) throws IOException {
    printIndents();
    write("<");
    write(tag);
    write(">");
    write(contents);
    write("</");
    write(tag);
    write(">\n");
  }

  public void writeTag(String tag, int number) throws IOException {
    writeTag(tag, String.valueOf(number));
  }

  public void flush() throws IOException {
    writer.flush();
  }
}
