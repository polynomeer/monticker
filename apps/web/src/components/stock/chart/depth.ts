import type { DepthLevel, DepthSeries } from "./types";

/**
 * 호가 단계 → 누적 깊이. 매수는 최우선(최고가)부터 아래로, 매도는 최우선(최저가)부터 위로 잔량을 누적한다.
 * 두 계열 모두 가격 오름차순으로 돌려준다(가격 축에 그대로 그린다). 잔량 0 이하·가격이 유한하지 않은 단계는 버린다.
 */
export function cumulativeDepth(bids: DepthLevel[], asks: DepthLevel[]): DepthSeries {
  const clean = (ls: DepthLevel[]) => ls.filter((l) => Number.isFinite(l.price) && l.price > 0 && l.quantity > 0);

  const bidPoints: [number, number][] = [];
  let acc = 0;
  for (const l of [...clean(bids)].sort((a, b) => b.price - a.price)) {
    acc += l.quantity;
    bidPoints.push([l.price, acc]);
  }
  bidPoints.reverse();

  const askPoints: [number, number][] = [];
  acc = 0;
  for (const l of [...clean(asks)].sort((a, b) => a.price - b.price)) {
    acc += l.quantity;
    askPoints.push([l.price, acc]);
  }

  return {
    bids: bidPoints,
    asks: askPoints,
    bidTotal: bidPoints.length ? bidPoints[0][1] : 0,
    askTotal: askPoints.length ? askPoints[askPoints.length - 1][1] : 0,
    bestBid: bidPoints.length ? bidPoints[bidPoints.length - 1][0] : null,
    bestAsk: askPoints.length ? askPoints[0][0] : null,
  };
}
