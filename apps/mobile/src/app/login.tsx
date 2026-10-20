import { useState } from "react";
import { View, Text, TextInput, TouchableOpacity, StyleSheet, ActivityIndicator, KeyboardAvoidingView, Platform, Linking } from "react-native";
import { useRouter } from "expo-router";
import { login, LoginError } from "@/services/auth";
import { getWebBase } from "@/services/api";

/** ADR-109 — 이메일·비밀번호 로그인. 회원가입(동의 필요)·비밀번호 찾기는 웹에서 한다 */
export default function LoginScreen() {
  const router = useRouter();
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

  return (
    <KeyboardAvoidingView style={s.container} behavior={Platform.OS === "ios" ? "padding" : undefined}>
      <View style={s.form}>
        <Text style={s.label}>이메일</Text>
        <TextInput
          style={s.input}
          value={email}
          onChangeText={setEmail}
          placeholder="you@example.com"
          placeholderTextColor={MUTED}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="email-address"
          textContentType="username"
          autoComplete="email"
          accessibilityLabel="이메일"
        />
        <Text style={s.label}>비밀번호</Text>
        <TextInput
          style={s.input}
          value={password}
          onChangeText={setPassword}
          placeholder="비밀번호"
          placeholderTextColor={MUTED}
          secureTextEntry
          textContentType="password"
          autoComplete="password"
          returnKeyType="go"
          onSubmitEditing={submit}
          accessibilityLabel="비밀번호"
        />
        {error && <Text style={s.error} accessibilityRole="alert">{error}</Text>}
        <TouchableOpacity style={[s.cta, (!valid || busy) && s.ctaOff]} onPress={submit} disabled={!valid || busy} accessibilityRole="button">
          {busy ? <ActivityIndicator color="#fff" /> : <Text style={s.ctaText}>로그인</Text>}
        </TouchableOpacity>
        <View style={s.links}>
          <TouchableOpacity onPress={() => Linking.openURL(`${getWebBase()}/signup`)}>
            <Text style={s.link}>회원가입 (웹)</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={() => Linking.openURL(`${getWebBase()}/forgot-password`)}>
            <Text style={s.link}>비밀번호 찾기 (웹)</Text>
          </TouchableOpacity>
        </View>
      </View>
    </KeyboardAvoidingView>
  );
}

const BG = "#0d1117";
const SURF = "#161929";
const BORDER = "#242840";
const TEXT = "#e8eaf6";
const MUTED = "#5c6288";
const ACCENT = "#5c7cfa";

const s = StyleSheet.create({
  container: { flex: 1, backgroundColor: BG, justifyContent: "center" },
  form:      { paddingHorizontal: 24, gap: 8 },
  label:     { color: MUTED, fontSize: 12, marginTop: 8 },
  input:     { backgroundColor: SURF, borderColor: BORDER, borderWidth: 1, borderRadius: 10, color: TEXT, fontSize: 15, paddingHorizontal: 14, paddingVertical: 12 },
  error:     { color: "#ff5370", fontSize: 13, marginTop: 8 },
  cta:       { backgroundColor: ACCENT, borderRadius: 24, paddingVertical: 14, alignItems: "center", marginTop: 16 },
  ctaOff:    { opacity: 0.5 },
  ctaText:   { color: "#fff", fontWeight: "700", fontSize: 15 },
  links:     { flexDirection: "row", justifyContent: "space-between", marginTop: 16 },
  link:      { color: ACCENT, fontSize: 13 },
});
