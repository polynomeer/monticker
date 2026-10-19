// assets/brand/*.svg 를 앱·웹에서 쓰는 PNG/ICO로 굽는다.
// 실행: node scripts/brand/render-icons.mjs  (sharp는 apps/web의 next가 가져오는 것을 빌려 쓴다)
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { readFile, writeFile, copyFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const sharp = createRequire(path.join(root, "apps/web/package.json"))("sharp");
const src = (name) => path.join(root, "assets/brand", name);
const out = (rel) => path.join(root, rel);

async function png(svgName, size, dest, { flatten } = {}) {
  let img = sharp(await readFile(src(svgName)), { density: 72 * Math.ceil(size / 100) }).resize(size, size);
  // iOS 기본 아이콘은 알파 채널이 있으면 App Store 검수에서 거절된다
  if (flatten) img = img.flatten({ background: flatten }).removeAlpha();
  await writeFile(out(dest), await img.png().toBuffer());
  return dest;
}

// PNG 여러 장을 담은 ICO (Vista 이후 모든 브라우저가 PNG 항목을 읽는다)
async function ico(svgName, sizes, dest) {
  const images = await Promise.all(
    sizes.map((s) => sharp(readFileSync(src(svgName)), { density: 300 }).resize(s, s).png().toBuffer()),
  );
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(images.length, 4);
  const dir = Buffer.alloc(16 * images.length);
  let offset = 6 + dir.length;
  images.forEach((buf, i) => {
    const s = sizes[i];
    dir.writeUInt8(s >= 256 ? 0 : s, i * 16);
    dir.writeUInt8(s >= 256 ? 0 : s, i * 16 + 1);
    dir.writeUInt16LE(1, i * 16 + 4);
    dir.writeUInt16LE(32, i * 16 + 6);
    dir.writeUInt32LE(buf.length, i * 16 + 8);
    dir.writeUInt32LE(offset, i * 16 + 12);
    offset += buf.length;
  });
  await writeFile(out(dest), Buffer.concat([header, dir, ...images]));
  return dest;
}
const made = await Promise.all([
  png("app-icon.svg", 1024, "apps/mobile/assets/icon.png", { flatten: "#6a3fd4" }),
  png("app-icon-dark.svg", 1024, "apps/mobile/assets/icon-dark.png"),
  png("app-icon-tinted.svg", 1024, "apps/mobile/assets/icon-tinted.png"),
  png("adaptive-foreground.svg", 1024, "apps/mobile/assets/adaptive-icon.png"),
  png("adaptive-monochrome.svg", 1024, "apps/mobile/assets/adaptive-icon-monochrome.png"),
  png("silhouette.svg", 96, "apps/mobile/assets/notification-icon.png"),
  png("favicon.svg", 180, "apps/web/src/app/apple-icon.png", { flatten: "#6a3fd4" }),
  ico("favicon.svg", [16, 32, 48], "apps/web/src/app/favicon.ico"),
  (async () => {
    await writeFile(out("apps/web/public/og-image.png"),
      await sharp(await readFile(src("og-image.svg")), { density: 144 }).resize(1200, 630).png().toBuffer());
    return "apps/web/public/og-image.png";
  })(),
]);
// 웹 파비콘 SVG는 Next.js app/icon.svg 규칙으로 그대로 쓴다
await copyFile(src("favicon.svg"), out("apps/web/src/app/icon.svg"));
console.log([...made, "apps/web/src/app/icon.svg"].join("\n"));
