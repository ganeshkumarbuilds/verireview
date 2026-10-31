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
