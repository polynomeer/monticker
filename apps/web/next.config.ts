import type { NextConfig } from "next";

const apiBase = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
// P2-1 — 'unsafe-eval'은 개발 서버(webpack HMR)에만 필요해 프로덕션에서는 뺀다(실측:
// 프로덕션 빌드+standalone 서버로 전 페이지를 훑어도 eval 관련 CSP 위반은 한 건도 없었다).
// script-src의 'unsafe-inline'은 이 값과 달리 못 뺀다 — Next.js App Router가 하이드레이션
// 페이로드(self.__next_f.push(...))를 인라인 <script>로 주입하는데, 이 앱의 페이지 대부분이
// 빌드 타임에 정적 프리렌더링되어(`next build` 출력의 ○ 표시) 그 HTML이 요청 이전에 이미
// 고정된다 — nonce 기반 CSP로 시도했으나(src/middleware.ts, 이후 되돌림) 정적 페이지에는
// nonce를 심을 요청 컨텍스트 자체가 없어 인라인 스크립트가 전부 막혀 화면이 비어버렸다.
// nonce를 쓰려면 앱 전체를 동적 렌더링으로 바꿔야 하는데, 이건 이 보안 헤더 정리 범위를
// 넘는 별도의 아키텍처 결정이다.
const isProd = process.env.NODE_ENV === "production";

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
      `script-src 'self' 'unsafe-inline'${isProd ? "" : " 'unsafe-eval'"} https://js.tosspayments.com`,
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
