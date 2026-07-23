import { MetadataRoute } from "next";

export default function sitemap(): MetadataRoute.Sitemap {
  const base = process.env.NEXT_PUBLIC_BASE_URL ?? "https://monticker.io";
  const now = new Date();

  return [
    { url: base,                       lastModified: now, changeFrequency: "hourly",  priority: 1.0 },
    { url: `${base}/screener`,         lastModified: now, changeFrequency: "hourly",  priority: 0.9 },
    { url: `${base}/watchlist`,        lastModified: now, changeFrequency: "daily",   priority: 0.7 },
    { url: `${base}/alerts`,           lastModified: now, changeFrequency: "daily",   priority: 0.7 },
    { url: `${base}/quant-lab`,        lastModified: now, changeFrequency: "weekly",  priority: 0.6 },
    { url: `${base}/quant-lab/market`, lastModified: now, changeFrequency: "daily",   priority: 0.6 },
    { url: `${base}/backtest`,         lastModified: now, changeFrequency: "weekly",  priority: 0.5 },
    { url: `${base}/login`,            lastModified: now, changeFrequency: "monthly", priority: 0.3 },
    { url: `${base}/signup`,           lastModified: now, changeFrequency: "monthly", priority: 0.3 },
  ];
}
