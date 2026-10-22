import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { AppState, StyleSheet, Text, View } from "react-native";
import { useTheme } from "@/theme/ThemeProvider";
import { font } from "@/theme/type";
import { authenticate, useAppLockEnabled } from "@/services/appLock";
import { Button, Icon } from "@/components/ui";

/** 잠금을 켰으면 앱을 새로 열 때와 백그라운드에서 돌아올 때 화면을 가리고 인증을 묻는다 */
export function LockGate({ children, active }: { children: ReactNode; active: boolean }) {
  const { c } = useTheme();
  const { enabled, loaded } = useAppLockEnabled();
  const [locked, setLocked] = useState(true);
  const wasBackground = useRef(false);

  const unlock = useCallback(async () => {
    if (await authenticate("monticker 잠금 해제")) setLocked(false);
  }, []);

  useEffect(() => {
    if (!loaded) return;
    if (!enabled || !active) { setLocked(false); return; }
    unlock();
    const sub = AppState.addEventListener("change", (s) => {
      if (s === "background") { wasBackground.current = true; setLocked(true); }
      if (s === "active" && wasBackground.current) { wasBackground.current = false; unlock(); }
    });
    return () => sub.remove();
  }, [enabled, loaded, active, unlock]);

  const show = loaded && enabled && active && locked;
  return (
    <>
      {children}
      {show && (
        <View style={[StyleSheet.absoluteFill, s.cover, { backgroundColor: c.bg }]} accessibilityViewIsModal>
          <Icon name="shield" size={44} color={c.accent} />
          <Text style={[font.title, { color: c.text }]}>monticker가 잠겨 있어요</Text>
          <Button label="잠금 해제" onPress={unlock} style={s.button} />
        </View>
      )}
    </>
  );
}

const s = StyleSheet.create({
  cover: { alignItems: "center", justifyContent: "center", gap: 14, padding: 32 },
  button: { alignSelf: "stretch", marginTop: 8 },
});
