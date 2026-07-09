#!/usr/bin/env python3
"""
Candle Backfill Script
======================
Yahoo Finance에서 일봉 OHLCV를 가져와 monticker DB의 candles_1m / candles_1d에 적재한다.

사용법:
    # 전체 활성 종목 · 1년치 (기본)
    python scripts/backfill-candles.py

    # 기간 지정
    python scripts/backfill-candles.py --from 2023-01-01 --to 2024-12-31

    # 특정 종목만
    python scripts/backfill-candles.py --symbols 005930 AAPL NVDA

    # 캐시 무시하고 강제 재다운로드
    python scripts/backfill-candles.py --no-cache

    # DB 연결 정보를 명시
    python scripts/backfill-candles.py --db-url postgresql://monticker:monticker@localhost:5432/monticker

캐시:
    data/backfill/{symbol}_{from}_{to}.csv 형태로 저장된다.
    같은 기간 재실행 시 Yahoo Finance 호출 없이 캐시를 사용한다.
    캐시 디렉터리는 .gitignore에 포함되어 있다.

의존성:
    pip install -r scripts/requirements-backfill.txt
"""

import argparse
import csv
import os
import sys
import time
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Optional

# ── 의존성 확인 ──────────────────────────────────────────────────────────────

def check_deps():
    missing = []
    for pkg in ("yfinance", "psycopg2", "dotenv"):
        try:
            __import__(pkg if pkg != "dotenv" else "dotenv")
        except ImportError:
            missing.append(pkg if pkg != "dotenv" else "python-dotenv")
    if missing:
        print(f"[ERROR] 의존성 누락: {', '.join(missing)}")
        print(f"        pip install -r scripts/requirements-backfill.txt")
        sys.exit(1)

check_deps()

import yfinance as yf
import psycopg2
import psycopg2.extras
from dotenv import load_dotenv

# ── 설정 ────────────────────────────────────────────────────────────────────

CACHE_DIR = Path(__file__).parent.parent / "data" / "backfill"
CACHE_DIR.mkdir(parents=True, exist_ok=True)

DEFAULT_DB_URL = "postgresql://monticker:monticker@localhost:5432/monticker"
DEFAULT_DAYS   = 365  # 기본 1년치

# Yahoo Finance 심볼 매핑 (한국 종목은 .KS / .KQ 접미사)
KS_SUFFIX  = ".KS"   # KOSPI
KQ_SUFFIX  = ".KQ"   # KOSDAQ

# 호출 간 대기 (Yahoo Finance 과호출 방지)
FETCH_DELAY_SEC = 0.5

# ── Yahoo Finance 심볼 변환 ─────────────────────────────────────────────────

def to_yahoo_symbol(symbol: str, market: str) -> str:
    """DB symbol → Yahoo Finance 티커"""
    if market in ("KOSPI",):
        return symbol + KS_SUFFIX
    if market in ("KOSDAQ",):
        return symbol + KQ_SUFFIX
    # NASDAQ, NYSE, 기타는 그대로
    return symbol

# ── 캐시 ────────────────────────────────────────────────────────────────────

def cache_path(symbol: str, from_date: date, to_date: date) -> Path:
    return CACHE_DIR / f"{symbol}_{from_date}_{to_date}.csv"

def read_cache(symbol: str, from_date: date, to_date: date) -> Optional[list[dict]]:
    p = cache_path(symbol, from_date, to_date)
    if not p.exists():
        return None
    rows = []
    with open(p, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append(row)
    return rows if rows else None

def write_cache(symbol: str, from_date: date, to_date: date, rows: list[dict]):
    if not rows:
        return
    p = cache_path(symbol, from_date, to_date)
    with open(p, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["date", "open", "high", "low", "close", "volume"])
        writer.writeheader()
        writer.writerows(rows)
    print(f"  캐시 저장: {p.name} ({len(rows)}행)")

# ── Yahoo Finance 조회 ───────────────────────────────────────────────────────

def fetch_from_yahoo(yahoo_symbol: str, from_date: date, to_date: date) -> Optional[list[dict]]:
    """Yahoo Finance에서 일봉 OHLCV 조회. 실패 시 None 반환."""
    try:
        ticker = yf.Ticker(yahoo_symbol)
        df = ticker.history(
            start=str(from_date),
            end=str(to_date + timedelta(days=1)),
            interval="1d",
            auto_adjust=True,
            actions=False,
        )
        if df.empty:
            return None

        rows = []
        for idx, row in df.iterrows():
            d = idx.date() if hasattr(idx, "date") else idx
            rows.append({
                "date":   str(d),
                "open":   f"{float(row['Open']):.4f}",
                "high":   f"{float(row['High']):.4f}",
                "low":    f"{float(row['Low']):.4f}",
                "close":  f"{float(row['Close']):.4f}",
                "volume": str(int(row["Volume"])),
            })
        return rows if rows else None

    except Exception as e:
        print(f"  Yahoo Finance 오류 ({yahoo_symbol}): {e}")
        return None

# ── DB 적재 ──────────────────────────────────────────────────────────────────

UPSERT_1M = """
INSERT INTO candles_1m (stock_id, candle_time, open, high, low, close, volume)
VALUES %s
ON CONFLICT (stock_id, candle_time) DO NOTHING
"""

UPSERT_1D = """
INSERT INTO candles_1d (stock_id, candle_time, open, high, low, close, volume)
VALUES %s
ON CONFLICT (stock_id, candle_time) DO NOTHING
"""

def insert_candles(conn, stock_id: int, rows: list[dict]) -> int:
    """candles_1m (09:00 KST) 과 candles_1d 양쪽에 삽입. 삽입된 행 수 반환."""
    if not rows:
        return 0

    # 09:00 KST = 00:00 UTC (UTC+9)
    values_1m = []
    values_1d = []
    for r in rows:
        d = datetime.strptime(r["date"], "%Y-%m-%d")
        candle_time_1m = d.replace(hour=9, minute=0, second=0)  # 09:00 KST (naive, stored as local)
        candle_time_1d = d.replace(hour=0, minute=0, second=0)  # 00:00 일봉
        t = (
            stock_id,
            candle_time_1m,
            float(r["open"]), float(r["high"]), float(r["low"]), float(r["close"]),
            int(r["volume"]),
        )
        values_1m.append(t)
        values_1d.append((stock_id, candle_time_1d,
                          float(r["open"]), float(r["high"]), float(r["low"]), float(r["close"]),
                          int(r["volume"])))

    with conn.cursor() as cur:
        psycopg2.extras.execute_values(cur, UPSERT_1M, values_1m)
        inserted_1m = cur.rowcount
        psycopg2.extras.execute_values(cur, UPSERT_1D, values_1d)
    conn.commit()
    return inserted_1m

# ── 활성 종목 조회 ───────────────────────────────────────────────────────────

def fetch_active_stocks(conn, symbols_filter: list[str]) -> list[dict]:
    with conn.cursor() as cur:
        if symbols_filter:
            placeholders = ",".join(["%s"] * len(symbols_filter))
            cur.execute(
                f"SELECT id, symbol, market FROM stocks WHERE is_active = true AND symbol IN ({placeholders}) ORDER BY id",
                symbols_filter,
            )
        else:
            cur.execute("SELECT id, symbol, market FROM stocks WHERE is_active = true ORDER BY id")
        rows = cur.fetchall()
    return [{"id": r[0], "symbol": r[1], "market": r[2]} for r in rows]

# ── 기존 데이터 확인 ─────────────────────────────────────────────────────────

def has_data(conn, stock_id: int, from_date: date, to_date: date) -> bool:
    """해당 종목에 대상 기간 내 데이터가 이미 있으면 True."""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT COUNT(*) FROM candles_1d WHERE stock_id = %s AND candle_time >= %s AND candle_time <= %s",
            (stock_id, from_date, to_date),
        )
        count = cur.fetchone()[0]
    # 기간의 60% 이상 데이터가 있으면 skip (영업일 기준 완전히 채우기 어려움)
    expected = (to_date - from_date).days * 0.6
    return count >= expected

# ── 메인 ────────────────────────────────────────────────────────────────────

def main():
    load_dotenv()

    parser = argparse.ArgumentParser(description="monticker 차트 데이터 백필")
    parser.add_argument("--from", dest="from_date", default=None,
                        help="시작일 YYYY-MM-DD (기본: 1년 전)")
    parser.add_argument("--to", dest="to_date", default=None,
                        help="종료일 YYYY-MM-DD (기본: 어제)")
    parser.add_argument("--symbols", nargs="*", default=[],
                        help="백필할 종목 심볼 목록 (기본: 전체 활성 종목)")
    parser.add_argument("--no-cache", action="store_true",
                        help="캐시를 무시하고 Yahoo Finance에서 강제 재다운로드")
    parser.add_argument("--skip-existing", action="store_true", default=True,
                        help="DB에 이미 데이터가 있는 종목 건너뜀 (기본: True)")
    parser.add_argument("--no-skip-existing", dest="skip_existing", action="store_false",
                        help="DB 기존 데이터 무관하게 모두 적재")
    parser.add_argument("--db-url", default=None,
                        help=f"DB 연결 URL (기본: {DEFAULT_DB_URL})")
    args = parser.parse_args()

    # 날짜 결정
    to_date   = date.fromisoformat(args.to_date)   if args.to_date   else date.today() - timedelta(days=1)
    from_date = date.fromisoformat(args.from_date) if args.from_date else to_date - timedelta(days=DEFAULT_DAYS)

    # DB URL (우선순위: CLI > 환경변수 > 기본값)
    db_url = args.db_url or os.getenv("DATABASE_URL") or DEFAULT_DB_URL

    print(f"")
    print(f"monticker Candle Backfill")
    print(f"  기간: {from_date} ~ {to_date}")
    print(f"  캐시: {'비활성화' if args.no_cache else str(CACHE_DIR)}")
    print(f"  DB  : {db_url.split('@')[-1] if '@' in db_url else db_url}")
    print(f"")

    # DB 연결
    try:
        conn = psycopg2.connect(db_url)
    except Exception as e:
        print(f"[ERROR] DB 연결 실패: {e}")
        print(f"        docker compose up -d postgres 로 DB를 먼저 기동하세요.")
        sys.exit(1)

    # 종목 목록
    stocks = fetch_active_stocks(conn, args.symbols)
    if not stocks:
        print("[WARN] 대상 종목이 없습니다. stocks 테이블을 확인하세요.")
        conn.close()
        return

    print(f"대상 종목: {len(stocks)}개\n")

    inserted_total = 0
    skipped_existing = 0
    skipped_no_data  = 0
    errors = []

    for i, stock in enumerate(stocks, 1):
        symbol = stock["symbol"]
        market = stock["market"]
        stock_id = stock["id"]
        yahoo_sym = to_yahoo_symbol(symbol, market)

        prefix = f"[{i:3d}/{len(stocks)}] {symbol:<10} ({market})"

        # DB 기존 데이터 확인
        if args.skip_existing and has_data(conn, stock_id, from_date, to_date):
            print(f"{prefix} → skip (DB에 이미 데이터 있음)")
            skipped_existing += 1
            continue

        # 캐시 확인
        rows = None
        if not args.no_cache:
            rows = read_cache(symbol, from_date, to_date)
            if rows:
                print(f"{prefix} → 캐시 사용 ({len(rows)}행)", end="")

        # Yahoo Finance 조회
        if rows is None:
            print(f"{prefix} → Yahoo ({yahoo_sym}) 조회 중...", end="", flush=True)
            rows = fetch_from_yahoo(yahoo_sym, from_date, to_date)
            time.sleep(FETCH_DELAY_SEC)

            if rows:
                print(f" {len(rows)}행 수신", end="")
                if not args.no_cache:
                    write_cache(symbol, from_date, to_date, rows)
            else:
                print(f" 데이터 없음 (종목 미지원 또는 API 오류)")
                skipped_no_data += 1
                errors.append(f"{symbol} ({yahoo_sym}): 데이터 없음")
                continue

        # DB 적재
        inserted = insert_candles(conn, stock_id, rows)
        print(f" → DB {inserted}행 삽입")
        inserted_total += inserted

    conn.close()

    # 결과 요약
    print(f"\n{'─'*50}")
    print(f"완료")
    print(f"  삽입: {inserted_total:,}행")
    print(f"  skip (기존 데이터): {skipped_existing}종목")
    print(f"  skip (데이터 없음): {skipped_no_data}종목")
    if errors:
        print(f"\n실패 목록:")
        for e in errors:
            print(f"  - {e}")


if __name__ == "__main__":
    main()
