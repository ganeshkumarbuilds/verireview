#Requires -Version 5.1
<#
  VeriReview Phase 0 validator.
  Checks: required files exist, no obvious secrets committed, compose file parses (if docker present).
  Exit 0 = pass, non-zero = fail.
#>
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$failures = @()

function Check-Exists([string]$rel) {
  $p = Join-Path $RepoRoot $rel
  if (-not (Test-Path -LiteralPath $p)) {
    $script:failures += "MISSING: $rel"
    Write-Host "FAIL: missing $rel"
  } else {
    Write-Host "OK:   $rel exists"
  }
}

$required = @(
  "README.md",
  "AGENTS.md",
  ".gitignore",
  ".env.example",
  "LICENSE",
  "docker-compose.yml",
  "docs/PRODUCT_REQUIREMENTS.md",
  "docs/ARCHITECTURE.md",
  "docs/TECH_STACK.md",
  "docs/AGENT_DESIGN.md",
  "docs/DATABASE_DESIGN.md",
  "docs/API_DESIGN.md",
  "docs/SECURITY_DESIGN.md",
  "docs/DEVELOPMENT_ROADMAP.md",
  "docs/adr/README.md",
  "docs/adr/ADR-001-java-21.md",
  "docs/adr/ADR-002-flyway.md",
  "docs/adr/ADR-003-mapstruct.md",
  "docs/adr/ADR-004-modular-monolith.md",
  "docs/adr/ADR-005-ai-boundary-and-verification.md",
  "docs/adr/ADR-006-github-public-import.md",
  "docs/adr/ADR-007-sandbox-limits.md",
  "docs/adr/ADR-008-deferred-tech.md",
  "backend/README.md",
  "frontend/README.md",
  "ai-service/README.md",
  "docker/README.md"
)
foreach ($f in $required) { Check-Exists $f }

# Phase 0 must not contain application build files yet.
$forbidden = @("backend/pom.xml", "frontend/package.json", "ai-service/requirements.txt", "ai-service/pyproject.toml")
foreach ($f in $forbidden) {
  $p = Join-Path $RepoRoot $f
  if (Test-Path -LiteralPath $p) {
    $failures += "OUT-OF-SCOPE: $f must not exist in Phase 0"
    Write-Host "FAIL: out-of-scope file present: $f"
  } else {
    Write-Host "OK:   no premature $f"
  }
}

# Secret scan (simple, local): fail on real-looking secrets in tracked text files.
$scanRoots = @(
  (Join-Path $RepoRoot "README.md"),
  (Join-Path $RepoRoot ".env.example"),
  (Join-Path $RepoRoot "docker-compose.yml"),
  (Join-Path $RepoRoot "scripts")
)
$secretPatterns = @(
  "AKIA[0-9A-Z]{16}",
  "ghp_[A-Za-z0-9]{20,}",
  "xox[bap]-",
  "-----BEGIN (RSA )?PRIVATE KEY-----"
)
$secretHits = @()
foreach ($root in $scanRoots) {
  if (Test-Path -LiteralPath $root) {
    $files = if ((Get-Item -LiteralPath $root).PSIsContainer) {
      Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue
    } else { @(Get-Item -LiteralPath $root) }
    foreach ($file in $files) {
      foreach ($pat in $secretPatterns) {
        $hit = Select-String -LiteralPath $file.FullName -Pattern $pat -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit) { $secretHits += "$($file.Name): $pat" }
      }
    }
  }
}
# .env must never be committed
if (Test-Path -LiteralPath (Join-Path $RepoRoot ".env")) {
  Write-Host "WARN: .env exists locally (must remain uncommitted; .gitignore covers it)"
}
if ($secretHits.Count -gt 0) {
  $failures += "SECRETS: $($secretHits -join '; ')"
  Write-Host "FAIL: secret patterns found: $($secretHits -join '; ')"
} else {
  Write-Host "OK:   no secret patterns in scanned files"
}

# Compose validation (optional: requires docker compose)
$docker = Get-Command "docker" -ErrorAction SilentlyContinue
if ($docker) {
  try {
    & docker compose version | Out-Null
    if ($?) {
      Push-Location $RepoRoot
      try {
        & docker compose config --quiet
        if ($?) { Write-Host "OK:   docker compose config passes" }
        else { $failures += "COMPOSE: docker compose config failed"; Write-Host "FAIL: docker compose config failed" }
      } finally { Pop-Location }
    } else { Write-Host "WARN: 'docker compose' not available; skipped compose check" }
  } catch { Write-Host "WARN: docker check skipped: $($_.Exception.Message)" }
} else {
  Write-Host "WARN: docker not found; skipped compose check"
}

if ($failures.Count -gt 0) {
  Write-Host ""
  Write-Host "PHASE 0 VALIDATION FAILED ($($failures.Count)):"
  $failures | ForEach-Object { Write-Host " - $_" }
  exit 1
}
Write-Host ""
Write-Host "PHASE 0 VALIDATION PASSED"
exit 0
