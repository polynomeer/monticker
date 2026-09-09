# 빌드 컨텍스트는 저장소 루트여야 한다 (docker build -f apps/web/Dockerfile . 에서 루트).
# apps/web은 pnpm 워크스페이스 멤버라 package.json(apps/web/)과 pnpm-lock.yaml/.npmrc(루트)가
# 서로 다른 디렉터리에 있어서, 이 둘을 동시에 요구하는 한 이 파일 단독 컨텍스트로는 못 만든다.
FROM node:26-alpine AS deps
WORKDIR /app
RUN npm install -g pnpm@9
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml .npmrc ./
COPY apps/web/package.json apps/web/package.json
COPY apps/mobile/package.json apps/mobile/package.json
COPY packages/types/package.json packages/types/package.json
# --filter로 web과 그 워크스페이스 의존성만 설치한다 — apps/mobile(Expo/네이티브 의존성)까지
# 설치하면 이미지가 쓸데없이 커지고 느려진다. pnpm은 워크스페이스 그래프 검증을 위해 위
# 세 package.json이 전부 존재해야 하지만, 실제로 설치되는 건 web 쪽 의존성뿐이다.
RUN pnpm install --frozen-lockfile --ignore-scripts --filter @monticker/web...

FROM node:26-alpine AS builder
WORKDIR /app
RUN npm install -g pnpm@9
COPY --from=deps /app ./
COPY apps/web apps/web
COPY packages/types packages/types
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm --filter @monticker/web build

FROM node:26-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
RUN addgroup -S monticker && adduser -S monticker -G monticker
# Next standalone 출력은 워크스페이스 루트(pnpm-lock.yaml 위치) 기준 상대 경로를 그대로
# 보존한다 — apps/web이 루트가 아니므로 server.js가 .next/standalone/apps/web/ 아래에 있다.
COPY --from=builder /app/apps/web/.next/standalone ./
COPY --from=builder /app/apps/web/.next/static ./apps/web/.next/static
COPY --from=builder /app/apps/web/public ./apps/web/public
USER monticker
EXPOSE 3000
CMD ["node", "apps/web/server.js"]
