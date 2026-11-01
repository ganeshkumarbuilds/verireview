package com.verireview.generation;

import com.verireview.agent.dto.AiGenerationFilesResult;
import com.verireview.generation.GenerationBackend;
import com.verireview.generation.GenerationDatabase;
import com.verireview.generation.GenerationFrontend;
import com.verireview.review.FindingSeverity;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import com.sun.source.util.JavacTask;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses generated source without loading or executing it and runs conservative
 * source-level checks that are safe to perform in the application process.
 */
@Component
public class GenerationStaticAnalyzer {

  private static final Pattern HARDCODED_SECRET = Pattern.compile(
      "(?i)(api[_-]?key|password|secret|token)\\s*[:=]\\s*['\"][^'\"\\s]{8,}['\"]");
  private static final Pattern PYTHON_ASSIGNMENT_CONDITION =
      Pattern.compile("^\\s*if\\s+[A-Za-z_][\\w.]*\\s*=\\s*[^=].*:");

  private final ObjectMapper objects;
  private final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

  public GenerationStaticAnalyzer(ObjectMapper objects) {
    this.objects = objects;
  }

  public List<StaticFinding> analyze(List<AiGenerationFilesResult.GeneratedFile> files) {
    List<StaticFinding> findings = new ArrayList<>();
    for (AiGenerationFilesResult.GeneratedFile file : files) {
      String path = file.path();
      String content = file.content();
      String extension = extension(path);
      switch (extension) {
        case "java" -> parseJava(path, content, findings);
        case "json" -> parseJson(path, content, findings);
        case "yaml", "yml" -> parseYaml(path, content, findings);
        case "ts", "tsx", "js", "jsx", "py" ->
            checkBalancedSyntax(path, content, extension, findings);
        default -> {
        }
      }
      if ("py".equals(extension)) {
        checkPythonIndentation(path, content, findings);
      } else if ("jsx".equals(extension) || "tsx".equals(extension)) {
        checkJsxTags(path, content, findings);
      }
      checkSourceRules(path, content, extension, findings);
    }
    return List.copyOf(findings);
  }

  public List<StaticFinding> analyzeProjectContract(
      List<AiGenerationFilesResult.GeneratedFile> files,
      GenerationBackend backend,
      GenerationFrontend frontend,
      GenerationDatabase database) {
    Map<String, String> byPath = new LinkedHashMap<>();
    files.forEach(file -> byPath.put(file.path(), file.content()));
    List<StaticFinding> findings = new ArrayList<>();
    for (String required : List.of(
        "README.md", ".gitignore", ".env.example", "Dockerfile", "docker-compose.yml", "LICENSE")) {
      if (!byPath.containsKey(required)) {
        findings.add(contractFinding(required, 1, "Required project file is missing."));
      }
    }
    if (backend != GenerationBackend.NONE || frontend != GenerationFrontend.NONE) {
      for (String script : List.of("scripts/run.sh", "scripts/test.sh")) {
        if (!byPath.containsKey(script)) {
          findings.add(contractFinding(script, 1, "The selected stack requires the standard helper script."));
        }
      }
    }
    String readme = byPath.get("README.md");
    if (readme != null) {
      for (String section : List.of("install", "run", "test", "build", "environment", "structure", "troubleshoot")) {
        if (!readme.toLowerCase(Locale.ROOT).contains(section)) {
          findings.add(contractFinding("README.md", 1,
              "README is missing the " + section + " instructions required by the generated-project contract."));
        }
      }
    }
    String dockerfile = byPath.get("Dockerfile");
    if (dockerfile != null) {
      if (!dockerfile.contains("USER ")) {
        findings.add(contractFinding("Dockerfile", 1,
            "Dockerfile does not declare a non-root USER."));
      }
      if (!dockerfile.toUpperCase(Locale.ROOT).contains("HEALTHCHECK")) {
        findings.add(contractFinding("Dockerfile", 1,
            "Dockerfile does not declare a HEALTHCHECK."));
      }
    }
    String compose = byPath.get("docker-compose.yml");
    if (compose != null) {
      if (!compose.contains("env_file") && !compose.contains("${")) {
        findings.add(contractFinding("docker-compose.yml", 1,
            "Compose does not use environment variables for application configuration."));
      }
      if (database != GenerationDatabase.NONE && !compose.contains("database:")) {
        findings.add(contractFinding("docker-compose.yml", 1,
            "A database was selected but docker-compose.yml has no database service."));
      }
    }
    return List.copyOf(findings);
  }

  private static StaticFinding contractFinding(String file, int line, String explanation) {
    return new StaticFinding(
        file, line, "style", FindingSeverity.MEDIUM, "Project contract mismatch", explanation);
  }

  private void parseJava(String path, String content, List<StaticFinding> findings) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      findings.add(new StaticFinding(path, 1, "syntax", FindingSeverity.INFO,
          "Java syntax check unavailable", "The Java parser is not available in this runtime."));
      return;
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    JavaFileObject source = new SimpleJavaFileObject(
        URI.create("string:///" + path.replace(" ", "%20")), JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return content;
      }
    };
    try {
      JavacTask task = (JavacTask) compiler.getTask(
          null, null, diagnostics, List.of("-proc:none"), null, List.of(source));
      task.parse();
    } catch (IOException | RuntimeException e) {
      addSyntax(path, 1, "Java source could not be parsed.", findings);
      return;
    }
    diagnostics.getDiagnostics().stream()
        .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
        .forEach(diagnostic -> addSyntax(
            path,
            Math.max(1, (int) diagnostic.getLineNumber()),
            "Java syntax error: " + plainMessage(diagnostic.getMessage(Locale.ROOT)),
            findings));
  }

  private void parseJson(String path, String content, List<StaticFinding> findings) {
    try {
      objects.readTree(content);
    } catch (Exception e) {
      addSyntax(path, lineFromMessage(e.getMessage()), "JSON is not valid.", findings);
    }
  }

  private void parseYaml(String path, String content, List<StaticFinding> findings) {
    try {
      yaml.load(content);
    } catch (Exception e) {
      addSyntax(path, lineFromMessage(e.getMessage()), "YAML is not valid.", findings);
    }
  }

  private void checkBalancedSyntax(
      String path, String content, String extension, List<StaticFinding> findings) {
    List<Character> stack = new ArrayList<>();
    boolean single = false;
    boolean doub = false;
    boolean template = false;
    boolean escaped = false;
    boolean commentLine = false;
    int line = 1;
    for (int i = 0; i < content.length(); i++) {
      char current = content.charAt(i);
      char next = i + 1 < content.length() ? content.charAt(i + 1) : '\0';
      if (current == '\n') {
        line++;
        commentLine = false;
        if ("py".equals(extension) && !stack.isEmpty() && i == content.length() - 1) {
          addSyntax(path, line, "Python expression has unclosed delimiters.", findings);
        }
        continue;
      }
      if (commentLine) continue;
      if (!single && !doub && !template && current == '/' && next == '/') {
        commentLine = true;
        i++;
        continue;
      }
      if ("py".equals(extension) && !single && !doub && current == '#') {
        commentLine = true;
        continue;
      }
      if (escaped) {
        escaped = false;
        continue;
      }
      if ((single || doub || template) && current == '\\') {
        escaped = true;
        continue;
      }
      if (!doub && !template && current == '\'') {
        single = !single;
        continue;
      }
      if (!single && !template && current == '"') {
        doub = !doub;
        continue;
      }
      if (!single && !doub && current == '`' && !"py".equals(extension)) {
        template = !template;
        continue;
      }
      if (single || doub || template) continue;
      if (current == '(' || current == '[' || current == '{') {
        stack.add(current);
      } else if (current == ')' || current == ']' || current == '}') {
        char expected = current == ')' ? '(' : current == ']' ? '[' : '{';
        if (stack.isEmpty() || stack.remove(stack.size() - 1) != expected) {
          addSyntax(path, line, "Source has an unmatched closing delimiter.", findings);
          return;
        }
      }
    }
    if (!stack.isEmpty() || single || doub || template) {
      addSyntax(path, line, "Source has an unclosed delimiter or string.", findings);
    }
  }

  private void checkPythonIndentation(
      String path, String content, List<StaticFinding> findings) {
    String[] lines = content.split("\\R", -1);
    List<Integer> indents = new ArrayList<>(List.of(0));
    int pendingBlockIndent = -1;
    int pendingBlockLine = -1;

    for (int index = 0; index < lines.length; index++) {
      String raw = lines[index];
      String code = pythonCodeWithoutComment(raw);
      if (code.isBlank()) {
        continue;
      }
      int line = index + 1;
      int indent = indentationWidth(raw);

      if (pendingBlockIndent >= 0) {
        if (indent <= pendingBlockIndent) {
          addSyntax(path, line, "Expected an indented block after line "
              + pendingBlockLine + ".", findings);
          pendingBlockIndent = -1;
        } else {
          indents.add(indent);
          pendingBlockIndent = -1;
        }
      }

      int current = indents.get(indents.size() - 1);
      if (indent > current) {
        if (indents.size() == 1) {
          addSyntax(path, line, "Unexpected indent.", findings);
        } else if (pendingBlockIndent < 0 && !indents.contains(indent)) {
          addSyntax(path, line, "Unexpected indent.", findings);
        }
        if (!indents.contains(indent)) {
          indents.add(indent);
        }
      } else if (indent < current) {
        while (indents.size() > 1 && indent < indents.get(indents.size() - 1)) {
          indents.remove(indents.size() - 1);
        }
        if (indent != indents.get(indents.size() - 1)) {
          addSyntax(path, line, "Inconsistent dedent.", findings);
          indents.add(indent);
        }
      }

      if (code.trim().endsWith(":")) {
        pendingBlockIndent = indent;
        pendingBlockLine = line;
      }
    }

    if (pendingBlockIndent >= 0) {
      addSyntax(path, lines.length, "Expected an indented block after line "
          + pendingBlockLine + ".", findings);
    }
  }

  private void checkJsxTags(
      String path, String content, List<StaticFinding> findings) {
    List<JsxTag> tags = new ArrayList<>();
    int line = 1;
    for (int i = 0; i < content.length(); i++) {
      char current = content.charAt(i);
      if (current == '\n') {
        line++;
        continue;
      }
      if (current == '/' && i + 1 < content.length() && content.charAt(i + 1) == '/') {
        i = skipToLineEnd(content, i + 2);
        continue;
      }
      if (current == '/' && i + 1 < content.length() && content.charAt(i + 1) == '*') {
        i = skipBlockComment(content, i + 2, line);
        line += countNewlines(content, i + 1, Math.min(content.length(), i + 1));
        continue;
      }
      if (current == '\'' || current == '"' || current == '`') {
        i = skipQuoted(content, i, current);
        continue;
      }
      if (current == '{' && !tags.isEmpty()) {
        i = skipJsxExpression(content, i, line);
        continue;
      }
      if (current != '<' || !isLikelyJsxOpen(content, i)) {
        continue;
      }

      boolean closing = i + 1 < content.length() && content.charAt(i + 1) == '/';
      int nameStart = closing ? i + 2 : i + 1;
      String name;
      if (nameStart < content.length() && content.charAt(nameStart) == '>') {
        name = "";
      } else {
        int nameEnd = nameStart;
        while (nameEnd < content.length() && isJsxNameChar(content.charAt(nameEnd))) {
          nameEnd++;
        }
        if (nameEnd == nameStart) {
          continue;
        }
        if (!closing && nameEnd < content.length() && content.charAt(nameEnd) == ',') {
          continue;
        }
        name = content.substring(nameStart, nameEnd);
      }
      int tagEnd = skipTag(content, closing ? nameStart : nameStart, line);
      if (tagEnd < 0) {
        addSyntax(path, line, "JSX tag is not closed.", findings);
        return;
      }
      boolean selfClosing = !closing && content.charAt(tagEnd - 1) == '/';
      if (closing) {
        if (tags.isEmpty() || !tags.get(tags.size() - 1).name().equals(name)) {
          String expected = tags.isEmpty() ? "none" : tags.get(tags.size() - 1).name();
          addSyntax(path, line, "JSX closing tag does not match opening tag <"
              + expected + ">.", findings);
          return;
        }
        tags.remove(tags.size() - 1);
      } else if (!selfClosing) {
        tags.add(new JsxTag(name, line));
      }
      i = tagEnd;
    }
    if (!tags.isEmpty()) {
      JsxTag tag = tags.get(tags.size() - 1);
      addSyntax(path, tag.line(), "JSX tag <" + tag.name() + "> is not closed.", findings);
    }
  }

  private static String pythonCodeWithoutComment(String line) {
    boolean single = false;
    boolean doub = false;
    boolean escaped = false;
    for (int i = 0; i < line.length(); i++) {
      char current = line.charAt(i);
      if (escaped) {
        escaped = false;
      } else if ((single || doub) && current == '\\') {
        escaped = true;
      } else if (!doub && current == '\'') {
        single = !single;
      } else if (!single && current == '"') {
        doub = !doub;
      } else if (!single && !doub && current == '#') {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static int indentationWidth(String line) {
    int width = 0;
    for (int i = 0; i < line.length(); i++) {
      if (line.charAt(i) == ' ') {
        width++;
      } else if (line.charAt(i) == '\t') {
        width = ((width / 8) + 1) * 8;
      } else {
        break;
      }
    }
    return width;
  }

  private static boolean isLikelyJsxOpen(String content, int index) {
    if (index + 1 >= content.length()) return false;
    char next = content.charAt(index + 1);
    if (next == '/' || next == '>') return true;
    if (!Character.isLetter(next)) return false;
    int previous = index - 1;
    while (previous >= 0 && Character.isWhitespace(content.charAt(previous))) previous--;
    if (previous < 0) return true;
    char before = content.charAt(previous);
    if ("=({[,;:>}".indexOf(before) >= 0) return true;
    int wordEnd = previous + 1;
    while (previous >= 0 && Character.isLetter(content.charAt(previous))) previous--;
    String word = content.substring(previous + 1, wordEnd);
    return "return".equals(word) || "yield".equals(word);
  }

  private static int skipTag(String content, int start, int line) {
    char quote = 0;
    int braces = 0;
    for (int i = start; i < content.length(); i++) {
      char current = content.charAt(i);
      if (quote != 0) {
        if (current == '\\') i++;
        else if (current == quote) quote = 0;
      } else if (current == '\'' || current == '"') {
        quote = current;
      } else if (current == '{') {
        braces++;
      } else if (current == '}' && braces > 0) {
        braces--;
      } else if (current == '>' && braces == 0) {
        return i;
      }
    }
    return -1;
  }

  private static int skipJsxExpression(String content, int start, int line) {
    int depth = 1;
    for (int i = start + 1; i < content.length(); i++) {
      char current = content.charAt(i);
      if (current == '\'' || current == '"' || current == '`') {
        i = skipQuoted(content, i, current);
      } else if (current == '{') {
        depth++;
      } else if (current == '}' && --depth == 0) {
        return i;
      }
    }
    return content.length() - 1;
  }

  private static int skipQuoted(String content, int start, char quote) {
    for (int i = start + 1; i < content.length(); i++) {
      if (content.charAt(i) == '\\') i++;
      else if (content.charAt(i) == quote) return i;
    }
    return content.length() - 1;
  }

  private static int skipToLineEnd(String content, int start) {
    int end = content.indexOf('\n', start);
    return end < 0 ? content.length() - 1 : end - 1;
  }

  private static int skipBlockComment(String content, int start, int line) {
    int end = content.indexOf("*/", start);
    return end < 0 ? content.length() - 1 : end + 1;
  }

  private static int countNewlines(String content, int start, int end) {
    int count = 0;
    for (int i = start; i < end; i++) if (content.charAt(i) == '\n') count++;
    return count;
  }

  private static boolean isJsxNameChar(char value) {
    return Character.isLetterOrDigit(value) || value == '_' || value == '-'
        || value == ':' || value == '.';
  }

  private record JsxTag(String name, int line) {
  }

  private void checkSourceRules(
      String path, String content, String extension, List<StaticFinding> findings) {
    String[] lines = content.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (HARDCODED_SECRET.matcher(line).find()) {
        findings.add(new StaticFinding(path, i + 1, "security", FindingSeverity.HIGH,
            "Possible hard-coded credential",
            "Move this credential to a protected environment variable or secret store."));
      }
      if ("py".equals(extension) && PYTHON_ASSIGNMENT_CONDITION.matcher(line).find()) {
        findings.add(new StaticFinding(path, i + 1, "logic", FindingSeverity.MEDIUM,
            "Assignment used in a condition",
            "This Python condition appears to use assignment where a comparison is expected."));
      }
      if (!line.isEmpty() && Character.isWhitespace(line.charAt(line.length() - 1))) {
        findings.add(new StaticFinding(path, i + 1, "style", FindingSeverity.LOW,
            "Trailing whitespace",
            "Remove whitespace at the end of this line."));
      }
      if (line.contains("dangerouslySetInnerHTML")) {
        findings.add(new StaticFinding(path, i + 1, "security", FindingSeverity.HIGH,
            "Unsafe HTML rendering",
            "Avoid rendering untrusted HTML directly; use escaped text or sanitize trusted input."));
      }
    }
  }

  private static void addSyntax(
      String path, int line, String message, List<StaticFinding> findings) {
    findings.add(new StaticFinding(path, Math.max(1, line), "syntax", FindingSeverity.HIGH,
        "Syntax error", message));
  }

  private static String extension(String path) {
    int dot = path == null ? -1 : path.lastIndexOf('.');
    return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static int lineFromMessage(String message) {
    if (message == null) return 1;
    Matcher matcher = Pattern.compile("(?:line|Line)\\s+(\\d+)").matcher(message);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : 1;
  }

  private static String plainMessage(String message) {
    if (message == null || message.isBlank()) return "Source could not be parsed.";
    return message.replaceAll("\\s+", " ").trim();
  }

  public record StaticFinding(
      String file,
      int line,
      String category,
      FindingSeverity severity,
      String title,
      String explanation) {
  }
}
