#!/usr/bin/env node
// pnpm v9의 `pnpm audit`는 워크스페이스 전체 lockfile을 스캔하고 `--filter`를 지원하지
// 않는다 — 그래서 apps/web만 건드리는 PR도 apps/mobile의 취약점 때문에 계속 막힌다
// (docs/engineering-backlog.md §6 참고). 이 스크립트는 audit 결과(JSON)를 후처리해서,
// 지정한 워크스페이스 경로에 해당하는 advisory만 게이트에 반영한다.
//
// 사용법:
//   node scripts/ci/audit-scope.js <scope-prefix> [<scope-prefix> ...] [--allow <module>]...
//
// 예:
//   node scripts/ci/audit-scope.js apps/web packages/
//   node scripts/ci/audit-scope.js apps/mobile --allow image-size
//
// --allow <module>은 "이미 확인했지만 지금은 고칠 수 없는" 취약점을 명시적으로 예외
// 처리한다(예: 업스트림에 아직 패치 버전이 없는 경우) — 조용히 넘어가지 않고 왜
// 예외 처리됐는지 로그에 남긴다.

const { execSync } = require("child_process");

function parseArgs(argv) {
  const scopePrefixes = [];
  const allowModules = new Set();
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--allow") {
      allowModules.add(argv[++i]);
    } else {
      scopePrefixes.push(argv[i]);
    }
  }
  return { scopePrefixes, allowModules };
}

function runAudit() {
  try {
    const out = execSync("pnpm audit --audit-level=high --json", {
      encoding: "utf8",
      maxBuffer: 50 * 1024 * 1024,
    });
    return JSON.parse(out);
  } catch (e) {
    // pnpm audit는 high 이상 취약점이 있으면 non-zero exit이지만, stdout에는 여전히
    // 유효한 JSON 리포트를 낸다 — 그 stdout을 파싱해야 한다.
    const out = e.stdout ? e.stdout.toString() : null;
    if (!out) {
      console.error("pnpm audit 실행 자체가 실패했습니다:", e.message);
      process.exit(1);
    }
    try {
      return JSON.parse(out);
    } catch (parseErr) {
      console.error("pnpm audit 출력 파싱 실패:", parseErr.message);
      console.error(out.slice(0, 2000));
      process.exit(1);
    }
  }
}

function main() {
  const { scopePrefixes, allowModules } = parseArgs(process.argv.slice(2));
  if (scopePrefixes.length === 0) {
    console.error("사용법: node audit-scope.js <scope-prefix> [<scope-prefix> ...] [--allow <module>]...");
    process.exit(1);
  }

  const report = runAudit();
  const advisories = report.advisories || {};

  const inScope = [];
  const allowed = [];

  for (const advisory of Object.values(advisories)) {
    const paths = (advisory.findings || []).flatMap((f) => f.paths || []);
    const matchedPaths = paths.filter((p) =>
      scopePrefixes.some((prefix) => p.startsWith(prefix))
    );
    if (matchedPaths.length === 0) continue;

    const entry = {
      module: advisory.module_name,
      severity: advisory.severity,
      title: advisory.title,
      url: advisory.url,
      samplePath: matchedPaths[0],
    };

    if (allowModules.has(advisory.module_name)) {
      allowed.push(entry);
    } else {
      inScope.push(entry);
    }
  }

  if (allowed.length > 0) {
    console.log(`⚠️  범위(${scopePrefixes.join(", ")}) 안에 있지만 명시적으로 예외 처리된 항목 ${allowed.length}건:`);
    for (const a of allowed) {
      console.log(`  - [${a.severity}] ${a.module}: ${a.title} (${a.url})`);
      console.log(`    ${a.samplePath}`);
    }
  }

  if (inScope.length === 0) {
    console.log(`✅ ${scopePrefixes.join(", ")} 범위에 게이트를 막는 high 이상 취약점 없음.`);
    process.exit(0);
  }

  console.error(`❌ ${scopePrefixes.join(", ")} 범위에서 high 이상 취약점 ${inScope.length}건 발견:`);
  for (const a of inScope) {
    console.error(`  - [${a.severity}] ${a.module}: ${a.title} (${a.url})`);
    console.error(`    ${a.samplePath}`);
  }
  process.exit(1);
}

main();
