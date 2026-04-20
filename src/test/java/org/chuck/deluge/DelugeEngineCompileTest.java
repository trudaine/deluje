package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.chuck.compiler.ChuckANTLRLexer;
import org.chuck.compiler.ChuckANTLRParser;
import org.chuck.compiler.ChuckAST;
import org.chuck.compiler.ChuckASTVisitor;
import org.chuck.compiler.ChuckToDSLConverter;
import org.junit.jupiter.api.Test;

public class DelugeEngineCompileTest {

  @Test
  public void testCompileDelugeEngine() throws Exception {
    File root = new File(".").getAbsoluteFile();
    while (root != null && !new File(root, "deluge").exists()) {
      root = root.getParentFile();
    }
    File ckFile = new File(root, "deluge/src/main/resources/org/chuck/deluge/engine.ck");
    String source = Files.readString(ckFile.toPath());

    var input = CharStreams.fromString(source);
    var lexer = new ChuckANTLRLexer(input);
    var tokens = new CommonTokenStream(lexer);
    var parser = new ChuckANTLRParser(tokens);

    var visitor = new ChuckASTVisitor(tokens);
    @SuppressWarnings("unchecked")
    List<ChuckAST.Stmt> ast = (List<ChuckAST.Stmt>) visitor.visit(parser.program());

    var converter = new ChuckToDSLConverter();
    String javaCode = converter.convert(ast, "DelugeEngineDSL");

    File targetDir = new File(root, "deluge/target/generated-test-sources");
    if (!targetDir.exists()) targetDir.mkdirs();
    File javaFile = new File(targetDir, "DelugeEngineDSL.java");
    Files.writeString(javaFile.toPath(), javaCode);

    System.out.println("Generated Java code in " + javaFile.getAbsolutePath());

    // Try to compile it
    String classpath = System.getProperty("java.class.path");
    ProcessBuilder pb =
        new ProcessBuilder(
            "javac",
            "--enable-preview",
            "--release",
            "25",
            "--add-modules",
            "jdk.incubator.vector",
            "-cp",
            classpath,
            "-d",
            "deluge/target",
            javaFile.getAbsolutePath());
    pb.inheritIO();
    Process p = pb.start();
    int exitCode = p.waitFor();

    assertEquals(0, exitCode, "Generated Java code failed to compile!");
  }
}
