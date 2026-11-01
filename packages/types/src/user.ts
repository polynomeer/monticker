/** GET /api/users/me — 내 계정 프로필 */
export interface UserProfile {
  id: number;
  email: string;
  nickname: string;
  /** LOCAL | GOOGLE | KAKAO | NAVER */
  provider: string;
  emailVerified: boolean;
  createdAt: string;
}
