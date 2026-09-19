# ai-service/ — Python agentic service (Phase 7+)

Empty in Phase 0. Phase 7 will add the FastAPI + LangGraph service (Python 3.12) per `docs/AGENT_DESIGN.md`.

Binding rule (ADR-005): this service PROPOSES actions and agent results. It must NOT directly write to PostgreSQL. Spring Boot owns all business state and persistence; this service is called by the backend and returns versioned JSON.
