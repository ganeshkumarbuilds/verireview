package com.verireview.generation;

import com.verireview.review.Finding;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class GenerationFixScope {

  private GenerationFixScope() {
  }

  static Set<String> affectedFiles(List<Finding> findings, Map<String, String> files) {
    Set<String> targets = new LinkedHashSet<>();
    for (Finding finding : findings) {
      if (finding.getFilePath() == null || finding.getFilePath().isBlank()) {
        targets.addAll(files.keySet());
      } else if (files.containsKey(finding.getFilePath())) {
        targets.add(finding.getFilePath());
      }
    }
    boolean changed;
    do {
      changed = false;
      for (Map.Entry<String, String> file : files.entrySet()) {
        if (targets.contains(file.getKey())) continue;
        for (String target : List.copyOf(targets)) {
          String filename = target.substring(target.lastIndexOf('/') + 1);
          int dot = filename.lastIndexOf('.');
          String stem = dot > 0 ? filename.substring(0, dot) : filename;
          if (!stem.isBlank() && Pattern.compile(
              "(?s).*\\b" + Pattern.quote(stem) + "\\b.*").matcher(file.getValue()).matches()) {
            changed |= targets.add(file.getKey());
            break;
          }
        }
      }
    } while (changed);
    return targets;
  }
}
