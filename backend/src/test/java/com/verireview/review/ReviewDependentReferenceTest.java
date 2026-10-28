package com.verireview.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewDependentReferenceTest {

  @Test
  void detectsJavaImport() {
    assertThat(ReviewService.references("src/Use.java",
        "import src.Target;\nclass Use {}", "src/Target.java")).isTrue();
  }

  @Test
  void detectsTypeScriptImportAndRequire() {
    assertThat(ReviewService.references("src/use.ts",
        "import { Target } from './Target';", "src/Target.ts")).isTrue();
    assertThat(ReviewService.references("src/use.js",
        "const target = require('./Target');", "src/Target.js")).isTrue();
  }

  @Test
  void detectsPythonImport() {
    assertThat(ReviewService.references("app/use.py",
        "from app.target import Target", "app/target.py")).isTrue();
  }
}
