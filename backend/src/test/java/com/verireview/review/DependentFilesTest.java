package com.verireview.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DependentFilesTest {

  @Nested
  @DisplayName("Java Dependent Files")
  class JavaTests {

    private final String target = "src/main/java/com/example/service/UserService.java";

    @Test
    void directImportDetected() {
      String content = """
          package com.example.controller;

          import com.example.service.UserService;
          import org.springframework.web.bind.annotation.RestController;

          @RestController
          public class UserController {
            private final UserService users;
          }
          """;
      assertThat(ReviewService.references("src/main/java/com/example/controller/UserController.java", content, target))
          .isTrue();
    }

    @Test
    void staticImportDetected() {
      String content = """
          package com.example.controller;

          import static com.example.service.UserService.DEFAULT_TIMEOUT;

          public class UserController {
          }
          """;
      assertThat(ReviewService.references("src/main/java/com/example/controller/UserController.java", content, target))
          .isTrue();
    }

    @Test
    void wildcardImportDetected() {
      String content = """
          package com.example.controller;

          import com.example.service.*;

          public class UserController {
            UserService users;
          }
          """;
      assertThat(ReviewService.references("src/main/java/com/example/controller/UserController.java", content, target))
          .isTrue();
    }

    @Test
    void unrelatedJavaImportNotDetected() {
      String content = """
          package com.example.controller;

          import com.example.other.OtherService;

          public class UserController {
            OtherService other;
          }
          """;
      assertThat(ReviewService.references("src/main/java/com/example/controller/UserController.java", content, target))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("TypeScript and JavaScript Dependent Files")
  class TypeScriptJavaScriptTests {

    private final String target = "src/services/userService.ts";

    @Test
    void esImportFromDetected() {
      String content = """
          import { getUser } from '../services/userService';

          export function UserCard() {
            return getUser();
          }
          """;
      assertThat(ReviewService.references("src/components/UserCard.tsx", content, target))
          .isTrue();
    }

    @Test
    void commonJsRequireDetected() {
      String content = """
          const { getUser } = require('../services/userService');

          module.exports = { getUser };
          """;
      assertThat(ReviewService.references("src/components/userCard.js", content, target))
          .isTrue();
    }

    @Test
    void dynamicImportDetected() {
      String content = """
          export async function loadUser() {
            const mod = await import('../services/userService');
            return mod.getUser();
          }
          """;
      assertThat(ReviewService.references("src/components/UserCard.tsx", content, target))
          .isTrue();
    }

    @Test
    void exportFromDetected() {
      String content = """
          export * from '../services/userService';
          """;
      assertThat(ReviewService.references("src/components/index.ts", content, target))
          .isTrue();
    }

    @Test
    void importWithExtensionDetected() {
      String content = """
          import '../services/userService.ts';
          """;
      assertThat(ReviewService.references("src/components/UserCard.tsx", content, target))
          .isTrue();
    }

    @Test
    void unrelatedTsImportNotDetected() {
      String content = """
          import { getProduct } from '../services/productService';

          export function ProductCard() {
            return getProduct();
          }
          """;
      assertThat(ReviewService.references("src/components/ProductCard.tsx", content, target))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("Python Dependent Files")
  class PythonTests {

    private final String target = "app/services/user_service.py";

    @Test
    void fromModuleImportDetected() {
      String content = """
          from app.services.user_service import UserService

          service = UserService()
          """;
      assertThat(ReviewService.references("app/api/user_routes.py", content, target))
          .isTrue();
    }

    @Test
    void relativeImportDetected() {
      String content = """
          from .user_service import UserService

          service = UserService()
          """;
      assertThat(ReviewService.references("app/services/test_service.py", content, target))
          .isTrue();
    }

    @Test
    void fromPackageImportModuleDetected() {
      String content = """
          from services import user_service

          user_service.find()
          """;
      assertThat(ReviewService.references("app/main.py", content, target))
          .isTrue();
    }

    @Test
    void absoluteImportDetected() {
      String content = """
          import user_service

          user_service.find()
          """;
      assertThat(ReviewService.references("app/main.py", content, target))
          .isTrue();
    }

    @Test
    void includeStatementDetected() {
      String content = """
          include user_service.py
          """;
      assertThat(ReviewService.references("app/main.py", content, target))
          .isTrue();
    }

    @Test
    void unrelatedPythonImportNotDetected() {
      String content = """
          import payment_service

          payment_service.pay()
          """;
      assertThat(ReviewService.references("app/api/user_routes.py", content, target))
          .isFalse();
    }
  }
}

