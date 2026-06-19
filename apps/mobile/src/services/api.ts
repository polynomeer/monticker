const DEV_API_BASE = "http://localhost:8080";

export function getApiBase(): string {
  return process.env.EXPO_PUBLIC_API_URL ?? DEV_API_BASE;
}
