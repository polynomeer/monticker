// Next 16은 `next lint`를 없앴고 eslint-config-next 16은 ESLint 9 flat config만 제공한다(.eslintrc.json에서 옮김).
import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

export default defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    rules: {
      "@typescript-eslint/no-unused-vars": ["error", { ignoreRestSiblings: true }],
      // eslint-config-next 16이 가져온 eslint-plugin-react-hooks 7의 React Compiler 규칙. 기존 코드 61곳(42개 파일,
      // 대부분 set-state-in-effect)이 걸린다 — 업그레이드 PR에 동작 변경을 섞지 않으려고 warn으로 둔다.
      // 고치는 작업은 docs/dependency-upgrade-plan.md W1 후속에 있다. 다 고치면 error로 되돌린다.
      "react-hooks/set-state-in-effect": "warn",
      "react-hooks/purity": "warn",
      "react-hooks/refs": "warn",
      "react-hooks/use-memo": "warn",
      "react-hooks/immutability": "warn",
    },
  },
  globalIgnores([".next/**", "out/**", "build/**", "next-env.d.ts", "test-results/**", "playwright-report/**"]),
]);
