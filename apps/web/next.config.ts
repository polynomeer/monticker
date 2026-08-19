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
      "script-src 'self' 'unsafe-inline' 'unsafe-eval'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: blob:",
      // SockJS는 실제 WebSocket으로 업그레이드하기 전에 `${apiBase}/ws/info` 등으로
      // 일반 HTTP(S) 협상 요청을 먼저 보낸다. ws:/wss: 스킴만 허용하면 이 협상이
      // CSP에 막혀 실시간 시세/체결 기능이 전부 연결되지 않는다.
      `connect-src 'self' ws: wss: ${apiBase}`,
      "font-src 'self'",
      "frame-ancestors 'none'",
    ].join("; "),
  },
];

const nextConfig: NextConfig = {
  output: "standalone",
  eslint: { ignoreDuringBuilds: true },
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
