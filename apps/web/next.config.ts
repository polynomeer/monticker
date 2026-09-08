import type { NextConfig } from "next";

const apiBase = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

const securityHeaders = [
  { key: "X-Frame-Options",        value: "SAMEORIGIN" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy",        value: "strict-origin-when-cross-origin" },
  { key: "Permissions-Policy",     value: "camera=(), microphone=(), geolocation=()" },
  {
    key: "Content-Security-Policy",
    value: [
      "default-src 'self'",
      // https://js.tosspayments.com: 정기결제 SDK(@tosspayments/tosspayments-sdk)는 npm
      // 패키지 자체가 아니라 이 스크립트를 런타임에 동적 로드하는 얇은 로더다 — 카드 정보
      // 입력 UI를 우리 페이지가 아니라 토스 도메인에서 렌더링해야 PCI 스코프를 우리 쪽으로
      // 가져오지 않기 때문(정기결제 위젯 연동 문서 참고).
      "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://js.tosspayments.com",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: blob: https://static.toss.im",
      // SockJS는 실제 WebSocket으로 업그레이드하기 전에 `${apiBase}/ws/info` 등으로
      // 일반 HTTP(S) 협상 요청을 먼저 보낸다. ws:/wss: 스킴만 허용하면 이 협상이
      // CSP에 막혀 실시간 시세/체결 기능이 전부 연결되지 않는다.
      `connect-src 'self' ws: wss: ${apiBase} https://*.tosspayments.com`,
      // 결제수단 선택/본인인증 등 토스 쪽 팝업이 iframe으로 뜨는 경우가 있다.
      "frame-src 'self' https://*.tosspayments.com",
      "font-src 'self'",
      "frame-ancestors 'none'",
    ].join("; "),
  },
];

const nextConfig: NextConfig = {
  output: "standalone",
  eslint: { ignoreDuringBuilds: true },
  transpilePackages: ["@monticker/types"],
  async headers() {
    return [{ source: "/(.*)", headers: securityHeaders }];
  },
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${apiBase}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
