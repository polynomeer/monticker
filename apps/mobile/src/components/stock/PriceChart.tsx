import { StyleSheet, Text, View, useWindowDimensions } from "react-native";
import Svg, { Circle, Line, Polyline } from "react-native-svg";
import { useTheme } from "@/theme/ThemeProvider";
import { font, radius, tabular } from "@/theme/type";
import { movingAverage, scaleY } from "@/lib/chart";
import { formatWon } from "@/lib/format";

const HEIGHT = 200;
const PAD = 18;

/**
 * 종목 상세 선 차트 — 종가 선(등락 색) + MA20(보조색 점선) + 마지막 가격 꼬리표, 이벤트 시각에 강조 점.
 * 시안처럼 축 없이 흐름만 보인다(자세한 차트는 웹).
 */
export function PriceChart({ closes, times, eventTimes = [], up }: { closes: number[]; times: number[]; eventTimes?: number[]; up: boolean }) {
  const { c, market } = useTheme();
  const { width: screen } = useWindowDimensions();
  const width = screen - 32;
  const color = up ? market.up : market.down;

  if (closes.length < 2) {
    return (
      <View style={[s.box, { backgroundColor: c.card, height: HEIGHT }]}>
        <Text style={[font.footnote, s.empty, { color: c.muted }]}>이 기간의 시세가 아직 없어요</Text>
      </View>
    );
  }

  const ma = movingAverage(closes, 20);
  const all = [...closes, ...ma.filter((v): v is number => v != null)];
  const y = scaleY(Math.min(...all), Math.max(...all), HEIGHT, PAD);
  const step = (width - 60) / (closes.length - 1);   // 오른쪽 가격 꼬리표 자리
  const x = (i: number) => 8 + i * step;
  const line = closes.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  const maLine = ma.map((v, i) => (v == null ? null : `${x(i).toFixed(1)},${y(v).toFixed(1)}`)).filter(Boolean).join(" ");
  const lastY = y(closes[closes.length - 1]);
  // 이벤트 시각에 가장 가까운 봉
  const marks = eventTimes
    .map((t) => times.findIndex((tt, i) => tt <= t && (times[i + 1] ?? Infinity) > t))
    .filter((i, k, all) => i >= 0 && all.indexOf(i) === k);

  return (
    <View style={[s.box, { backgroundColor: c.card }]} accessibilityLabel={`가격 차트, 마지막 ${formatWon(closes[closes.length - 1])}`}>
      <Svg width={width} height={HEIGHT}>
        {maLine && <Polyline points={maLine} fill="none" stroke={c.muted} strokeWidth={1.4} strokeDasharray="4 4" />}
        <Line x1={x(0)} x2={width} y1={lastY} y2={lastY} stroke={color} strokeWidth={1} strokeDasharray="2 4" opacity={0.6} />
        <Polyline points={line} fill="none" stroke={color} strokeWidth={2.2} strokeLinejoin="round" strokeLinecap="round" />
        {marks.map((i) => <Circle key={i} cx={x(i)} cy={y(closes[i])} r={5} fill={c.accent} />)}
      </Svg>
      <Text style={[font.caption, s.legend, { color: c.muted }]}>MA20</Text>
      <View style={[s.tag, { top: lastY - 10, backgroundColor: color }]}>
        <Text style={[s.tagText, tabular, { color: up ? market.onUp : market.onDown }]}>{formatWon(closes[closes.length - 1])}</Text>
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  box: { height: HEIGHT, borderRadius: radius.card, overflow: "hidden", justifyContent: "center" },
  empty: { textAlign: "center" },
  legend: { position: "absolute", left: 14, top: 12, fontSize: 11 },
  tag: { position: "absolute", right: 10, paddingHorizontal: 7, paddingVertical: 2, borderRadius: 8 },
  tagText: { fontSize: 11, fontWeight: "700" },
});
