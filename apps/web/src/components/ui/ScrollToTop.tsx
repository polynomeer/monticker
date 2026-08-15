"use client";

import { useState, useEffect } from "react";
import { ArrowUp } from "@phosphor-icons/react";

export default function ScrollToTop() {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const onScroll = () => setVisible(window.scrollY > 400);
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  if (!visible) return null;

  return (
    <button
      onClick={() => window.scrollTo({ top: 0, behavior: "smooth" })}
      aria-label="맨 위로"
      className="fixed bottom-6 left-6 z-40 w-10 h-10 rounded-full bg-[#44475a] hover:bg-[#bd93f9] text-[#f8f8f2] hover:text-[#282a36] shadow-lg transition-all flex items-center justify-center"
    >
      <ArrowUp size={18} weight="bold" aria-hidden />
    </button>
  );
}
