# 브랜드 마크

monticker 마크는 몬스터 한 마리다. 둥근 머리 아래가 톱니로 끝나는 몸, 눈 둘, 그리고 **차트선 입** — 오른쪽으로 올라가는 짧은 꺾은선과 끝의 핑크 점(현재가).

| 파일 | 쓰는 곳 |
|---|---|
| `mark.svg` | 기본 마크 (투명 배경, 보라 몸) |
| `app-icon.svg` | iOS 기본 아이콘 1024 — 보라 그라데이션 배경, 흰 몬스터 |
| `app-icon-dark.svg` | iOS 다크 아이콘 — 투명 배경, 밝은 보라 몬스터 |
| `app-icon-tinted.svg` | iOS 틴트 아이콘 — 회색조 (색은 시스템이 입힘) |
| `adaptive-foreground.svg` / `adaptive-monochrome.svg` | 안드로이드 적응형 아이콘 전경 / 테마 아이콘 (가운데 66% 안전 영역) |
| `silhouette.svg` | 단색 실루엣 (눈·입을 뚫음) — 안드로이드 알림 아이콘 |
| `favicon.svg` | 웹 파비콘 — 밝은·어두운 탭 모두에서 보이도록 둥근 사각 배경 |
| `og-image.svg` | 링크 미리보기 1200×630 |

## 규칙

- 색: 몸 보라(`#7c4dd6` 계열), 얼굴은 배경색 또는 진보라, 끝점은 핑크 `#ff79c6`. 초록·빨강·파랑(상승·하락)과 주황(실전 모드)은 마크에 쓰지 않는다.
- 표정은 시장 상태에만 반응한다 (평소 / 발견! / 반가워요 / 장 마감 / 불러오는 중). 수익·손실에 따라 웃거나 우는 표정, 내려가는 입선은 만들지 않는다 — 매매를 부추기는 게임화로 읽힐 수 있다.
- 장식(배, 볼터치, 그림자)을 더하지 않는다. 16px에서도 형태가 읽혀야 한다.

## 다시 굽기

SVG를 고친 뒤 PNG·ICO를 다시 만든다. sharp는 `apps/web`이 설치한 것을 쓰므로 `pnpm install` 뒤에 실행한다.

```bash
node scripts/brand/render-icons.mjs
```

만드는 파일: `apps/mobile/assets/{icon,icon-dark,icon-tinted,adaptive-icon,adaptive-icon-monochrome,notification-icon}.png`, `apps/web/src/app/{icon.svg,apple-icon.png,favicon.ico}`, `apps/web/public/og-image.png`.
