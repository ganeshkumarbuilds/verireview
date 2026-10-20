package com.verireview.generation;

/**
 * Optional third-party integrations selected in the generation wizard.
 *
 * <p>These are <em>scope</em> choices, not credentials: the generated code
 * only wires environment-variable placeholders. No provider key is ever
 * collected here or persisted.
 */
public enum GenerationIntegration {
  STRIPE,
  RAZORPAY,
  EMAIL,
  FILE_STORAGE_S3,
  AI_LLM,
  MAPS,
  ANALYTICS,
  DOCKER_CI;

  public String note() {
    return switch (this) {
      case STRIPE -> "Payments with Stripe checkout and webhooks (keys via env).";
      case RAZORPAY -> "Payments with Razorpay orders and webhooks (keys via env).";
      case EMAIL -> "Transactional email sender abstraction (provider key via env).";
      case FILE_STORAGE_S3 -> "S3-compatible object storage for uploads (credentials via env).";
      case AI_LLM -> "An LLM call adapter with the API key read from the environment.";
      case MAPS -> "Map/location lookup adapter with the provider key from the environment.";
      case ANALYTICS -> "Product analytics event hooks (write key via env).";
      case DOCKER_CI -> "Dockerfile and CI workflow that build and test the project.";
    };
  }
}
