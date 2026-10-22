import { useState } from "react";
import { KeyboardAvoidingView, Linking, Platform, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { useRouter } from "expo-router";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { login, LoginError } from "@/services/auth";
import { getWebBase } from "@/services/api";
import { useTheme } from "@/theme/ThemeProvider";
import { font, radius } from "@/theme/type";
import { Button, GlassButton, Icon } from "@/components/ui";

/**
 * ADR-109 — 이메일·비밀번호 로그인. 회원가입(동의 필요)·비밀번호 찾기는 웹에서 한다.
 * 시안의 소셜 로그인 버튼은 네이티브 토큰 교환 API가 생기면 붙인다(계획 §4 — Apple 로그인도 함께여야 한다).
 */
export default function LoginScreen() {
  const { c } = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const valid = /^\S+@\S+\.\S+$/.test(email.trim()) && password.length > 0;

  const submit = async () => {
    if (!valid || busy) return;
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
      setPassword("");
      if (router.canGoBack()) router.back(); else router.replace("/");
    } catch (e) {
      setError(e instanceof LoginError ? e.message : "로그인하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  const input = [font.body, s.input, { backgroundColor: c.card, color: c.text }];
  return (
    <KeyboardAvoidingView style={[s.fill, { backgroundColor: c.bg }]} behavior={Platform.OS === "ios" ? "padding" : undefined}>
      <ScrollView contentContainerStyle={[s.content, { paddingTop: Platform.OS === "ios" ? 14 : insets.top + 8, paddingBottom: insets.bottom + 24 }]} keyboardShouldPersistTaps="handled">
        <GlassButton onPress={() => (router.canGoBack() ? router.back() : router.replace("/"))} accessibilityLabel="닫기">
          <Icon name="close" size={20} color={c.text} />
        </GlassButton>
        <View style={s.head}>
          <Text style={[font.largeTitle, { color: c.text }]} accessibilityRole="header">로그인</Text>
          <Text style={[font.subhead, { color: c.muted }]}>계정이 없으면 웹에서 가입한 뒤 같은 이메일로 로그인해 주세요.</Text>
        </View>

        <View style={s.form}>
          <TextInput
            style={input}
            value={email}
            onChangeText={setEmail}
            placeholder="이메일"
            placeholderTextColor={c.muted}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="email-address"
            textContentType="username"
            autoComplete="email"
            accessibilityLabel="이메일"
          />
          <TextInput
            style={input}
            value={password}
            onChangeText={setPassword}
            placeholder="비밀번호"
            placeholderTextColor={c.muted}
            secureTextEntry
            textContentType="password"
            autoComplete="password"
            returnKeyType="go"
            onSubmitEditing={submit}
            accessibilityLabel="비밀번호"
          />
          <Text onPress={() => Linking.openURL(`${getWebBase()}/forgot-password`)} accessibilityRole="link" style={[font.subhead, s.forgot, { color: c.accent }]}>
            비밀번호를 잊었어요
          </Text>
          {error && <Text style={[font.footnote, { color: c.danger }]} accessibilityRole="alert">{error}</Text>}
          <Button label="이메일로 로그인" onPress={submit} disabled={!valid} busy={busy} />
        </View>

        <Text style={[font.footnote, s.center, { color: c.muted }]}>
          계속하면{" "}
          <Text onPress={() => Linking.openURL(`${getWebBase()}/terms`)} accessibilityRole="link" style={{ color: c.accent }}>이용약관</Text>과{" "}
          <Text onPress={() => Linking.openURL(`${getWebBase()}/privacy`)} accessibilityRole="link" style={{ color: c.accent }}>개인정보처리방침</Text>에 동의하게 됩니다.
        </Text>
        <Text style={[font.subhead, s.center, { color: c.muted }]}>
          계정이 없나요?{" "}
          <Text onPress={() => Linking.openURL(`${getWebBase()}/signup`)} accessibilityRole="link" style={[s.bold, { color: c.accent }]}>웹에서 가입</Text>
        </Text>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const s = StyleSheet.create({
  fill: { flex: 1 },
  content: { paddingHorizontal: 16, gap: 20 },
  head: { gap: 6, marginTop: 8 },
  form: { gap: 10 },
  input: { height: 52, borderRadius: radius.tile, paddingHorizontal: 18 },
  forgot: { alignSelf: "flex-end", paddingVertical: 4 },
  center: { textAlign: "center", lineHeight: 19 },
  bold: { fontWeight: "600" },
});
