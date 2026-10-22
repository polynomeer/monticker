import { useEffect, useRef, useState } from "react";
import { Animated, Easing, Pressable, StyleSheet, Text, View } from "react-native";
import * as Haptics from "expo-haptics";
import { font } from "@/theme/type";
import { LONG_PRESS_MS } from "@/lib/liveOrder";

/**
 * 길게 눌러 확인하는 버튼(ADR-113 실전 주문). 누르는 동안 안쪽이 채워지고, 끝까지 누르면 햅틱과 함께 onConfirm.
 * 중간에 떼면 아무 일도 없다. VoiceOver는 두 번 탭한 채 누르기로 같은 동작을 한다.
 */
export function HoldToConfirm({ label, color, fg, disabled, busy, onConfirm, accessibilityLabel }: {
  label: string; color: string; fg: string; disabled?: boolean; busy?: boolean; onConfirm: () => void; accessibilityLabel: string;
}) {
  const progress = useRef(new Animated.Value(0)).current;
  const [width, setWidth] = useState(0);
  const done = useRef(false);
  // 누르기 시작한 뒤 바뀐 값을 끝나는 순간에 읽는다(눌린 1초 사이 거래 중지·한도 초과가 되면 보내지 않는다)
  const confirmRef = useRef(onConfirm);
  const blockedRef = useRef(!!(disabled || busy));
  confirmRef.current = onConfirm;
  blockedRef.current = !!(disabled || busy);

  useEffect(() => () => progress.stopAnimation(), [progress]);
  useEffect(() => {
    if (disabled || busy) { progress.stopAnimation(); progress.setValue(0); }
  }, [disabled, busy, progress]);

  const start = () => {
    if (disabled || busy) return;
    done.current = false;
    Haptics.selectionAsync().catch(() => undefined);
    Animated.timing(progress, { toValue: 1, duration: LONG_PRESS_MS, easing: Easing.linear, useNativeDriver: false }).start(({ finished }) => {
      if (!finished || done.current || blockedRef.current) return;
      done.current = true;
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning).catch(() => undefined);
      confirmRef.current();
      progress.setValue(0);
    });
  };
  const cancel = () => {
    if (done.current) return;
    progress.stopAnimation();
    Animated.timing(progress, { toValue: 0, duration: 150, useNativeDriver: false }).start();
  };

  const fill = progress.interpolate({ inputRange: [0, 1], outputRange: [0, width] });
  return (
    <Pressable
      onPressIn={start}
      onPressOut={cancel}
      disabled={disabled || busy}
      onLayout={(e) => setWidth(e.nativeEvent.layout.width)}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityHint="길게 누르면 주문이 나갑니다"
      accessibilityState={{ disabled: !!(disabled || busy), busy: !!busy }}
      style={[s.button, { backgroundColor: color, opacity: disabled ? 0.4 : 1 }]}
    >
      <Animated.View style={[s.fill, { width: fill }]} />
      <View style={s.labelWrap}>
        <Text style={[font.headline, { color: fg }]}>{busy ? "주문 보내는 중…" : label}</Text>
      </View>
    </Pressable>
  );
}

const s = StyleSheet.create({
  button: { height: 54, borderRadius: 27, overflow: "hidden", justifyContent: "center" },
  fill: { position: "absolute", left: 0, top: 0, bottom: 0, backgroundColor: "rgba(255, 255, 255, 0.28)" },
  labelWrap: { alignItems: "center" },
});
