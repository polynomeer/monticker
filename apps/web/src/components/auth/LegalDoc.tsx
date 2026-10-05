"use client";

import Link from "next/link";
import { useEffect, useState, type ReactNode } from "react";
import { BtnLink, Notice } from "@/components/terminal";
import { cn } from "@/lib/utils";
import { BrandLink } from "./AuthShell";

export interface LegalSection {
  heading: string;
  body: ReactNode;
}

const DOCS = [
  { key: "terms", href: "/terms", label: "이용약관" },
  { key: "privacy", href: "/privacy", label: "개인정보 처리방침" },
] as const;

/**
 * 약관·개인정보 문서 화면 — 상단 바(로고 · 앱으로 돌아가기) + 목차 | 본문. 시안 p_account.legal().
 * 법률 문구는 각 페이지가 그대로 넘긴다 — 이 컴포넌트는 배치만 한다.
 */
export function LegalDoc({
  doc, title, meta, intro, sections,
}: {
  doc: "terms" | "privacy";
  title: string;
  meta: ReactNode;
  intro?: ReactNode;
  sections: LegalSection[];
}) {
  const [active, setActive] = useState(0);

  // 스크롤 위치에 맞춰 목차 강조
  useEffect(() => {
    if (typeof IntersectionObserver === "undefined") return;
    const els = sections.map((_, i) => document.getElementById(`sec-${i}`)).filter((e): e is HTMLElement => !!e);
    const io = new IntersectionObserver(
      (entries) => {
        const vis = entries.filter((e) => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
        if (vis[0]) setActive(Number(vis[0].target.id.slice(4)));
      },
      { rootMargin: "0px 0px -70% 0px" },
    );
    els.forEach((e) => io.observe(e));
    return () => io.disconnect();
  }, [sections]);

  return (
    <div className="min-h-screen bg-tm-page text-sm text-dracula-fg">
      <header className="flex items-center justify-between gap-3 border-b border-tm-line px-4 py-4 sm:px-6">
        <BrandLink size={26} />
        <BtnLink href="/" kind="ghost" className="h-9">앱으로 돌아가기</BtnLink>
      </header>

      <div className="mx-auto flex max-w-[1120px] flex-wrap items-start gap-8 px-4 pb-16 pt-8 sm:px-6">
        <nav aria-label="목차" className="flex flex-[0_1_220px] flex-col gap-0.5 md:sticky md:top-6">
          <span className="px-2.5 pb-2 text-2xs text-tm-muted">목차</span>
          {sections.map((s, i) => (
            <a
              key={s.heading}
              href={`#sec-${i}`}
              aria-current={active === i ? "location" : undefined}
              className={cn(
                "block rounded-md px-2.5 py-[7px] text-13",
                active === i ? "bg-tm-raised text-dracula-fg" : "text-tm-soft hover:text-dracula-fg",
              )}
            >
              {s.heading}
            </a>
          ))}
        </nav>

        <article className="flex min-w-0 flex-[999_1_520px] flex-col gap-7">
          <div className="flex flex-col gap-1.5 border-b border-tm-line pb-4">
            <div className="flex">
              <div className="inline-flex gap-0.5 rounded-lg bg-tm-inner p-[3px]">
                {DOCS.map((d) => (
                  <Link
                    key={d.key}
                    href={d.href}
                    aria-current={d.key === doc ? "page" : undefined}
                    className={cn(
                      "inline-flex h-7 items-center whitespace-nowrap rounded-md px-3 text-13",
                      d.key === doc ? "bg-tm-line2 font-semibold text-dracula-fg" : "text-tm-muted hover:text-dracula-fg",
                    )}
                  >
                    {d.label}
                  </Link>
                ))}
              </div>
            </div>
            <h1 className="m-0 mt-3 text-[1.75rem] font-bold">{title}</h1>
            <span className="text-13 text-tm-muted">{meta}</span>
          </div>

          <Notice tone="warn">
            이 페이지는 초안(draft)입니다. 실제 서비스 오픈 전 법률 자문을 거쳐 확정됩니다. 주황색으로
            표시된 항목은 아직 확정되지 않은 부분입니다.
          </Notice>

          {intro && <div className="text-15 leading-[1.8] text-tm-soft">{intro}</div>}

          {sections.map((s, i) => (
            <section key={s.heading} id={`sec-${i}`} className="flex scroll-mt-6 flex-col gap-2.5">
              <h2 className="m-0 text-lg font-bold">{s.heading}</h2>
              <div className="text-15 leading-[1.8] text-tm-soft [&_li]:leading-[1.7] [&_ul]:m-0 [&_ul]:flex [&_ul]:list-disc [&_ul]:flex-col [&_ul]:gap-1.5 [&_ul]:pl-5 [&_p]:m-0">
                {s.body}
              </div>
            </section>
          ))}
        </article>
      </div>
    </div>
  );
}
