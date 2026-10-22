import { StyleSheet } from "react-native";
import Svg, { Defs, LinearGradient, Rect, Stop } from "react-native-svg";

/** 탭 바 뒤 바탕색 그라데이션(위 투명 → 아래 바탕 95%) — 목록이 탭 바 밑으로 흐려지며 사라진다 */
export function LinearGradientFade({ color, height = 200 }: { color: string; height?: number }) {
  return (
    <Svg style={[StyleSheet.absoluteFill, { top: undefined, height }]} width="100%" height={height} pointerEvents="none">
      <Defs>
        <LinearGradient id="fade" x1="0" y1="0" x2="0" y2="1">
          <Stop offset="0" stopColor={color} stopOpacity={0} />
          <Stop offset="1" stopColor={color} stopOpacity={0.95} />
        </LinearGradient>
      </Defs>
      <Rect x="0" y="0" width="100%" height={height} fill="url(#fade)" />
    </Svg>
  );
}
