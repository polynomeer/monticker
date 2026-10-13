// @testing-library/jest-dom 7.0.1의 vitest 타입 확장은 `Assertion<T>`(타입 인자 1개)를 확장한다. vitest 5는 `Assertion<R, T>`
// (반환 타입 R이 추가됨)라 타입 인자가 달라 병합되지 않고, skipLibCheck가 그 오류를 숨겨 toBeInTheDocument 등이 타입에서
// 사라진다(런타임은 정상). jest-dom이 vitest 5를 지원하면 이 파일을 지운다(docs/dependency-upgrade-plan.md W3).
import type { TestingLibraryMatchers } from "@testing-library/jest-dom/matchers";

declare module "vitest" {
  // T는 vitest의 선언과 타입 인자를 똑같이 맞춰야 병합되므로 쓰지 않아도 둔다.
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type, @typescript-eslint/no-unused-vars
  interface Assertion<R extends void | Promise<void> = void, T = unknown> extends TestingLibraryMatchers<unknown, R> {}
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type
  interface AsymmetricMatchersContaining extends TestingLibraryMatchers<unknown, unknown> {}
}
