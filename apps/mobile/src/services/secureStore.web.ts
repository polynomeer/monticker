/**
 * 웹 미리보기(화면 확인용) 전용 — 브라우저에는 OS 보안 저장소가 없다. refresh token을 브라우저 저장소에 남기지 않도록
 * 메모리에만 두어 새로 고침하면 로그아웃된다. 웹은 배포 대상이 아니다(ADR-109의 저장 결정은 네이티브에만 해당).
 */
const memory = new Map<string, string>();

export type SecureStoreOptions = { keychainAccessible?: number };
export const WHEN_UNLOCKED_THIS_DEVICE_ONLY = 0;

export async function getItemAsync(key: string, _options?: SecureStoreOptions): Promise<string | null> {
  return memory.get(key) ?? null;
}

export async function setItemAsync(key: string, value: string, _options?: SecureStoreOptions): Promise<void> {
  memory.set(key, value);
}

export async function deleteItemAsync(key: string, _options?: SecureStoreOptions): Promise<void> {
  memory.delete(key);
}
